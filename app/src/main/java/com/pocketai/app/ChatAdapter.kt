package com.pocketai.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration

class ChatAdapter(
    private val context: Context,
    private val messages: MutableList<ChatMessage>,
    private val onExport: (ChatMessage) -> Unit,
    private val onCopy: (ChatMessage) -> Unit,
    private val onSpeak: (ChatMessage) -> Unit,
) : RecyclerView.Adapter<ChatAdapter.MessageHolder>() {
    init { setHasStableIds(true) }

    private val markdown = Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.linkResolver { view, destination ->
                val uri = Uri.parse(destination)
                if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    Toast.makeText(view.context, "Ce lien ne correspond pas à une page web.", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        Toast.makeText(view.context, "Aucun navigateur disponible.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }).build()

    override fun getItemCount(): Int = messages.size
    override fun getItemId(position: Int): Long = messages[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        val root = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val card = MaterialCardView(context).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
        }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        card.addView(content)
        val heading = TextView(context).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#B7C6DE"))
        }
        val body = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#F1F4FA"))
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(6))
            setLinkTextColor(Color.parseColor("#A4C8FF"))
        }
        val progress = TextView(context).apply {
            textSize = 12f
            text = "Réponse en cours…"
            setTextColor(Color.parseColor("#B7C6DE"))
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val copy = action("Copier")
        val speak = action("Lire")
        val export = action("Enregistrer")
        val share = action("Partager")
        actions.addView(copy, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(speak, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(export, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(share, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(heading)
        content.addView(body)
        content.addView(progress)
        content.addView(actions)
        return MessageHolder(root, card, heading, body, progress, actions, copy, speak, export, share)
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        bindFull(holder, messages[position])
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int, payloads: MutableList<Any>) {
        val message = messages[position]
        if (payloads.contains(PAYLOAD_STREAM) && !message.isUser && message.isStreaming) {
            bindStreamingText(holder, message)
        } else {
            bindFull(holder, message)
        }
    }

    private fun bindStreamingText(holder: MessageHolder, message: ChatMessage) {
        val visible = ResponseText.visible(message.content)
        holder.body.setTextIsSelectable(false)
        holder.body.movementMethod = null
        holder.body.text = visible.ifBlank { "Préparation de la réponse…" }
        holder.progress.visibility = View.VISIBLE
        holder.actions.visibility = View.GONE
    }

    private fun bindFull(holder: MessageHolder, message: ChatMessage) {
        val visible = if (message.isUser) message.content else ResponseText.visible(message.content)
        val layout = holder.card.layoutParams as FrameLayout.LayoutParams
        layout.marginStart = if (message.isUser) dp(36) else 0
        layout.marginEnd = if (message.isUser) 0 else dp(12)
        holder.card.layoutParams = layout
        holder.card.setCardBackgroundColor(Color.parseColor(if (message.isUser) "#21395E" else "#1B2433"))
        holder.card.strokeColor = Color.parseColor(if (message.isUser) "#375887" else "#303D50")
        holder.heading.text = if (message.isUser) "Vous" else "PocketAI"
        if (message.isUser) {
            holder.body.setTextIsSelectable(true)
            holder.body.movementMethod = null
            holder.body.text = visible
        } else if (message.isStreaming) {
            bindStreamingText(holder, message)
        } else {
            holder.body.setTextIsSelectable(false)
            holder.body.movementMethod = LinkMovementMethod.getInstance()
            markdown.setMarkdown(holder.body, visible.ifBlank { "Aucune réponse reçue." })
        }
        holder.progress.visibility = if (message.isStreaming) View.VISIBLE else View.GONE
        holder.actions.visibility = if (message.isStreaming || visible.isBlank()) View.GONE else View.VISIBLE
        holder.export.visibility = if (message.isUser) View.GONE else View.VISIBLE
        holder.speak.visibility = if (message.isUser) View.GONE else View.VISIBLE
        holder.copy.setOnClickListener { onCopy(message) }
        holder.speak.setOnClickListener { onSpeak(message) }
        holder.export.setOnClickListener { onExport(message) }
        holder.share.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, visible)
                putExtra(Intent.EXTRA_TITLE, "Réponse PocketAI")
            }
            try {
                context.startActivity(Intent.createChooser(shareIntent, "Partager la réponse"))
            } catch (_: Exception) {
                Toast.makeText(context, "Aucune application de partage disponible.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun action(label: String) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        insetTop = dp(2)
        insetBottom = dp(2)
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(Color.parseColor("#BBD5FC"))
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        strokeWidth = 0
        contentDescription = "$label le message"
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        const val PAYLOAD_STREAM = "stream"
    }

    class MessageHolder(
        view: View,
        val card: MaterialCardView,
        val heading: TextView,
        val body: TextView,
        val progress: TextView,
        val actions: LinearLayout,
        val copy: MaterialButton,
        val speak: MaterialButton,
        val export: MaterialButton,
        val share: MaterialButton
    ) : RecyclerView.ViewHolder(view)
}
