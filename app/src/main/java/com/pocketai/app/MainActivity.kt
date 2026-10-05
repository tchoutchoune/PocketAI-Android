package com.pocketai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var model: ChatViewModel
    private lateinit var status: TextView
    private lateinit var modelBadge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var messageList: RecyclerView
    private lateinit var sendButton: MaterialButton
    private lateinit var input: TextInputEditText
    private lateinit var webToggle: SwitchMaterial
    private lateinit var chat: LinearLayout
    private lateinit var modelsPanel: LinearLayout
    private lateinit var creationPanel: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var tabs: TabLayout
    private val shownMessages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var pendingSave: GeneratedArtifact? = null
    private var lastModelsKey: Pair<Boolean, String?>? = null
    private var lastCreationKey: Pair<Boolean, Int>? = null
    private var exportInProgress = false
    private lateinit var stopButton: MaterialButton
    private lateinit var newButton: MaterialButton
    private var selectedTab = 0

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importModel)
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
                    modelBadge.text = state.modelName?.let { "Local · $it" } ?: "Choisis un modèle dans l’onglet Modèles"
                    progress.visibility = if (state.busy) View.VISIBLE else View.GONE
                    stopButton.visibility = if (state.busy) View.VISIBLE else View.GONE
                    newButton.isEnabled = !state.busy
                    sendButton.text = if (state.busy) "Arrêter" else "Envoyer"
                    input.isEnabled = !state.busy
                    webToggle.isEnabled = !state.busy
                    val wasAtBottom = !messageList.canScrollVertically(1)
                    val oldCount = shownMessages.size
                    if (shownMessages != state.messages) {
                        val changed = shownMessages.size == state.messages.size && shownMessages.dropLast(1) == state.messages.dropLast(1)
                        shownMessages.clear(); shownMessages.addAll(state.messages)
                        if (changed && shownMessages.isNotEmpty()) adapter.notifyItemChanged(shownMessages.lastIndex)
                        else adapter.notifyDataSetChanged()
                        if (wasAtBottom || state.messages.size > oldCount) messageList.scrollToPosition((shownMessages.size - 1).coerceAtLeast(0))
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
        adapter = ChatAdapter(this, shownMessages, ::chooseExport) { message ->
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("PocketAI", (if (message.isUser) message.content else ResponseText.visible(message.content))))
            toast("Réponse copiée")
        }
        messageList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = this@MainActivity.adapter
            setPadding(dp(4), dp(8), dp(4), dp(8))
            clipToPadding = false
            itemAnimator = null
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
        modelsPanel.addView(text("Commence par 0,5B pour la rapidité. Un modèle plus grand demande davantage de mémoire. Les fichiers restent sur ton téléphone.", 14f))
        modelsPanel.addView(button("Importer un fichier GGUF") { importPicker.launch(arrayOf("*/*")) }.apply { isEnabled = !model.state.value.busy })
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
        modelsPanel.addView(text("Catalogue vérifié", 18f, true))
        ModelRepository.catalogue.forEach { entry ->
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

    private fun renderCreation() {
        creationPanel.removeAllViews(); pad(creationPanel)
        creationPanel.addView(text("Créer et télécharger", 22f, true))
        creationPanel.addView(text("Documents et code : modèle local. Images et vidéos : services en ligne facultatifs, avec tes clés et leurs tarifs.", 14f))
        creationPanel.addView(button("Créer un fichier avec le modèle local") { createFileDialog() }.apply { isEnabled = !model.state.value.busy })
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
        settingsPanel.addView(text("Performances", 18f, true))
        val modeLabels = arrayOf("Automatique · équilibré", "Performances", "Autonomie", "CPU uniquement")
        val modes = arrayOf("balanced", "performance", "eco", "cpu")
        settingsPanel.addView(button("Profil : ${modeLabels[modes.indexOf(model.performanceMode).coerceAtLeast(0)]}") {
            MaterialAlertDialogBuilder(this).setTitle("Profil matériel")
                .setSingleChoiceItems(modeLabels, modes.indexOf(model.performanceMode).coerceAtLeast(0)) { dialog, index ->
                    model.performanceMode = modes[index]; dialog.dismiss(); renderSettings()
                    toast("Profil appliqué au prochain chargement du modèle")
                }.setNegativeButton("Fermer", null).show()
        })
        settingsPanel.addView(text("PocketAI adapte le contexte à la RAM, cherche automatiquement le meilleur nombre de couches Vulkan et calibre les threads CPU sur le modèle chargé. Si un backend GPU échoue réellement, l’application mémorise automatiquement un plafond sûr pour ce modèle et cet appareil. Le mode Performances permet de forcer un nouveau test.", 14f))
        settingsPanel.addView(button("Réinitialiser l’adaptation GPU") {
            model.clearBackendLearning()
            toast("Le prochain chargement retestera le GPU en mode automatique.")
            renderSettings()
        })
        settingsPanel.addView(button("Longueur maximale : ${model.maxTokens} tokens") {
            val values = intArrayOf(256, 512, 1024, 2048, 4096, 8192)
            MaterialAlertDialogBuilder(this).setTitle("Longueur des réponses")
                .setItems(values.map { "$it tokens" }.toTypedArray()) { _, index -> model.maxTokens = values[index]; renderSettings() }.show()
        })
        settingsPanel.addView(text("Services en ligne facultatifs", 18f, true))
        settingsPanel.addView(text("Aucun envoi en ligne sans action explicite. La recherche transmet la question à Brave. Les générations d’image/vidéo transmettent leur description au fournisseur. Les clés sont chiffrées sur ce téléphone et exclues des sauvegardes.", 14f))
        settingsPanel.addView(button("Recherche web · ${if (model.settings.hasBraveKey) "configurée" else "à configurer"}") { onlineDialog("web") })
        settingsPanel.addView(button("Images · ${if (model.settings.hasImageKey) "configurées" else "à configurer"}") { onlineDialog("image") })
        settingsPanel.addView(button("Vidéos · ${if (model.settings.hasFalKey) "configurées" else "à configurer"}") { onlineDialog("video") })
        settingsPanel.addView(text("Diagnostics", 18f, true))
        settingsPanel.addView(button("Performances en direct") { showPerformanceDialog() })
        settingsPanel.addView(button("Voir le matériel et le moteur") {
            MaterialAlertDialogBuilder(this).setTitle("Diagnostic matériel")
                .setMessage(HardwareProfile.detect(this).summary + "\n\n" + model.state.value.diagnostics.ifBlank { "Charge un modèle pour confirmer le moteur utilisé." })
                .setPositiveButton("Fermer", null).show()
        })
        if (model.state.value.modelName != null) {
            settingsPanel.addView(button("Recalibrer les threads CPU") { model.retunePerformance() }
                .apply { isEnabled = !model.state.value.busy })
            settingsPanel.addView(text("Le recalibrage compare plusieurs nombres de threads sur le modèle et le backend actuellement chargés. Le résultat est mémorisé pour les prochains chargements.", 13f))
        }
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

    private fun showPerformanceDialog() {
        val body = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#D6E2EA"))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Performances PocketAI")
            .setView(scroll)
            .setPositiveButton("Fermer", null)
            .create()
        dialog.setOnShowListener {
            lifecycleScope.launch {
                while (dialog.isShowing) {
                    val report = withContext(Dispatchers.Default) { model.performanceReport() }
                    if (dialog.isShowing) body.text = report
                    delay(1000)
                }
            }
        }
        dialog.show()
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
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Créer un fichier local")
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
