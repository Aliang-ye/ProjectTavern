package com.projecttavern.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.projecttavern.app.databinding.ActivityChatBinding
import kotlin.concurrent.thread

class ChatActivity : AppCompatActivity() {
    private lateinit var b: ActivityChatBinding
    private lateinit var convId: String
    private lateinit var adapter: MsgAdapter
    private var busy = false
    private var debugOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        convId = intent.getStringExtra("id") ?: return finish()
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)
        adapter = MsgAdapter()
        b.messages.layoutManager = LinearLayoutManager(this)
        b.messages.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnSend.setOnClickListener { if (busy) stop() else send() }
        b.btnDebug.setOnClickListener {
            debugOn = !debugOn
            b.debugBox.visibility = if (debugOn) View.VISIBLE else View.GONE
            if (debugOn) b.debugBox.text = Engine.build(convId).debug
        }
        b.btnSend.setOnLongClickListener {
            if (busy) stop()
            else if (b.etDraft.text.toString().isBlank()) {
                Toast.makeText(this, Store.t("writeAction"), Toast.LENGTH_SHORT).show()
            }
            true
        }
        if (Store.state.developerMode) {
            debugOn = true
            b.debugBox.visibility = View.VISIBLE
            b.debugBox.text = Engine.build(convId).debug
        }
        bindHeader()
        refresh()
    }

    private fun conv() = Store.state.conversations.find { it.id == convId }

    private fun bindHeader() {
        val c = conv() ?: return finish()
        val ch = Store.state.characters.find { it.id == c.characterId }
        b.headerTitle.text = ch?.name ?: c.title
        val contextText = if (c.storyId == null) Store.t("privateChat") else Store.state.stories.find { it.id == c.storyId }?.name ?: Store.t("stories")
        val live = if (busy) {
            if (Store.state.locale == "en") "Streaming" else "流式中"
        } else {
            if (Store.state.locale == "en") "Ready" else "就绪"
        }
        b.headerSub.text = "$contextText · $live"
        b.btnDebug.text = Store.t("debugger")
        b.etDraft.hint = Store.t("writeAction")
        paintSend()
        val presets = Store.localePresets()
        b.spPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.name })
        val idx = presets.indexOfFirst { it.id == c.presetId }
        if (idx >= 0) b.spPreset.setSelection(idx)
        b.spPreset.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                conv()?.presetId = presets.getOrNull(position)?.id ?: return
                Store.persist()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun paintSend() {
        b.btnSend.text = if (busy) "■" else Store.t("send").take(2)
        b.btnSend.setBackgroundResource(if (busy) R.drawable.bg_stop else R.drawable.bg_send)
    }

    private fun path(): List<ChatMessage> {
        val c = conv() ?: return emptyList()
        return Engine.visible(convId, c.tipMessageId)
    }

    private fun refresh() {
        adapter.items = path()
        adapter.notifyDataSetChanged()
        if (adapter.items.isNotEmpty()) b.messages.scrollToPosition(adapter.items.size - 1)
    }

    private fun send() {
        val text = b.etDraft.text.toString().trim()
        if (text.isEmpty() || busy) return
        val c = conv() ?: return
        val profile = Store.activeProfile()
        if (profile == null || profile.apiKey.isBlank()) {
            toast(Store.t("needProfile"))
            return
        }
        b.etDraft.setText("")
        val user = ChatMessage(Store.nid(), convId, c.tipMessageId, "user", text, mutableListOf(), 0, Store.now())
        Store.state.messages.add(user)
        val gen = Generation(Store.nid(), "", profile.model, profile.provider, Store.now())
        val asst = ChatMessage(Store.nid(), convId, user.id, "assistant", "", mutableListOf(gen), 0, Store.now())
        Store.state.messages.add(asst)
        c.tipMessageId = asst.id
        c.updatedAt = Store.now()
        Store.persist()
        refresh()
        generate(asst)
    }

    private fun generate(asst: ChatMessage) {
        val profile = Store.activeProfile() ?: return
        val preset = Store.state.presets.find { it.id == conv()?.presetId } ?: Store.localePresets().firstOrNull() ?: return
        busy = true
        paintSend()
        b.err.visibility = View.GONE
        thread {
            try {
                val built = Engine.build(convId)
                val full = Llm.stream(profile, built.messages, preset) { piece ->
                    if (!Store.state.streaming) return@stream
                    runOnUiThread {
                        val g = asst.generations[asst.generationIndex]
                        g.content += piece
                        asst.content = g.content
                        refresh()
                    }
                }
                runOnUiThread {
                    val g = asst.generations[asst.generationIndex]
                    if (full.isNotBlank()) g.content = full
                    asst.content = g.content
                    Store.persist()
                    busy = false
                    paintSend()
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    paintSend()
                    b.err.visibility = View.VISIBLE
                    b.err.text = e.message ?: "error"
                }
            }
        }
    }

    private fun stop() {
        Llm.cancel()
        busy = false
        paintSend()
    }

    private fun regenerate(m: ChatMessage) {
        val profile = Store.activeProfile()
        if (profile == null || profile.apiKey.isBlank()) {
            toast(Store.t("needProfile"))
            return
        }
        val gen = Generation(Store.nid(), "", profile.model, profile.provider, Store.now())
        m.generations.add(gen)
        m.generationIndex = m.generations.lastIndex
        m.content = ""
        conv()?.tipMessageId = m.id
        Store.persist()
        refresh()
        generate(m)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {
        var items: List<ChatMessage> = emptyList()
        inner class VH(val v: View) : RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            val ch = Store.state.characters.find { it.id == conv()?.characterId }
            val body = h.v.findViewById<TextView>(R.id.body)
            val user = h.v.findViewById<TextView>(R.id.userBody)
            val nameRow = h.v.findViewById<LinearLayout>(R.id.nameRow)
            val actions = h.v.findViewById<LinearLayout>(R.id.actions)
            actions.removeAllViews()
            if (m.role == "assistant") {
                nameRow.visibility = View.VISIBLE
                h.v.findViewById<TextView>(R.id.avatar).text = letter(ch?.name ?: "?")
                h.v.findViewById<TextView>(R.id.speaker).text = ch?.name ?: ""
                body.visibility = View.VISIBLE
                user.visibility = View.GONE
                body.text = Engine.display(m).ifBlank { if (busy && position == items.lastIndex) "…" else "" }
                val candle = getColor(R.color.candle)
                val muted = getColor(R.color.muted)
                val wine = getColor(R.color.wine)
                actions.addView(actionLabel(this@ChatActivity, Store.t("copy"), muted) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("t", Engine.display(m)))
                })
                actions.addView(actionLabel(this@ChatActivity, Store.t("regenerate"), candle) { regenerate(m) })
                if (m.generations.size > 1) {
                    actions.addView(actionLabel(this@ChatActivity, "${m.generationIndex + 1}/${m.generations.size}", muted) {
                        m.generationIndex = (m.generationIndex + 1) % m.generations.size
                        m.content = Engine.display(m)
                        Store.persist()
                        refresh()
                    })
                }
                actions.addView(actionLabel(this@ChatActivity, Store.t("delete"), wine) {
                    Store.state.messages.removeAll { it.id == m.id }
                    if (conv()?.tipMessageId == m.id) conv()?.tipMessageId = m.parentId
                    Store.persist()
                    refresh()
                })
            } else {
                nameRow.visibility = View.GONE
                body.visibility = View.GONE
                user.visibility = View.VISIBLE
                user.text = m.content
            }
        }
    }
}
