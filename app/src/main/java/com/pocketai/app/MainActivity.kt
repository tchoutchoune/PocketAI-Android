package com.pocketai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var model: ChatViewModel
    private lateinit var status: TextView
    private lateinit var modelBadge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var liveMetricsView: TextView
    private lateinit var messageList: RecyclerView
    private lateinit var sendButton: MaterialButton
    private lateinit var input: TextInputEditText
    private lateinit var webToggle: SwitchMaterial
    private lateinit var attachmentButton: MaterialButton
    private lateinit var attachmentStatus: TextView
    private lateinit var chat: LinearLayout
    private lateinit var modelsPanel: LinearLayout
    private lateinit var creationPanel: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var tabs: TabLayout
    private val shownMessages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var pendingSave: GeneratedArtifact? = null
    private var pendingCameraFile: File? = null
    private var lastModelsKey: Pair<Boolean, String?>? = null
    private var lastCreationKey: Pair<Boolean, Int>? = null
    private var exportInProgress = false
    private lateinit var stopButton: MaterialButton
    private lateinit var newButton: MaterialButton
    private var selectedTab = 0
    private var hubFilter: ModelUse? = null
    private var followStreaming = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var lastAutoSpokenMessageId: String? = null

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importModel)
    }
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::prepareAttachment)
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::transcribeAudio)
    }
    private val cameraPicker = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null && file.isFile && file.length() > 0) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            model.prepareAttachment(uri)
        } else {
            file?.delete()
            if (!success) toast("Photo annulée")
        }
    }
    private val savePicker = registerForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: android.content.Context, input: String): Intent =
            super.createIntent(context, input).apply { type = pendingSave?.mimeType ?: "application/octet-stream" }
    }) { uri ->
        val artifact = pendingSave
        pendingSave = null
        if (uri != null && artifact != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { model.artifacts.save(uri, artifact) }
                toast("Fichier enregistré")
            } catch (error: Exception) { showError(error.message ?: "Enregistrement impossible") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        model = ViewModelProvider(this)[ChatViewModel::class.java]
        savedInstanceState?.getString("pendingPath")?.let { path ->
            val file = File(path)
            if (file.exists() && file.canonicalFile.parentFile == File(filesDir, "generated").canonicalFile) {
                pendingSave = GeneratedArtifact(file, savedInstanceState.getString("pendingMime") ?: "application/octet-stream", savedInstanceState.getString("pendingName") ?: file.name)
            }
        }
        savedInstanceState?.getString("pendingCameraPath")?.let { path ->
            val file = File(path)
            val cameraDir = File(cacheDir, "exports")
            if (file.exists() && runCatching { file.canonicalFile.parentFile == cameraDir.canonicalFile }.getOrDefault(false)) {
                pendingCameraFile = file
            }
        }
        selectedTab = savedInstanceState?.getInt("tab") ?: 0
        val root = column().apply { setBackgroundColor(Color.parseColor("#10171E")) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        val heading = row().apply { setPadding(dp(16), dp(8), dp(16), 0); gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("PocketAI", 25f, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        newButton = button("Nouveau") {
            MaterialAlertDialogBuilder(this).setTitle("Nouvelle conversation ?")
                .setMessage("La conversation enregistrée sera effacée.")
                .setNegativeButton("Annuler", null).setPositiveButton("Effacer") { _, _ -> model.clearConversation() }.show()
        }
        heading.addView(newButton)
        root.addView(heading)
        modelBadge = text("Un assistant local, à ton rythme", 13f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(16), 0, dp(16), dp(6)) }
        root.addView(modelBadge)
        status = text("Préparation…", 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(16), dp(4), dp(16), dp(6)); setTextColor(Color.parseColor("#9DE3C5")) }
        val statusRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(12), 0) }
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        stopButton = button("Arrêter") { model.stop() }.apply { visibility = View.GONE }
        statusRow.addView(stopButton)
        root.addView(statusRow)
        liveMetricsView = text("", 11f).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
            setTextColor(Color.parseColor("#8FB6C9"))
            visibility = View.GONE
            maxLines = 3
        }
        root.addView(liveMetricsView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        val content = android.widget.FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        chat = column()
        modelsPanel = column()
        creationPanel = column()
        settingsPanel = column()
        content.addView(chat, android.widget.FrameLayout.LayoutParams(-1, -1))
        listOf(modelsPanel, creationPanel, settingsPanel).forEach { panel ->
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(panel) }
            content.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        buildChat()
        input.setText(savedInstanceState?.getString("draft").orEmpty())
        tabs = TabLayout(this).apply {
            setBackgroundColor(Color.parseColor("#18212A"))
            setTabTextColors(Color.parseColor("#B7C7D3"), Color.parseColor("#9DE3C5"))
            setSelectedTabIndicatorColor(Color.parseColor("#9DE3C5"))
            listOf("Chat", "Modèles", "Créer", "Réglages").forEach { addTab(newTab().setText(it)) }
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { selectedTab = tab.position; showTab() }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(56)))
        renderModels()
        renderCreation()
        renderSettings()
        tabs.getTabAt(selectedTab)?.select()
        showTab()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    status.text = state.status
                    modelBadge.text = state.modelName?.let { "${if (state.remote) "Serveur" else "Local"} · $it" } ?: "Choisis un modèle dans l’onglet Modèles"
                    liveMetricsView.text = state.liveMetrics
                    liveMetricsView.visibility = if (state.liveMetrics.isBlank()) View.GONE else View.VISIBLE
                    progress.visibility = if (state.busy) View.VISIBLE else View.GONE
                    stopButton.visibility = if (state.busy) View.VISIBLE else View.GONE
                    newButton.isEnabled = !state.busy
                    sendButton.text = if (state.busy) "Arrêter" else "Envoyer"
                    input.isEnabled = !state.busy
                    webToggle.isEnabled = !state.busy
                    attachmentButton.isEnabled = !state.busy
                    attachmentStatus.text = state.attachment?.let { "📎 ${it.summary} · reste joint aux prochaines questions · toucher pour retirer" }.orEmpty()
                    attachmentStatus.visibility = if (state.attachment == null) View.GONE else View.VISIBLE
                    val oldCount = shownMessages.size
                    if (shownMessages != state.messages) {
                        val samePrefix = shownMessages.size == state.messages.size &&
                            shownMessages.dropLast(1) == state.messages.dropLast(1)
                        val oldLastStreaming = shownMessages.lastOrNull()?.isStreaming == true
                        val newLastStreaming = state.messages.lastOrNull()?.isStreaming == true
                        shownMessages.clear()
                        shownMessages.addAll(state.messages)
                        if (oldLastStreaming && !newLastStreaming) {
                            val finished = shownMessages.lastOrNull()
                            if (finished != null && !finished.isUser && ttsAutoRead() && lastAutoSpokenMessageId != finished.id) {
                                lastAutoSpokenMessageId = finished.id
                                speakMessage(finished)
                            }
                        }
                        if (samePrefix && shownMessages.isNotEmpty()) {
                            if (oldLastStreaming && newLastStreaming) {
                                adapter.notifyItemChanged(shownMessages.lastIndex, ChatAdapter.PAYLOAD_STREAM)
                            } else {
                                adapter.notifyItemChanged(shownMessages.lastIndex)
                            }
                        } else {
                            adapter.notifyDataSetChanged()
                        }
                        if (state.messages.size > oldCount) followStreaming = true
                        if (followStreaming && shownMessages.isNotEmpty()) {
                            val target = shownMessages.lastIndex
                            messageList.postOnAnimation {
                                if (followStreaming && target < shownMessages.size) {
                                    messageList.scrollToPosition(target)
                                }
                            }
                        }
                    }
                    val key = state.busy to state.modelName
                    if (key != lastModelsKey) { renderModels(); lastModelsKey = key }
                    val creationKey = state.busy to state.artifacts.size
                    if (lastCreationKey != creationKey) { renderCreation(); lastCreationKey = creationKey }
                    state.error?.let { showError(it); model.dismissError() }
                }
            }
        }
    }

    private fun buildChat() {
        val toggleRow = row().apply { setPadding(dp(12), 0, dp(12), 0); gravity = Gravity.CENTER_VERTICAL }
        webToggle = SwitchMaterial(this).apply {
            text = "Compléter avec le web"
            isChecked = model.settings.webSearchEnabled
            setTextColor(Color.parseColor("#D6E2EA"))
            textSize = 13f
            setOnCheckedChangeListener { _, checked ->
                if (checked && !model.settings.hasBraveKey) {
                    isChecked = false
                    toast("Ajoute une clé Brave Search dans Réglages.")
                    tabs.getTabAt(3)?.select()
                } else model.settings.webSearchEnabled = checked
            }
        }
        toggleRow.addView(webToggle, LinearLayout.LayoutParams(-1, -2))
        chat.addView(toggleRow)

        val attachmentRow = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        attachmentButton = button("📎 Fichier / photo") {
            attachmentPicker.launch(arrayOf(
                "text/*",
                "application/json",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "image/*",
            ))
        }
        attachmentRow.addView(attachmentButton, LinearLayout.LayoutParams(0, -2, 1f))
        attachmentRow.addView(button("📷 Appareil photo") { startCameraCapture() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        chat.addView(attachmentRow)
        attachmentStatus = text("", 11f).apply {
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), dp(6))
            setTextColor(Color.parseColor("#9DE3C5"))
            setOnClickListener { model.clearAttachment() }
        }
        chat.addView(attachmentStatus)

        adapter = ChatAdapter(
            this,
            shownMessages,
            ::chooseExport,
            { message ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("PocketAI", (if (message.isUser) message.content else ResponseText.visible(message.content))))
                toast("Réponse copiée")
            },
            ::speakMessage,
        )
        messageList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = this@MainActivity.adapter
            setPadding(dp(4), dp(8), dp(4), dp(8))
            clipToPadding = false
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) followStreaming = false
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(1)) followStreaming = true
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (!recyclerView.canScrollVertically(1)) followStreaming = true
                }
            })
        }
        chat.addView(messageList, LinearLayout.LayoutParams(-1, 0, 1f))
        val compose = row().apply { gravity = Gravity.BOTTOM; setPadding(dp(12), dp(6), dp(12), dp(8)) }
        val inputLayout = TextInputLayout(this).apply { hint = "Pose ta question…"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5; minLines = 1; textSize = 16f
            setTextColor(Color.parseColor("#EDF4FA"))
            contentDescription = "Question à envoyer"
        }
        inputLayout.addView(input, LinearLayout.LayoutParams(-1, -2))
        compose.addView(inputLayout, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = button("Envoyer") {
            if (model.state.value.busy) model.stop() else {
                val prompt = input.text?.toString()?.trim().orEmpty()
                if (prompt.isNotEmpty()) {
                    val ready = model.state.value.modelName != null
                    model.send(prompt)
                    if (ready) input.setText("")
                }
            }
        }.apply { contentDescription = "Envoyer la question ou arrêter la génération" }
        compose.addView(sendButton, LinearLayout.LayoutParams(-2, dp(58)).apply { leftMargin = dp(8) })
        chat.addView(compose)
    }

    private fun renderModels() {
        modelsPanel.removeAllViews(); pad(modelsPanel)
        modelsPanel.addView(text("Le bon modèle pour ton téléphone", 22f, true))
        modelsPanel.addView(text(HardwareProfile.detect(this).summary, 14f))
        modelsPanel.addView(text("Commence par un petit modèle pour la rapidité. PocketAI peut charger d’autres familles (Qwen, Gemma, DeepSeek, Mistral, Phi, etc.) dès lors que le fichier GGUF et son architecture sont pris en charge par la version intégrée de llama.cpp. Les fichiers restent sur ton téléphone.", 14f))
        modelsPanel.addView(button("Importer un fichier GGUF") { importPicker.launch(arrayOf("*/*")) }.apply { isEnabled = !model.state.value.busy })
        modelsPanel.addView(button("Rechercher un GGUF sur Hugging Face") { huggingFaceSearchDialog() }.apply { isEnabled = !model.state.value.busy })
        modelsPanel.addView(button("Explorer Hugging Face dans le navigateur") {
            openLink("https://huggingface.co/models?library=gguf&sort=trending")
        })
        modelsPanel.addView(text("Modèles installés", 18f, true))
        val installed = model.models.installed()
        if (installed.isEmpty()) modelsPanel.addView(text("Aucun modèle pour l’instant. Importe un GGUF ou télécharge un modèle ci-dessous.", 14f))
        installed.forEach { file ->
            val contents = column()
            contents.addView(text(file.nameWithoutExtension, 16f, true))
            contents.addView(text("${"%.2f".format(file.length() / (1024.0 * 1024 * 1024))} Go · GGUF local", 13f))
            val controls = row()
            val loaded = model.state.value.modelName == file.nameWithoutExtension
            controls.addView(button(if (loaded) "Chargé" else "Charger") { model.loadModel(file); tabs.getTabAt(0)?.select() }.apply { isEnabled = !model.state.value.busy && !loaded })
            controls.addView(button("Supprimer") {
                MaterialAlertDialogBuilder(this).setTitle("Supprimer ce modèle ?").setMessage(file.name)
                    .setNegativeButton("Annuler", null).setPositiveButton("Supprimer") { _, _ ->
                        if (file.delete()) renderModels() else showError("Impossible de supprimer ce modèle.")
                    }.show()
            }.apply { isEnabled = !model.state.value.busy && !loaded })
            contents.addView(controls); modelsPanel.addView(card(contents))
        }
        if (model.state.value.modelName != null) modelsPanel.addView(button("Décharger et libérer la mémoire") { model.unloadModel() }.apply { isEnabled = !model.state.value.busy })
        renderHubCatalogue()
        if (hubFilter == null || hubFilter == ModelUse.CHAT) {
        modelsPanel.addView(text("Modèles historiques", 18f, true))
        ModelRepository.catalogue.filter { entry -> ModelHub.models.none { it.local?.id == entry.id } }.forEach { entry ->
            val contents = column()
            contents.addView(text(entry.title, 17f, true))
            contents.addView(text(entry.description, 14f))
            contents.addView(text("${"%.2f".format(entry.sizeBytes / (1024.0 * 1024 * 1024))} Go · intégrité SHA-256 vérifiée", 12f))
            val controls = row()
            controls.addView(button("Télécharger") {
                MaterialAlertDialogBuilder(this).setTitle("Télécharger ${entry.title} ?")
                    .setMessage("Le téléchargement utilise Internet et ${"%.2f".format(entry.sizeBytes / (1024.0 * 1024 * 1024))} Go de stockage. Consulte la licence du modèle avant utilisation.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Télécharger") { _, _ -> model.downloadModel(entry) }.show()
            }.apply { isEnabled = !model.state.value.busy })
            controls.addView(button("Licence") { openLink(entry.licenseUrl) })
            contents.addView(controls); modelsPanel.addView(card(contents))
        }
        }

        val hf = model.state.value.hfResults
        if (hf.isNotEmpty()) {
            modelsPanel.addView(text("Résultats Hugging Face vérifiés", 18f, true))
            modelsPanel.addView(text("PocketAI n’affiche ici que des fichiers GGUF publics non découpés dont Hugging Face fournit la taille et le SHA-256 LFS. La compatibilité de l’architecture est ensuite vérifiée par llama.cpp au chargement.", 13f))
            hf.forEach { entry ->
                val contents = column()
                contents.addView(text(entry.title, 15f, true))
                contents.addView(text(entry.description, 12f))
                val controls = row()
                controls.addView(button("Télécharger") {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Télécharger ce GGUF ?")
                        .setMessage("${entry.title}\n\n${entry.description}\n\nLe fichier est épinglé au commit Hugging Face trouvé et son SHA-256 sera vérifié avant installation. Vérifie aussi la licence du dépôt.")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Télécharger") { _, _ -> model.downloadModel(entry) }
                        .show()
                }.apply { isEnabled = !model.state.value.busy })
                controls.addView(button("Dépôt") { openLink(entry.licenseUrl) })
                contents.addView(controls)
                modelsPanel.addView(card(contents))
            }
            modelsPanel.addView(button("Effacer les résultats") { model.clearHuggingFaceResults(); renderModels() })
        }
    }

    private fun renderHubCatalogue() {
        modelsPanel.addView(text("Catalogue des modèles", 18f, true))
        modelsPanel.addView(button("Usage : ${hubFilter?.label ?: "Tous"}") {
            val labels = arrayOf("Tous") + ModelUse.entries.map { it.label }.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Choisir un usage").setItems(labels) { _, index ->
                hubFilter = if (index == 0) null else ModelUse.entries[index - 1]; renderModels()
            }.show()
        })
        ModelHub.models.filter { hubFilter == null || it.use == hubFilter ||
            (hubFilter == ModelUse.VISION && it.vision) }.forEach { entry ->
            val contents = column()
            contents.addView(text(entry.title, 17f, true))
            contents.addView(text(entry.description, 14f))
            entry.local?.let { local ->
                contents.addView(text("${"%.2f".format(local.sizeBytes / (1024.0 * 1024 * 1024))} Go · GGUF texte local · SHA-256 vérifié", 12f))
                contents.addView(button("Télécharger le GGUF texte") {
                    MaterialAlertDialogBuilder(this).setTitle("Télécharger ${entry.title} ?")
                        .setMessage("${local.description}\n\n${"%.2f".format(local.sizeBytes / (1024.0 * 1024 * 1024))} Go de stockage, plus la mémoire du contexte. Ce téléchargement contient le texte seul. Consulte la licence du modèle.")
                        .setNegativeButton("Annuler", null).setPositiveButton("Télécharger") { _, _ -> model.downloadModel(local) }.show()
                }.apply { isEnabled = !model.state.value.busy })
            }
            contents.addView(text(if (entry.supportsChat) "Serveur : ${if (entry.vision) "texte + photo" else "texte"}"
                else "Serveur nécessaire · ${entry.use.label}", 12f))
            val controls = row()
            controls.addView(button("Configurer") { hubServerDialog(entry) }.apply { isEnabled = !model.state.value.busy })
            controls.addView(button("Fiche / licence") { openLink(entry.pageUrl) })
            contents.addView(controls)
            val actions = row()
            actions.addView(button("Tester le serveur") {
                hubAction(entry.id) { model.checkRemoteModel(entry) }
            }.apply { isEnabled = !model.state.value.busy })
            actions.addView(button(if (entry.supportsChat) "Utiliser" else "Ouvrir l’usage") {
                if (entry.supportsChat) hubAction(entry.id) {
                    MaterialAlertDialogBuilder(this).setTitle("Utiliser ${entry.title} sur serveur ?")
                        .setMessage("Les questions, l’historique récent et les extraits joints seront envoyés au serveur configuré. ${if (entry.vision) "Les photos jointes seront aussi transmises, réduites à 1 280 pixels." else "Les photos fournissent seulement leur OCR local."} Reviens à un GGUF installé pour utiliser le chat local.")
                        .setNegativeButton("Annuler", null).setPositiveButton("Utiliser") { _, _ ->
                            model.selectRemoteModel(entry); tabs.getTabAt(0)?.select()
                        }.show()
                } else tabs.getTabAt(if (entry.use == ModelUse.EMBEDDING) 3 else 2)?.select()
            }.apply { isEnabled = !model.state.value.busy })
            contents.addView(actions)
            modelsPanel.addView(card(contents))
        }
    }

    private fun chooseHubConfiguration() {
        MaterialAlertDialogBuilder(this).setTitle("Quel modèle configurer ?")
            .setItems(ModelHub.models.map { it.title }.toTypedArray()) { _, index ->
                hubServerDialog(ModelHub.models[index])
            }.show()
    }

    private fun hubAction(id: String, action: () -> Unit) {
        if (model.state.value.busy) return
        if (model.settings.inferenceUrl(id).isBlank()) hubServerDialog(ModelHub.find(id), action)
        else action()
    }

    private fun hubServerDialog(entry: HubModel, afterSave: () -> Unit = {}) {
        if (model.state.value.busy) return
        val wrapper = column().apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val url = EditText(this).apply {
            hint = "URL HTTPS du serveur, terminant souvent par /v1"
            setText(model.settings.inferenceUrl(entry.id)); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val alias = EditText(this).apply {
            hint = "Alias du modèle exposé par le serveur"; setText(model.settings.inferenceModel(entry.id)); isSingleLine = true
        }
        val key = EditText(this).apply {
            hint = "Clé facultative · vide = conserver"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val clearKey = android.widget.CheckBox(this).apply { text = "Supprimer la clé enregistrée"; setTextColor(Color.parseColor("#D6E2EA")) }
        listOf(url, alias, key, clearKey).forEach(wrapper::addView)
        val endpoint = when (entry.use) {
            ModelUse.EMBEDDING -> "/embeddings"
            ModelUse.TRANSCRIPTION -> "/audio/transcriptions"
            ModelUse.SPEECH -> "/audio/speech (WAV)"
            ModelUse.IMAGE -> "/images/generations et /images/edits (b64_json)"
            else -> "/chat/completions${if (entry.vision) " avec image_url" else ""}"
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(entry.title)
            .setMessage("Le serveur doit héberger ce modèle et fournir $endpoint au format compatible OpenAI. Les données saisies dans cet usage lui seront envoyées. La configuration ne déploie pas le modèle. Le test utilise /models lorsqu’il est disponible.")
            .setView(wrapper).setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (model.state.value.busy) return@setOnClickListener
                try {
                    model.settings.configureInference(entry.id, url.text.toString(), alias.text.toString(),
                        if (clearKey.isChecked) "" else key.text.toString().trim().takeIf { it.isNotEmpty() })
                    dialog.dismiss(); renderModels(); renderSettings(); afterSave()
                } catch (error: Exception) { url.error = error.message ?: "Configuration invalide." }
            }
        }
        dialog.show()
    }

    private fun huggingFaceSearchDialog() {
        val field = EditText(this).apply {
            hint = "Ex. Gemma 3 1B, DeepSeek R1, Qwen 3…"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rechercher des GGUF")
            .setMessage("Recherche publique sur Hugging Face. PocketAI retient uniquement les fichiers uniques disposant d’une empreinte SHA-256 vérifiable.")
            .setView(wrapper)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Rechercher") { _, _ ->
                val query = field.text?.toString()?.trim().orEmpty()
                if (query.length >= 2) model.searchHuggingFace(query)
                else toast("Saisis au moins deux caractères.")
            }
            .show()
    }

    private fun renderCreation() {
        creationPanel.removeAllViews(); pad(creationPanel)
        creationPanel.addView(text("Créer et télécharger", 22f, true))
        creationPanel.addView(text("Documents et code : modèle de chat sélectionné, local ou serveur. Vision, Whisper, Kokoro et Qwen Image : serveur à configurer, avec ses conditions et tarifs.", 14f))
        creationPanel.addView(button("Créer un fichier avec le modèle sélectionné") { createFileDialog() }.apply { isEnabled = !model.state.value.busy })
        creationPanel.addView(button("Transcrire un audio · Whisper") {
            hubAction("whisper-small") { audioPicker.launch(arrayOf("audio/*")) }
        }.apply { isEnabled = !model.state.value.busy })
        creationPanel.addView(button("Créer une voix · Kokoro") {
            hubAction("kokoro") { promptDialog("Voix Kokoro", "Le texte est envoyé au serveur Kokoro (4 000 caractères maximum).", true, model::synthesizeSpeech) }
        }.apply { isEnabled = !model.state.value.busy })
        creationPanel.addView(button("Créer une image · Qwen Image") {
            hubAction("qwen-image") { promptDialog("Qwen Image", "La description est envoyée au serveur diffusion configuré.", true) { model.generateHubImage(it) } }
        }.apply { isEnabled = !model.state.value.busy })
        creationPanel.addView(button("Modifier la photo jointe · Qwen Image") {
            hubAction("qwen-image") { promptDialog("Modifier la photo", "Joins d’abord une photo dans le chat. La photo réduite et les instructions sont envoyées au serveur ; celui-ci doit prendre en charge /images/edits.", true) { model.generateHubImage(it, edit = true) } }
        }.apply { isEnabled = !model.state.value.busy })
        creationPanel.addView(button("Générer une image") { promptDialog("Image en ligne", "Décris l’image à générer. La demande est envoyée au fournisseur configuré.", model.settings.hasImageKey, model::generateImage) }.apply { isEnabled = !model.state.value.busy })
        creationPanel.addView(button("Générer une vidéo") { promptDialog("Vidéo en ligne", "Décris une courte vidéo. La demande est envoyée à fal.ai ; les délais et le coût dépendent du fournisseur.", model.settings.hasFalKey, model::generateVideo) }.apply { isEnabled = !model.state.value.busy })
        creationPanel.addView(text("Fichiers prêts", 18f, true))
        val outputs = model.state.value.artifacts
        if (outputs.isEmpty()) creationPanel.addView(text("Les fichiers créés apparaîtront ici. Tu peux aussi enregistrer chaque réponse du chat en PDF, Markdown ou texte.", 14f))
        outputs.sortedByDescending { it.file.lastModified() }.forEach { artifact ->
            val contents = column()
            contents.addView(text(artifact.displayName, 16f, true))
            contents.addView(text("${artifact.mimeType} · ${artifact.file.length() / 1024} Ko", 12f))
            val fileActions = row()
            fileActions.addView(button("Ouvrir") { openArtifact(artifact) })
            fileActions.addView(button("Enregistrer") { saveArtifact(artifact) })
            val manageActions = row()
            manageActions.addView(button("Partager") { shareArtifact(artifact) })
            manageActions.addView(button("Supprimer") {
                MaterialAlertDialogBuilder(this).setTitle("Supprimer ce fichier ?")
                    .setMessage("Enregistre-le d’abord si tu souhaites le conserver.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Supprimer") { _, _ -> model.deleteArtifact(artifact) }.show()
            }.apply { isEnabled = !model.state.value.busy })
            listOf(fileActions, manageActions).forEach { actions ->
                for (index in 0 until actions.childCount) {
                    actions.getChildAt(index).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index == 0) rightMargin = dp(4) else leftMargin = dp(4)
                    }
                }
                contents.addView(actions)
            }
            creationPanel.addView(card(contents))
        }
    }

    private fun renderSettings() {
        settingsPanel.removeAllViews(); pad(settingsPanel)
        settingsPanel.addView(text("Ton PocketAI", 22f, true))
        settingsPanel.addView(text("Serveurs des modèles", 18f, true))
        settingsPanel.addView(button("Configurer un modèle du catalogue") { chooseHubConfiguration() }.apply { isEnabled = !model.state.value.busy })
        settingsPanel.addView(text("Chaque modèle a son URL HTTPS et son alias serveur. Il doit être déployé côté serveur ; une fiche Hugging Face n’est pas un endpoint d’inférence. Les clés restent chiffrées sur le téléphone.", 13f))
        settingsPanel.addView(SwitchMaterial(this).apply {
            text = "Recherche sémantique · serveur Qwen Embedding"
            setTextColor(Color.parseColor("#D6E2EA"))
            isChecked = model.settings.embeddingEnabled
            isEnabled = !model.state.value.busy
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    isChecked = false
                    hubAction("qwen3-embedding") {
                        MaterialAlertDialogBuilder(this@MainActivity).setTitle("Activer les embeddings ?")
                            .setMessage("Les passages de chaque document joint et tes questions seront envoyés au serveur d’embeddings, y compris avec un chat local. La recherche est limitée à 64 passages répartis dans le document.")
                            .setNegativeButton("Annuler", null).setPositiveButton("Activer") { _, _ ->
                                model.settings.embeddingEnabled = true; renderSettings()
                            }.show()
                    }
                } else model.settings.embeddingEnabled = false
            }
        })
        settingsPanel.addView(button("Voix Kokoro : ${model.settings.speechVoice}") {
            MaterialAlertDialogBuilder(this).setTitle("Voix française du serveur")
                .setItems(arrayOf("ff_siwis · féminin", "fm_gilles · masculin")) { _, index ->
                    model.settings.speechVoice = if (index == 0) "ff_siwis" else "fm_gilles"; renderSettings()
                }.show()
        }.apply { isEnabled = !model.state.value.busy })
        settingsPanel.addView(text("Performances", 18f, true))
        val modeLabels = arrayOf("Auto · adaptatif", "CPU · performance", "CPU · équilibré", "Autonomie", "Vulkan · expérimental")
        val modes = arrayOf("auto", "cpu-performance", "balanced", "eco", "performance")
        val selectedMode = modes.indexOf(model.performanceMode).coerceAtLeast(0)
        settingsPanel.addView(button("Profil : ${modeLabels[selectedMode]}") {
            MaterialAlertDialogBuilder(this).setTitle("Profil matériel")
                .setSingleChoiceItems(modeLabels, selectedMode) { dialog, index ->
                    dialog.dismiss()
                    if (modes[index] == "performance") {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Vulkan expérimental")
                            .setMessage("Vulkan a déjà produit des sorties corrompues sur certains pilotes Adreno. PocketAI ne le reteste qu’après une action explicite et repasse automatiquement en CPU performance si une corruption est détectée.")
                            .setNegativeButton("Annuler", null)
                            .setPositiveButton("Tester Vulkan une fois") { _, _ ->
                                model.performanceMode = "performance"
                                renderSettings()
                                toast("Vulkan sera essayé au prochain chargement du modèle")
                            }.show()
                    } else {
                        model.performanceMode = modes[index]
                        renderSettings()
                        toast("Profil appliqué au prochain chargement du modèle")
                    }
                }.setNegativeButton("Fermer", null).show()
        })
        settingsPanel.addView(text("Auto adapte CPU, batch et contexte à la taille du modèle et à l’état du téléphone. CPU · performance pousse davantage les cœurs pour les gros modèles. Vulkan reste expérimental et n’est retenté qu’après une action explicite. La chauffe peut toujours réduire les threads.", 14f))
        val lengthLabel = if (model.autoLength) {
            "Auto · jusqu’à ${model.effectiveMaxTokens()} tokens"
        } else {
            "${model.maxTokens} tokens"
        }
        settingsPanel.addView(button("Longueur : $lengthLabel") {
            val labels = arrayOf("Auto · recommandé", "256 tokens", "512 tokens", "1024 tokens", "2048 tokens", "4096 tokens", "8192 tokens")
            val values = intArrayOf(0, 256, 512, 1024, 2048, 4096, 8192)
            MaterialAlertDialogBuilder(this).setTitle("Longueur des réponses")
                .setItems(labels) { _, index ->
                    val selected = values[index]
                    if (selected == 0) {
                        model.autoLength = true
                        renderSettings()
                    } else if (selected >= 4096) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Réponse potentiellement très longue")
                            .setMessage("$selected tokens peuvent représenter plusieurs minutes sur un modèle 4B local et augmenter fortement la chauffe. Le mode Auto est recommandé.")
                            .setNegativeButton("Garder Auto") { _, _ -> model.autoLength = true; renderSettings() }
                            .setPositiveButton("Utiliser $selected") { _, _ ->
                                model.maxTokens = selected
                                model.autoLength = false
                                renderSettings()
                            }.show()
                    } else {
                        model.maxTokens = selected
                        model.autoLength = false
                        renderSettings()
                    }
                }.show()
        })
        settingsPanel.addView(text("En mode Auto, PocketAI adapte la longueur au contexte, au profil et à la pression thermique pour éviter les générations de plusieurs minutes. Une valeur manuelle reste disponible pour les réponses volontairement très longues.", 13f))
        settingsPanel.addView(text("Lecture vocale locale", 18f, true))
        settingsPanel.addView(SwitchMaterial(this).apply {
            text = "Lire automatiquement les réponses terminées"
            isChecked = ttsAutoRead()
            setTextColor(Color.parseColor("#D6E2EA"))
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("pocketai", 0).edit().putBoolean("tts_auto_read", checked).apply()
            }
        })
        settingsPanel.addView(button("Vitesse de lecture : ${"%.2f".format(ttsRate())}×") {
            val rates = floatArrayOf(0.8f, 1.0f, 1.15f, 1.3f, 1.45f)
            val labels = rates.map { "${"%.2f".format(it)}×" }.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Vitesse de la voix")
                .setItems(labels) { _, index ->
                    getSharedPreferences("pocketai", 0).edit().putFloat("tts_rate", rates[index]).apply()
                    tts?.setSpeechRate(rates[index])
                    renderSettings()
                }.show()
        })
        settingsPanel.addView(button("Arrêter la lecture vocale") {
            tts?.stop()
        })
        settingsPanel.addView(text("PocketAI utilise en priorité une voix Android disponible hors ligne dans la langue du téléphone. Les longues réponses sont lues par morceaux pour éviter les limites du moteur TTS.", 13f))

        settingsPanel.addView(text("Services en ligne facultatifs", 18f, true))
        settingsPanel.addView(text("Aucun envoi en ligne sans action explicite. La recherche transmet la question à Brave. Les générations d’image/vidéo transmettent leur description au fournisseur. Les clés sont chiffrées sur ce téléphone et exclues des sauvegardes.", 14f))
        settingsPanel.addView(button("Recherche web · ${if (model.settings.hasBraveKey) "configurée" else "à configurer"}") { onlineDialog("web") })
        settingsPanel.addView(button("Images · ${if (model.settings.hasImageKey) "configurées" else "à configurer"}") { onlineDialog("image") })
        settingsPanel.addView(button("Vidéos · ${if (model.settings.hasFalKey) "configurées" else "à configurer"}") { onlineDialog("video") })
        settingsPanel.addView(text("Diagnostics", 18f, true))
        if (model.gpuBlacklistCount > 0) {
            settingsPanel.addView(text("Vulkan désactivé automatiquement pour ${model.gpuBlacklistCount} combinaison(s) appareil/modèle après corruption détectée.", 13f))
            settingsPanel.addView(button("Réautoriser Vulkan pour les modèles bloqués") {
                val count = model.clearGpuBlacklist()
                toast("$count blocage(s) Vulkan effacé(s). Sélectionne ensuite Vulkan expérimental pour un nouveau test.")
                renderSettings()
            })
        }
        settingsPanel.addView(button("Voir le matériel et le moteur") {
            MaterialAlertDialogBuilder(this).setTitle("Diagnostic matériel")
                .setMessage(HardwareProfile.detect(this).summary + "\n\n" + model.state.value.diagnostics.ifBlank { "Charge un modèle pour confirmer le moteur utilisé." })
                .setPositiveButton("Fermer", null).show()
        })
        settingsPanel.addView(button("Benchmark CPU / modèle chargé") {
            model.benchmarkActiveModel()
        }.apply { isEnabled = model.state.value.modelName != null && !model.state.value.busy })
        settingsPanel.addView(button("Auto-régler les threads CPU") {
            model.autoTuneThreads()
        }.apply { isEnabled = model.state.value.modelName != null && !model.state.value.busy })
        settingsPanel.addView(button("Exporter les logs de débogage") {
            if (pendingSave == null && !exportInProgress) lifecycleScope.launch {
                exportInProgress = true
                try { saveArtifact(withContext(Dispatchers.IO) { model.exportLogs() }) }
                catch (e: Exception) { showError(e.message ?: "Export impossible") }
                finally { exportInProgress = false }
            } else toast("Termine l’enregistrement en cours.")
        })
        settingsPanel.addView(text("Les logs contiennent le matériel, les réglages et les erreurs techniques. Le texte de tes conversations et les clés API ne sont pas journalisés.", 13f))
    }

    private fun onlineDialog(kind: String) {
        val contents = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val key = EditText(this).apply {
            hint = "Nouvelle clé API (vide : conserver)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
        }
        val endpoint = EditText(this).apply { hint = "URL de base HTTPS"; isSingleLine = true; setText(model.settings.imageBaseUrl) }
        val providerModel = EditText(this).apply { hint = "Modèle du fournisseur"; isSingleLine = true }
        contents.addView(text(when (kind) {
            "web" -> "Clé Brave Search API. La question est envoyée uniquement quand le bouton Web du chat est activé."
            "image" -> "API images compatible OpenAI. Le tarif dépend du fournisseur et du modèle."
            else -> "API fal.ai (Wan vidéo). Génération courte en 480p ; facturation selon ton compte."
        }, 14f))
        if (kind == "image") { contents.addView(endpoint); providerModel.setText(model.settings.imageModel); contents.addView(providerModel) }
        if (kind == "video") { providerModel.setText(model.settings.videoModel); contents.addView(providerModel) }
        contents.addView(key)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(when(kind) { "web" -> "Recherche web"; "image" -> "Génération d’images"; else -> "Génération de vidéos" })
            .setView(contents).setNegativeButton("Annuler", null).setNeutralButton("Effacer la clé", null).setPositiveButton("Enregistrer", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val value = key.text.toString().trim()
                    when (kind) {
                        "web" -> if (value.isNotEmpty()) model.settings.braveApiKey = value
                        "image" -> {
                            model.settings.imageBaseUrl = endpoint.text.toString().trim()
                            model.settings.imageModel = providerModel.text.toString().trim()
                            if (value.isNotEmpty()) model.settings.imageApiKey = value
                        }
                        else -> {
                            model.settings.videoModel = providerModel.text.toString().trim()
                            if (value.isNotEmpty()) model.settings.falApiKey = value
                        }
                    }
                    key.text.clear(); dialog.dismiss(); renderSettings()
                } catch (e: Exception) { showError(e.message ?: "Configuration invalide") }
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                when(kind) { "web" -> { model.settings.braveApiKey = ""; model.settings.webSearchEnabled = false; webToggle.isChecked = false }; "image" -> model.settings.imageApiKey = ""; else -> model.settings.falApiKey = "" }
                key.text.clear(); dialog.dismiss(); renderSettings()
            }
        }
        dialog.show()
    }

    private fun createFileDialog() {
        val contents = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val filename = EditText(this).apply { hint = "Nom du fichier"; setText("document.md"); isSingleLine = true }
        val prompt = EditText(this).apply { hint = "Que doit contenir ce fichier ?"; minLines = 3; gravity = Gravity.TOP }
        contents.addView(filename); contents.addView(prompt)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Créer un fichier · ${if (model.state.value.remote) "serveur" else "local"}")
            .setView(contents).setNegativeButton("Annuler", null).setPositiveButton("Créer", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requestedName = filename.text.toString().trim()
                val description = prompt.text.toString().trim()
                filename.error = null
                prompt.error = null
                if (requestedName.isBlank()) {
                    filename.error = "Donne un nom au fichier."
                    filename.requestFocus()
                    return@setOnClickListener
                }
                if (description.isBlank()) {
                    prompt.error = "Décris le contenu du fichier."
                    prompt.requestFocus()
                    return@setOnClickListener
                }
                if (model.state.value.modelName == null) {
                    prompt.error = "Charge d’abord un modèle dans l’onglet Modèles."
                    return@setOnClickListener
                }
                if (model.state.value.busy) {
                    prompt.error = "Attends la fin de l’action en cours."
                    return@setOnClickListener
                }
                val name = ArtifactStore.safeFileName(requestedName)
                val mime = when(name.substringAfterLast('.').lowercase()) { "md" -> "text/markdown"; "html" -> "text/html"; "json" -> "application/json"; "csv" -> "text/csv"; "pdf" -> "application/pdf"; else -> "text/plain" }
                model.send(description, name, mime)
                dialog.dismiss()
                tabs.getTabAt(0)?.select()
            }
        }
        dialog.show()
    }

    private fun promptDialog(title: String, description: String, configured: Boolean, action: (String) -> Unit) {
        if (!configured) { toast("Configure d’abord le service dans Réglages."); tabs.getTabAt(3)?.select(); return }
        val contents = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val prompt = EditText(this).apply { hint = "Description"; minLines = 3; gravity = Gravity.TOP }
        contents.addView(text(description, 14f)); contents.addView(prompt)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(contents).setNegativeButton("Annuler", null)
            .setPositiveButton("Générer", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val description = prompt.text.toString().trim()
                prompt.error = null
                if (description.isBlank()) {
                    prompt.error = "Ajoute une description avant de générer."
                    prompt.requestFocus()
                    return@setOnClickListener
                }
                if (model.state.value.busy) {
                    prompt.error = "Attends la fin de l’action en cours."
                    return@setOnClickListener
                }
                action(description)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun chooseExport(message: ChatMessage) {
        val files = ResponseText.extractFiles(message.content)
        val formats = ExportFormat.entries
        val names = formats.map { "Réponse en ${it.label}" } + files.map { "Fichier ${it.name}" }
        if (pendingSave != null || exportInProgress) { toast("Termine l’enregistrement en cours."); return }
        MaterialAlertDialogBuilder(this).setTitle("Enregistrer la réponse").setItems(names.toTypedArray()) { _, index ->
            if (pendingSave == null && !exportInProgress) lifecycleScope.launch {
                exportInProgress = true
                try {
                    val artifact = withContext(Dispatchers.IO) {
                        if (index < formats.size) model.exportMessage(message, formats[index])
                        else model.exportGeneratedFile(files[index - formats.size])
                    }
                    saveArtifact(artifact)
                } catch (e: Exception) { showError(e.message ?: "Export impossible") }
                finally { exportInProgress = false }
            }
        }.show()
    }

    private fun saveArtifact(artifact: GeneratedArtifact) {
        if (pendingSave != null) { toast("Termine l’enregistrement en cours."); return }
        pendingSave = artifact
        try { savePicker.launch(artifact.displayName) }
        catch (error: Exception) { pendingSave = null; showError("Le sélecteur de fichiers est indisponible.") }
    }

    private fun openArtifact(artifact: GeneratedArtifact) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", artifact.file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, artifact.mimeType)
                clipData = ClipData.newRawUri(artifact.displayName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            showError("Aucune application disponible pour ouvrir ce fichier. Tu peux l’enregistrer ou le partager.")
        }
    }

    private fun shareArtifact(artifact: GeneratedArtifact) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", artifact.file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = artifact.mimeType; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(artifact.displayName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Partager ${artifact.displayName}"))
        } catch (e: Exception) { showError("Aucune application disponible pour partager ce fichier.") }
    }

    private fun showTab() {
        listOf(chat, modelsPanel.parent as View, creationPanel.parent as View, settingsPanel.parent as View).forEachIndexed { index, view ->
            view.visibility = if (index == selectedTab) View.VISIBLE else View.GONE
        }
        if (selectedTab == 1) renderModels()
        if (selectedTab == 2) renderCreation()
        if (selectedTab == 3) renderSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", selectedTab)
        outState.putString("draft", input.text?.toString().orEmpty())
        pendingSave?.let { outState.putString("pendingPath", it.file.absolutePath); outState.putString("pendingMime", it.mimeType); outState.putString("pendingName", it.displayName) }
        pendingCameraFile?.let { outState.putString("pendingCameraPath", it.absolutePath) }
    }

    private fun startCameraCapture() {
        try {
            val directory = File(cacheDir, "exports").apply { mkdirs() }
            directory.listFiles().orEmpty()
                .filter { it.name.startsWith("camera-") && System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }
                .forEach { it.delete() }
            val file = File.createTempFile("camera-", ".jpg", directory)
            pendingCameraFile = file
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            cameraPicker.launch(uri)
        } catch (_: Exception) {
            pendingCameraFile = null
            showError("Impossible d’ouvrir l’appareil photo.")
        }
    }

    private fun speakMessage(message: ChatMessage) {
        val plain = ResponseText.visible(message.content)
            .replace(Regex("(?m)^[#>*+\\-]+\\s*"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_~`]{1,3}"), "")
            .trim()
        if (plain.isBlank()) return
        if (ttsReady) {
            speakText(plain)
            return
        }
        pendingSpeech = plain
        if (tts != null) {
            toast("Préparation de la voix…")
            return
        }
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                pendingSpeech = null
                tts?.shutdown()
                tts = null
                showError("La synthèse vocale Android n’est pas disponible sur cet appareil.")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val preferred = Locale.getDefault()
            val result = engine.setLanguage(preferred)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.FRANCE)
            }
            val targetLanguage = engine.voice?.locale?.language ?: preferred.language
            engine.voices.orEmpty()
                .filter { !it.isNetworkConnectionRequired && it.locale.language == targetLanguage }
                .maxWithOrNull(
                    compareBy<android.speech.tts.Voice> { it.quality }
                        .thenByDescending { -it.latency }
                )
                ?.let { engine.voice = it }
            engine.setSpeechRate(ttsRate())
            ttsReady = true
            pendingSpeech?.also {
                pendingSpeech = null
                speakText(it)
            }
        }
    }

    private fun ttsRate(): Float =
        getSharedPreferences("pocketai", 0).getFloat("tts_rate", 1.0f).coerceIn(0.7f, 1.5f)

    private fun ttsAutoRead(): Boolean =
        getSharedPreferences("pocketai", 0).getBoolean("tts_auto_read", false)

    private fun speakText(text: String) {
        val engine = tts ?: return
        engine.stop()
        engine.setSpeechRate(ttsRate())
        val max = (TextToSpeech.getMaxSpeechInputLength() - 256).coerceAtLeast(1000)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > max) {
            val window = remaining.take(max)
            val split = maxOf(
                window.lastIndexOf(". "),
                window.lastIndexOf("! "),
                window.lastIndexOf("? "),
                window.lastIndexOf("\n"),
                window.lastIndexOf(" "),
            ).takeIf { it >= max / 2 } ?: max
            chunks += remaining.substring(0, split + if (split < remaining.length && remaining.getOrNull(split) != ' ') 0 else 1).trim()
            remaining = remaining.substring((split + 1).coerceAtMost(remaining.length)).trimStart()
        }
        if (remaining.isNotBlank()) chunks += remaining
        chunks.forEachIndexed { index, chunk ->
            engine.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "pocketai-${System.nanoTime()}-$index",
            )
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun showError(message: String) { MaterialAlertDialogBuilder(this).setTitle("PocketAI").setMessage(message).setPositiveButton("Compris", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun openLink(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { showError("Aucun navigateur disponible.") } }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = ViewGroup.LayoutParams(-1, -2) }
    private fun pad(view: View) = view.setPadding(dp(16), dp(12), dp(16), dp(24))
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.parseColor("#D6E2EA")); setPadding(0, dp(8), 0, dp(8))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; textSize = 13f; minHeight = dp(48); setOnClickListener { action() }
    }
    private fun card(contents: LinearLayout) = MaterialCardView(this).apply {
        radius = dp(16).toFloat(); setCardBackgroundColor(Color.parseColor("#18212A"))
        strokeColor = Color.parseColor("#2C3A47"); strokeWidth = dp(1)
        contents.setPadding(dp(14), dp(8), dp(14), dp(8)); addView(contents)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
