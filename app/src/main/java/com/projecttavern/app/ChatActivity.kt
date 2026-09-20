package com.projecttavern.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // 流式输出节流：最多每 60ms 刷新一次 UI，避免高速模型打满主线程
    private val uiHandler = Handler(Looper.getMainLooper())
    private var throttlePending = false
    private var streamTarget: ChatMessage? = null

    // 智能自动滚动：记录用户是否在底部
    private var userAtBottom = true

    // 错误重试：记录最后一次失败时的 assistant 消息，以便一键重试
    private var lastFailedAsst: ChatMessage? = null

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
                userAtBottom = total == 0 || lastPos >= total - 2
                if (!userAtBottom) {
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
            if (debugOn) {
                b.debugBox.text = Engine.build(convId).debug
                b.debugBox.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
            }
        }
        b.btnSend.setOnLongClickListener {
            if (busy) stop()
            else if (b.etDraft.text.toString().isBlank()) {
                Toast.makeText(this, Store.t("writeAction"), Toast.LENGTH_SHORT).show()
            }
            true
        }
        // 重试按钮：出错后一键用上次失败的 assistant 消息重新生成
        b.btnRetry.setOnClickListener {
            val failed = lastFailedAsst ?: return@setOnClickListener
            lastFailedAsst = null
            b.btnRetry.visibility = View.GONE
            b.err.visibility = View.GONE
            // 清空已失败的空内容 assistant 消息，重新生成
            if (failed.content.isBlank()) {
                Store.state.messages.removeAll { it.id == failed.id }
                if (conv()?.tipMessageId == failed.id) conv()?.tipMessageId = failed.parentId
                Store.persist()
                refresh()
                // 取上一条 user 消息触发重新生成
                val parentUserMsg = Store.state.messages.find { it.id == failed.parentId }
                if (parentUserMsg != null) {
                    val profile = Store.profileFor(conv()) ?: return@setOnClickListener
                    val gen = Generation(Store.nid(), "", profile.model, profile.provider, Store.now())
                    val newAsst = ChatMessage(Store.nid(), convId, parentUserMsg.id, "assistant", "", mutableListOf(gen), 0, Store.now())
                    Store.state.messages.add(newAsst)
                    conv()?.tipMessageId = newAsst.id
                    conv()?.updatedAt = Store.now()
                    Store.persist()
                    refresh()
                    generate(newAsst, parentUserMsg.id)
                }
            } else {
                val profile = Store.profileFor(conv()) ?: return@setOnClickListener
                val current = failed.generations.getOrNull(failed.generationIndex)
                if (current != null && failed.content.isNotBlank()) current.content = failed.content
                failed.generations.add(Generation(Store.nid(), "", profile.model, profile.provider, Store.now()))
                failed.generationIndex = failed.generations.lastIndex
                failed.content = ""
                Store.persist()
                refresh()
                generate(failed, failed.parentId)
            }
        }
        if (Store.state.developerMode) {
            debugOn = true
            b.debugBox.visibility = View.VISIBLE
            b.debugBox.text = Engine.build(convId).debug
        }
        // 自动恢复未发出的草稿
        conv()?.draftText?.let {
            if (it.isNotBlank() && b.etDraft.text.isNullOrBlank()) {
                b.etDraft.setText(it)
                b.etDraft.setSelection(it.length)
            }
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
        val defaultName = ch?.name ?: if (world != null) {
            "${world.name} · ${world.willName.ifBlank { Store.t("worldWill") }}"
        } else "Conversation"
        // 优先展示用户自定义重命名的对话标题
        val headerName = c.title.ifBlank { defaultName }
        b.headerTitle.text = headerName
        // 长按标题弹出操作菜单：重命名 / 导出对话记录
        b.headerTitle.setOnLongClickListener {
            showConversationOptionsDialog()
            true
        }
        val contextText = if (c.storyId == null) Store.t("privateChat") else Store.state.stories.find { it.id == c.storyId }?.name ?: Store.t("stories")
        val live = if (busy) Store.t("streamingStatus") else Store.t("readyStatus")
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

    override fun onPause() {
        super.onPause()
        val text = b.etDraft.text?.toString() ?: ""
        conv()?.let {
            if (!busy && it.draftText != text) {
                it.draftText = text
                Store.persist()
            }
        }
    }

    override fun onDestroy() {
        val text = b.etDraft.text?.toString() ?: ""
        conv()?.let {
            if (it.draftText != text) {
                it.draftText = text
                Store.persist()
            }
        }
        super.onDestroy()
        Llm.cancel()
        val c = conv()
        val removed = Store.state.messages.removeAll { m ->
            m.conversationId == convId &&
                m.role == "assistant" &&
                m.content.isBlank() &&
                m.generations.all { it.content.isBlank() }
        }
        var tipFixed = false
        if (c != null && c.tipMessageId != null && Store.state.messages.none { it.id == c.tipMessageId }) {
            c.tipMessageId = Store.state.messages
                .filter { it.conversationId == convId }
                .maxByOrNull { it.createdAt }
                ?.id
            tipFixed = true
        }
        if (removed || tipFixed) Store.persist()
    }

    private fun paintSend() {
        b.btnSend.text = if (busy) "■" else Store.t("send").take(2)
        b.btnSend.setBackgroundResource(if (busy) R.drawable.bg_stop else R.drawable.bg_send)
        val c = conv() ?: return
        val contextText = if (c.storyId == null) Store.t("privateChat") else Store.state.stories.find { it.id == c.storyId }?.name ?: Store.t("stories")
        val live = if (busy) Store.t("streamingStatus") else Store.t("readyStatus")
        b.headerSub.text = "$contextText · $live"
    }

    private fun path(): List<ChatMessage> {
        val c = conv() ?: return emptyList()
        return Engine.visible(convId, c.tipMessageId)
    }

    private fun refresh(forceBottom: Boolean = false) {
        adapter.items = path()
        adapter.notifyDataSetChanged()
        if (adapter.items.isNotEmpty() && (forceBottom || userAtBottom)) {
            b.messages.scrollToPosition(adapter.items.size - 1)
        }
    }

    private fun send() {
        val text = b.etDraft.text.toString().trim()
        if (text.isEmpty() || busy) return
        val c = conv() ?: return
        val profile = Store.profileFor(c)
        if (profile == null || profile.apiKey.isBlank()) {
            toast(Store.t("needProfile"))
            return
        }
        if (c.profileId.isNullOrBlank()) c.profileId = profile.id
        b.etDraft.setText("")
        c.draftText = "" // 发送成功，清空已存草稿
        hideKeyboard()  // 发送后立即收起键盘，让消息列表完整显示
        // 发送新消息前，清理旧的未完成空白 assistant 脏数据
        Store.state.messages.removeAll { it.conversationId == convId && it.role == "assistant" && it.content.isBlank() && it.generations.all { g -> g.content.isBlank() } }
        val user = ChatMessage(Store.nid(), convId, c.tipMessageId, "user", text, mutableListOf(), 0, Store.now())
        Store.state.messages.add(user)
        val gen = Generation(Store.nid(), "", profile.model, profile.provider, Store.now())
        val asst = ChatMessage(Store.nid(), convId, user.id, "assistant", "", mutableListOf(gen), 0, Store.now())
        Store.state.messages.add(asst)
        c.tipMessageId = asst.id
        c.updatedAt = Store.now()
        Store.persist()
        refresh(forceBottom = true)
        generate(asst, user.id)
    }

    private fun generate(asst: ChatMessage, fallbackTipId: String? = null) {
        val profile = Store.profileFor(conv())
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
        streamTarget = asst
        throttlePending = false
        paintSend()
        b.err.visibility = View.GONE
        thread {
            try {
                val built = Engine.build(convId, fallbackTipId ?: asst.parentId)
                val full = Llm.stream(profile, built.messages, preset, { piece ->
                    if (!Store.state.streaming) return@stream
                    val g = asst.generations.getOrNull(asst.generationIndex) ?: return@stream
                    g.content += piece
                    asst.content = g.content
                    if (!throttlePending) {
                        throttlePending = true
                        uiHandler.postDelayed({
                            throttlePending = false
                            if (isFinishing || isDestroyed) return@postDelayed
                            val lastIdx = adapter.items.lastIndex
                            if (lastIdx >= 0) {
                                adapter.notifyItemChanged(lastIdx, Unit)
                                if (userAtBottom) b.messages.scrollToPosition(lastIdx)
                            }
                        }, 60)
                    }
                }, Store.state.streaming)
                runOnUiThread {
                    val g = asst.generations.getOrNull(asst.generationIndex) ?: asst.generations.lastOrNull()
                    if (g != null) {
                        if (full.isNotBlank()) g.content = full
                        asst.content = g.content
                    } else if (full.isNotBlank()) {
                        asst.content = full
                    }
                    Store.persist()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    streamTarget = null
                    paintSend()
                    refresh(forceBottom = userAtBottom)
                    if (userAtBottom && adapter.itemCount > 0) {
                        b.messages.scrollToPosition(adapter.itemCount - 1)
                    }
                    maybeSummarize()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (e.message != "CANCELLED" || asst.content.isNotBlank()) Store.persist()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    streamTarget = null
                    paintSend()
                    if (e.message == "CANCELLED") return@runOnUiThread
                    b.err.visibility = View.VISIBLE
                    b.err.text = "${Store.t("error")}: ${e.message ?: "error"}"
                    lastFailedAsst = asst
                    b.btnRetry.visibility = View.VISIBLE
                    if (asst.content.isBlank() && conv()?.tipMessageId != asst.id) {
                        conv()?.tipMessageId = fallbackTipId ?: asst.parentId
                        Store.persist()
                    }
                    refresh()
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
        val profile = Store.profileFor(conv())
        if (profile == null || profile.apiKey.isBlank()) {
            toast(Store.t("needProfile"))
            return
        }
        // 重写前先将当前最新展示文本保存在对应 generation 中，保证切换分支不丢失前次输出
        val currentGen = m.generations.getOrNull(m.generationIndex)
        if (currentGen != null && m.content.isNotBlank()) {
            currentGen.content = m.content
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
        val profile = Store.profileFor(conv())
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
                val full = Llm.stream(profile, messages, preset, { piece ->
                    if (!Store.state.streaming) return@stream
                    val g = asst.generations.getOrNull(asst.generationIndex)
                    if (g != null) {
                        g.content += piece
                        asst.content = g.content
                    } else {
                        asst.content += piece
                    }
                    if (!throttlePending) {
                        throttlePending = true
                        uiHandler.postDelayed({
                            throttlePending = false
                            if (isFinishing || isDestroyed) return@postDelayed
                            val idx = adapter.items.indexOfFirst { it.id == asst.id }
                            if (idx >= 0) {
                                adapter.notifyItemChanged(idx, Unit)
                                if (userAtBottom) b.messages.scrollToPosition(adapter.itemCount - 1)
                            }
                        }, 60)
                    }
                }, Store.state.streaming)
                runOnUiThread {
                    val g = asst.generations.getOrNull(asst.generationIndex)
                    if (full.isNotBlank()) {
                        if (g != null) {
                            if (!Store.state.streaming) g.content += full
                            else g.content = asst.content
                            asst.content = g.content
                        } else if (!Store.state.streaming) {
                            asst.content += full
                        }
                    }
                    Store.persist()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    paintSend()
                    refresh(forceBottom = userAtBottom)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Store.persist()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    paintSend()
                    if (e.message == "CANCELLED") return@runOnUiThread
                    b.err.visibility = View.VISIBLE
                    b.err.text = "${Store.t("error")}: ${e.message ?: "error"}"
                    lastFailedAsst = asst
                    b.btnRetry.visibility = View.VISIBLE
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
        val scroll = android.widget.ScrollView(this)
        scroll.addView(box)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("editMessage"))
            .setView(scroll)
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
                    // 不修改 createdAt，保留原始时间戳，避免打乱消息顺序
                    Store.persist()
                    refresh()
                }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun maybeSummarize() {
        if (!Engine.shouldSummarize(convId, conv()?.tipMessageId)) return
        val profile = Store.profileFor(conv()) ?: return
        val preset = Store.state.presets.find { it.id == conv()?.presetId } ?: Store.localePresets().firstOrNull() ?: return
        val prompt = Engine.summaryPrompt(convId, conv()?.tipMessageId)
        thread {
            try {
                val text = Llm.stream(
                    profile,
                    prompt,
                    preset.copy(maxTokens = 280, responseBudget = 280),
                    {},
                    streaming = false,
                    cancellable = false,
                )
                runOnUiThread {
                    if (text.isNotBlank()) {
                        Engine.upsertMemory(convId, text.trim())
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun forkFrom(m: ChatMessage) {
        val copy = ChatMessage(
            id = Store.nid(),
            conversationId = convId,
            parentId = m.parentId,
            role = m.role,
            content = if (m.role == "assistant") Engine.display(m) else m.content,
            generations = m.generations.map { it.copy(id = Store.nid()) }.toMutableList(),
            generationIndex = m.generationIndex,
            createdAt = Store.now(),
        )
        Store.state.messages.add(copy)
        conv()?.tipMessageId = Engine.leafOf(copy).id
        conv()?.updatedAt = Store.now()
        Store.persist()
        refresh(forceBottom = true)
        toast(Store.t("forked"))
    }

    private fun showMessageMenu(m: ChatMessage) {
        val labels = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        fun add(label: String, act: () -> Unit) { labels.add(label); acts.add(act) }
        add(Store.t("copy")) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = if (m.role == "assistant") Engine.display(m) else m.content
            cm.setPrimaryClip(android.content.ClipData.newPlainText("t", text))
            toast(Store.t("copied"))
        }
        add(Store.t("edit")) { showEditMessageDialog(m) }
        add(Store.t("fork")) { forkFrom(m) }
        if (m.role == "assistant") {
            add(Store.t("continueChat")) { continueGenerate(m) }
            add(Store.t("regenerate")) { regenerate(m) }
            if (m.generations.size > 1) {
                add("${m.generationIndex + 1}/${m.generations.size}") {
                    m.generationIndex = (m.generationIndex + 1) % m.generations.size
                    m.content = Engine.display(m)
                    Store.persist()
                    refresh()
                }
            }
        }
        val sibs = Engine.siblings(m)
        if (sibs.size > 1) {
            val idx = sibs.indexOfFirst { it.id == m.id }.coerceAtLeast(0)
            add("${Store.t("branchIndex")} ${idx + 1}/${sibs.size}") {
                val next = sibs[(idx + 1) % sibs.size]
                conv()?.tipMessageId = Engine.leafOf(next).id
                Store.persist()
                refresh(forceBottom = true)
            }
        }
        add(Store.t("delete")) {
            confirm(this, Store.t("deleteQ")) {
                Store.state.messages.filter { it.parentId == m.id }.forEach { it.parentId = m.parentId }
                Store.state.messages.removeAll { it.id == m.id }
                if (conv()?.tipMessageId == m.id) conv()?.tipMessageId = m.parentId
                Store.persist()
                refresh()
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .show()
    }

    private fun showConversationOptionsDialog() {
        val opts = arrayOf(Store.t("renameConversation"), Store.t("exportChat"))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showRenameConversationDialog()
                    1 -> exportChatAsMarkdown()
                }
            }
            .show()
    }

    private fun exportChatAsMarkdown() {
        val c = conv() ?: return
        val ch = Store.state.characters.find { it.id == c.characterId }
        val charName = ch?.name ?: c.title
        val persona = Store.state.personas.find { it.id == c.personaId } ?: Store.state.personas.firstOrNull()
        val userName = persona?.name ?: Store.state.userName.ifBlank { "You" }
        val msgs = path()

        Thread {
            val sb = StringBuilder()
            sb.append("# ${c.title}\n\n")
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val bannerTitle = if (Store.state.locale == "en") "> Project Tavern Chat History Export\n" else "> Project Tavern 对话记录导出\n"
            val datePrefix = if (Store.state.locale == "en") "> Exported At: " else "> 导出时间："
            sb.append(bannerTitle)
            sb.append("$datePrefix$dateStr\n\n---\n\n")

            for (m in msgs) {
                val speaker = if (m.role == "assistant") charName else userName
                val content = if (m.role == "assistant") Engine.display(m) else m.content
                sb.append("### **$speaker**\n\n")
                sb.append(content.trim())
                sb.append("\n\n---\n\n")
            }

            val safeName = (c.title.ifBlank { "chat" }).replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")
            val file = java.io.File(cacheDir, "$safeName.md")
            file.writeText(sb.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            runOnUiThread {
                val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/markdown")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(android.content.Intent.createChooser(share, Store.t("exportChat")))
            }
        }.start()
    }

    private fun showRenameConversationDialog() {
        val c = conv() ?: return
        val et = android.widget.EditText(this)
        et.setText(c.title)
        et.setTextColor(getColor(R.color.ink))
        et.setHintTextColor(getColor(R.color.muted))
        et.setSingleLine(true)
        et.background = getDrawable(R.drawable.bg_input)
        et.setPadding(dp(12), dp(12), dp(12), dp(12))
        et.selectAll()
        val pad = dp(16)
        val box = android.widget.FrameLayout(this)
        box.setPadding(pad, dp(12), pad, dp(4))
        box.addView(et)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("renameConversation"))
            .setView(box)
            .setPositiveButton(Store.t("save")) { _, _ ->
                val newTitle = et.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    c.title = newTitle
                    c.updatedAt = Store.now()
                    Store.persist()
                    bindHeader()
                    Toast.makeText(this, Store.t("renamed"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

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
                actions.gravity = android.view.Gravity.START
                nameRow.visibility = View.VISIBLE
                val avatarTv = h.v.findViewById<TextView>(R.id.avatar)
                val avatarImg = h.v.findViewById<ImageView>(R.id.avatarImg)
                renderAvatar(avatarTv, avatarImg, displayName, displayAvatar)
                h.v.findViewById<TextView>(R.id.speaker).text = displayName
                body.visibility = View.VISIBLE
                user.visibility = View.GONE
                val rawText = Engine.display(m).ifBlank { if (busy && position == items.lastIndex) "…" else "" }
                body.setText(Engine.formatRpText(rawText), TextView.BufferType.SPANNABLE)
                val muted = getColor(R.color.muted)
                val sibs = Engine.siblings(m)
                if (m.generations.size > 1) {
                    actions.addView(actionLabel(this@ChatActivity, "${m.generationIndex + 1}/${m.generations.size}", muted) {
                        m.generationIndex = (m.generationIndex + 1) % m.generations.size
                        m.content = Engine.display(m)
                        Store.persist()
                        refresh()
                    })
                }
                if (sibs.size > 1) {
                    val idx = sibs.indexOfFirst { it.id == m.id }.coerceAtLeast(0)
                    actions.addView(actionLabel(this@ChatActivity, "${Store.t("branchIndex")} ${idx + 1}/${sibs.size}", muted) {
                        conv()?.tipMessageId = Engine.leafOf(sibs[(idx + 1) % sibs.size]).id
                        Store.persist()
                        refresh(forceBottom = true)
                    })
                }
                actions.addView(actionLabel(this@ChatActivity, Store.t("more"), muted) { showMessageMenu(m) })
                body.setOnLongClickListener { showMessageMenu(m); true }
            } else {
                actions.gravity = android.view.Gravity.END
                nameRow.visibility = View.GONE
                body.visibility = View.GONE
                user.visibility = View.VISIBLE
                user.setText(Engine.formatRpText(m.content), TextView.BufferType.SPANNABLE)
                val muted = getColor(R.color.muted)
                val sibs = Engine.siblings(m)
                if (sibs.size > 1) {
                    val idx = sibs.indexOfFirst { it.id == m.id }.coerceAtLeast(0)
                    actions.addView(actionLabel(this@ChatActivity, "${Store.t("branchIndex")} ${idx + 1}/${sibs.size}", muted) {
                        conv()?.tipMessageId = Engine.leafOf(sibs[(idx + 1) % sibs.size]).id
                        Store.persist()
                        refresh(forceBottom = true)
                    })
                }
                actions.addView(actionLabel(this@ChatActivity, Store.t("more"), muted) { showMessageMenu(m) })
                user.setOnLongClickListener { showMessageMenu(m); true }
            }
        }
    }
}
