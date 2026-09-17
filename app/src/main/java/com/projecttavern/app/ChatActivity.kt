package com.projecttavern.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
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
        val layoutManager = LinearLayoutManager(this)
        b.messages.layoutManager = layoutManager
        b.messages.adapter = adapter
        b.messages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lastPos = layoutManager.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (total > 0 && lastPos < total - 2) {
                    b.btnScrollBottom.visibility = View.VISIBLE
                } else {
                    b.btnScrollBottom.visibility = View.GONE
                }
            }
        })
        b.btnScrollBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                b.messages.smoothScrollToPosition(adapter.itemCount - 1)
            }
        }
        b.messages.setOnTouchListener { _, _ -> hideKeyboard(); false }
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
        val world = if (c.characterId.isNullOrBlank() && c.worldBookIds.isNotEmpty()) {
            Store.state.worldBooks.find { it.id in c.worldBookIds }
        } else null
        val headerName = ch?.name ?: if (world != null) {
            "${world.name} · ${world.willName.ifBlank { Store.t("worldWill") }}"
        } else c.title
        b.headerTitle.text = headerName
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

    override fun onDestroy() {
        super.onDestroy()
        Llm.cancel()
    }

    private fun paintSend() {
        b.btnSend.text = if (busy) "■" else Store.t("send").take(2)
        b.btnSend.setBackgroundResource(if (busy) R.drawable.bg_stop else R.drawable.bg_send)
        val c = conv() ?: return
        val contextText = if (c.storyId == null) Store.t("privateChat") else Store.state.stories.find { it.id == c.storyId }?.name ?: Store.t("stories")
        val live = if (busy) {
            if (Store.state.locale == "en") "Streaming" else "流式中"
        } else {
            if (Store.state.locale == "en") "Ready" else "就绪"
        }
        b.headerSub.text = "$contextText · $live"
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
        generate(asst, user.id)
    }

    private fun generate(asst: ChatMessage, fallbackTipId: String? = null) {
        val profile = Store.activeProfile()
        if (profile == null || profile.apiKey.isBlank()) {
            busy = false
            paintSend()
            toast(Store.t("needProfile"))
            return
        }
        val preset = Store.state.presets.find { it.id == conv()?.presetId }
            ?: Store.localePresets().firstOrNull()
            ?: Store.state.presets.firstOrNull()
        if (preset == null) {
            busy = false
            paintSend()
            return
        }
        busy = true
        paintSend()
        b.err.visibility = View.GONE
        thread {
            try {
                val built = Engine.build(convId, fallbackTipId ?: asst.parentId)
                val full = Llm.stream(profile, built.messages, preset) { piece ->
                    if (!Store.state.streaming) return@stream
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val g = asst.generations[asst.generationIndex]
                        g.content += piece
                        asst.content = g.content
                        val lastIdx = adapter.items.lastIndex
                        if (lastIdx >= 0) {
                            adapter.notifyItemChanged(lastIdx, Unit)
                            b.messages.scrollToPosition(lastIdx)
                        } else {
                            refresh()
                        }
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
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
                    b.err.text = "${Store.t("error")}: ${e.message ?: "error"}"
                    if (asst.content.isBlank()) {
                        Store.state.messages.removeAll { it.id == asst.id }
                        if (conv()?.tipMessageId == asst.id) {
                            conv()?.tipMessageId = fallbackTipId ?: asst.parentId
                        }
                        Store.persist()
                        refresh()
                    }
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
        generate(m, m.parentId)
    }

    private fun continueGenerate(asst: ChatMessage) {
        val profile = Store.activeProfile()
        if (profile == null || profile.apiKey.isBlank()) {
            toast(Store.t("needProfile"))
            return
        }
        val preset = Store.state.presets.find { it.id == conv()?.presetId }
            ?: Store.localePresets().firstOrNull()
            ?: Store.state.presets.firstOrNull()
        if (preset == null || busy) return
        busy = true
        paintSend()
        b.err.visibility = View.GONE
        val promptBuilt = Engine.build(convId, asst.id)
        val continuePrompt = if (Store.state.locale == "en") {
            "[Instruction: Continue the narrative directly from the very last sentence. Do not repeat what was already written.]"
        } else {
            "【系统指令：直接顺承上文最后一句继续描写后续剧情与对话，严禁重复已有内容。】"
        }
        val messages = promptBuilt.messages.toMutableList()
        messages.add("user" to continuePrompt)
        thread {
            try {
                val full = Llm.stream(profile, messages, preset) { piece ->
                    if (!Store.state.streaming) return@stream
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val g = asst.generations.getOrNull(asst.generationIndex)
                        if (g != null) {
                            g.content += piece
                            asst.content = g.content
                        } else {
                            asst.content += piece
                        }
                        val lastIdx = adapter.items.indexOfFirst { it.id == asst.id }
                        if (lastIdx >= 0) adapter.notifyItemChanged(lastIdx, Unit)
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val g = asst.generations.getOrNull(asst.generationIndex)
                    if (g != null && full.isNotBlank()) g.content = asst.content
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
                    b.err.text = "${Store.t("error")}: ${e.message ?: "error"}"
                }
            }
        }
    }

    private fun showEditMessageDialog(m: ChatMessage) {
        val et = android.widget.EditText(this)
        val initialText = if (m.role == "assistant") Engine.display(m) else m.content
        et.setText(initialText)
        et.setTextColor(getColor(R.color.ink))
        et.setHintTextColor(getColor(R.color.muted))
        et.minLines = 5
        et.gravity = android.view.Gravity.TOP
        et.background = getDrawable(R.drawable.bg_input)
        et.setPadding(dp(12), dp(12), dp(12), dp(12))
        val pad = dp(16)
        val box = android.widget.FrameLayout(this)
        box.setPadding(pad, dp(12), pad, dp(4))
        box.addView(et)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("editMessage"))
            .setView(box)
            .setPositiveButton(Store.t("save")) { _, _ ->
                val newText = et.text.toString().trim()
                if (newText.isNotBlank()) {
                    if (m.role == "assistant") {
                        val g = m.generations.getOrNull(m.generationIndex)
                        if (g != null) g.content = newText
                        m.content = newText
                    } else {
                        m.content = newText
                    }
                    m.createdAt = Store.now()
                    Store.persist()
                    refresh()
                }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
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
            val story = Store.state.stories.find { it.id == conv()?.storyId }
            val mainPart = Store.state.participants.find { it.storyId == conv()?.storyId && it.role == "MAIN_CHARACTER" }
                ?: Store.state.participants.firstOrNull { it.storyId == conv()?.storyId }
            val world = if (ch == null && conv()?.worldBookIds?.isNotEmpty() == true) {
                Store.state.worldBooks.find { it.id in conv()!!.worldBookIds }
            } else null
            val displayCh = ch ?: Store.state.characters.find { it.id == mainPart?.characterId }
            val displayName = displayCh?.name ?: if (world != null) {
                "${world.name} · ${world.willName.ifBlank { Store.t("worldWill") }}"
            } else story?.name ?: conv()?.title ?: "?"
            val displayAvatar = displayCh?.avatar ?: world?.willAvatar
            val body = h.v.findViewById<TextView>(R.id.body)
            val user = h.v.findViewById<TextView>(R.id.userBody)
            val nameRow = h.v.findViewById<LinearLayout>(R.id.nameRow)
            val actions = h.v.findViewById<LinearLayout>(R.id.actions)
            actions.removeAllViews()
            if (m.role == "assistant") {
                user.setOnLongClickListener(null)
                nameRow.visibility = View.VISIBLE
                val avatarTv = h.v.findViewById<TextView>(R.id.avatar)
                val avatarImg = h.v.findViewById<ImageView>(R.id.avatarImg)
                renderAvatar(avatarTv, avatarImg, displayName, displayAvatar)
                h.v.findViewById<TextView>(R.id.speaker).text = displayName
                body.visibility = View.VISIBLE
                user.visibility = View.GONE
                body.text = Engine.display(m).ifBlank { if (busy && position == items.lastIndex) "…" else "" }
                val candle = getColor(R.color.candle)
                val muted = getColor(R.color.muted)
                val wine = getColor(R.color.wine)
                actions.addView(actionLabel(this@ChatActivity, Store.t("copy"), muted) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("t", Engine.display(m)))
                    Toast.makeText(this@ChatActivity, Store.t("copied"), Toast.LENGTH_SHORT).show()
                })
                actions.addView(actionLabel(this@ChatActivity, Store.t("edit"), muted) { showEditMessageDialog(m) })
                actions.addView(actionLabel(this@ChatActivity, Store.t("continueChat"), candle) { continueGenerate(m) })
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
                    confirm(this@ChatActivity, Store.t("deleteQ")) {
                        Store.state.messages.filter { it.parentId == m.id }.forEach { it.parentId = m.parentId }
                        Store.state.messages.removeAll { it.id == m.id }
                        if (conv()?.tipMessageId == m.id) conv()?.tipMessageId = m.parentId
                        Store.persist()
                        refresh()
                    }
                })
            } else {
                nameRow.visibility = View.GONE
                body.visibility = View.GONE
                user.visibility = View.VISIBLE
                user.text = m.content
                user.setOnLongClickListener {
                    val opts = arrayOf(Store.t("edit"), Store.t("copy"), Store.t("delete"))
                    androidx.appcompat.app.AlertDialog.Builder(this@ChatActivity)
                        .setItems(opts) { _, which ->
                            when (which) {
                                0 -> showEditMessageDialog(m)
                                1 -> {
                                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("t", m.content))
                                    Toast.makeText(this@ChatActivity, Store.t("copied"), Toast.LENGTH_SHORT).show()
                                }
                                2 -> {
                                    confirm(this@ChatActivity, Store.t("deleteQ")) {
                                        Store.state.messages.filter { it.parentId == m.id }.forEach { it.parentId = m.parentId }
                                        Store.state.messages.removeAll { it.id == m.id }
                                        if (conv()?.tipMessageId == m.id) conv()?.tipMessageId = m.parentId
                                        Store.persist()
                                        refresh()
                                    }
                                }
                            }
                        }
                        .show()
                    true
                }
            }
        }
    }
}
