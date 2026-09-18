package com.projecttavern.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.projecttavern.app.databinding.ActivityWorldBinding

class WorldActivity : AppCompatActivity() {
    private lateinit var b: ActivityWorldBinding
    private lateinit var id: String
    private var isNew: Boolean = false
    private lateinit var draftWorld: WorldBook
    private val draftEntries = mutableListOf<WorldBookEntry>()
    private var section = "description"
    private val keys = listOf("description", "geography", "history", "institutions", "culture", "personalNotes")

    private val pickAvatarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val w = current()
            val saved = saveAvatar(this, "will_${w.id}", uri)
            if (saved != null) {
                w.willAvatar = saved
                renderAvatar(b.willAvatar, b.willAvatarImg, w.willName, w.willAvatar)
                if (!isNew) Store.persist()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isNew = intent.getBooleanExtra("isNew", false)
        id = intent.getStringExtra("id") ?: Store.nid()

        if (isNew) {
            val isEn = Store.state.locale == "en"
            draftWorld = WorldBook(
                id = id,
                name = "",
                willName = if (isEn) "World Will" else "世界意志",
                willDescription = if (isEn) "Inherent consciousness that governs environments and rules." else "负责主持与引导世界的故事发展、环境描写与NPC交互。",
                willScenario = if (isEn) "Observes all travelers and choices within this world." else "身处于世界之中，注视着旅人的每一步选择。",
                willFirstMessage = if (isEn) "Where would you like to begin your journey?" else "「世界的心跳在此刻与你共鸣。你想从何处开启你的故事？」",
                willSystemPrompt = if (isEn) "You are the World Will and Narrator. Vividly describe environments, atmosphere, and NPCs. React to {{user}}'s actions, but never speak or act on behalf of {{user}}." else "你是【世界意志】与故事讲述者（World Will / Narrator）。\n根据世界法则与设定，生动描绘环境与NPC，推动情节，绝不代替玩家（{{user}}）发言或行动。",
                createdAt = Store.now(),
                updatedAt = Store.now()
            )
        } else {
            val existing = Store.state.worldBooks.find { it.id == id }
            if (existing == null) {
                finish()
                return
            }
            draftWorld = existing.copy()
        }

        b = ActivityWorldBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupTouchToHideKeyboard(b.root, this)

        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener {
            save(commitToStore = true)
            finish()
        }
        b.btnTest.setOnClickListener {
            save(commitToStore = !isNew)
            if (!isNew) {
                startActivity(Intent(this, EntryTestActivity::class.java).putExtra("id", id))
            }
        }
        b.btnAddEntry.setOnClickListener { editEntry(null) }
        b.btnDelete.setOnClickListener {
            if (isNew) {
                finish()
                return@setOnClickListener
            }
            confirm(this, Store.t("deleteQ")) {
                Store.state.worldBooks.removeAll { it.id == id }
                Store.state.entries.removeAll { it.worldBookId == id }
                Store.state.characterWorldBooks.removeAll { it.worldBookId == id }
                Store.state.stories.forEach { it.worldBookIds.remove(id) }
                Store.state.conversations.forEach { it.worldBookIds.remove(id) }
                Store.persist()
                finish()
            }
        }
        b.tabLore.setOnClickListener { switchTab(0) }
        b.tabWill.setOnClickListener { switchTab(1) }
        b.tabEntries.setOnClickListener { switchTab(2) }

        b.frameWillAvatar.setOnClickListener { pickAvatarLauncher.launch("image/*") }
        b.btnChangeAvatar.setOnClickListener { pickAvatarLauncher.launch("image/*") }

        b.etSection.addTextChangedListener(SimpleWatcher { writeSection() })
        b.etName.addTextChangedListener(SimpleWatcher {
            val name = b.etName.text.toString().trim()
            if (name.isNotEmpty()) {
                b.headerTitle.text = name
                b.willTitle.text = "${name} · ${b.etWillName.text.toString().ifBlank { Store.t("worldWill") }}"
            }
        })
        b.etWillName.addTextChangedListener(SimpleWatcher {
            val willName = b.etWillName.text.toString().trim()
            val worldName = b.etName.text.toString().trim().ifBlank { Store.t("newWorld") }
            b.willTitle.text = "$worldName · ${willName.ifBlank { Store.t("worldWill") }}"
            renderAvatar(b.willAvatar, b.willAvatarImg, willName.ifBlank { Store.t("worldWill") }, current().willAvatar)
        })

        bind()
    }

    private fun switchTab(tab: Int) {
        hideKeyboard()
        b.panelLore.visibility = if (tab == 0) View.VISIBLE else View.GONE
        b.panelWill.visibility = if (tab == 1) View.VISIBLE else View.GONE
        b.panelEntries.visibility = if (tab == 2) View.VISIBLE else View.GONE

        fun styleTab(tv: android.widget.TextView, on: Boolean) {
            tv.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (on) R.color.on_candle else R.color.ink))
        }

        styleTab(b.tabLore, tab == 0)
        styleTab(b.tabWill, tab == 1)
        styleTab(b.tabEntries, tab == 2)
        b.scrollContainer.smoothScrollTo(0, 0)
    }

    private fun current(): WorldBook = draftWorld

    private fun bind() {
        val w = current()
        b.headerTitle.text = if (isNew) Store.t("newWorldTitle") else w.name.ifBlank { Store.t("newWorld") }
        b.btnSave.text = if (isNew) Store.t("create") else Store.t("save")
        b.btnTest.text = Store.t("entryTest")
        b.btnTest.visibility = if (isNew) View.GONE else View.VISIBLE
        b.btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE

        b.tabLore.text = Store.t("tabLore")
        b.tabWill.text = Store.t("tabWill")
        b.tabEntries.text = Store.t("tabEntries")
        b.lName.text = Store.t("name")
        b.etName.setText(w.name)
        b.sectionsHint.text = Store.t("sectionsHint")

        // World Will Tab binding
        val willName = w.willName.ifBlank { Store.t("worldWill") }
        val worldName = w.name.ifBlank { Store.t("newWorld") }
        b.willTitle.text = "$worldName · $willName"
        b.willSubtitle.text = Store.t("willDesc")
        b.btnChangeAvatar.text = Store.t("tapAvatar")
        renderAvatar(b.willAvatar, b.willAvatarImg, willName, w.willAvatar)

        b.btnChatWill.text = Store.t("chatWithWill")
        b.btnChatWill.setOnClickListener {
            save(commitToStore = true)
            val convId = Store.startWorldWillConversation(w.id) ?: return@setOnClickListener
            openScreen(ChatActivity::class.java) { it.putExtra("id", convId) }
        }

        b.lWillName.text = Store.t("willName")
        b.etWillName.setText(w.willName)

        b.lWillDesc.text = Store.t("description")
        b.etWillDesc.setText(w.willDescription)

        b.lWillScenario.text = Store.t("willScenario")
        b.etWillScenario.setText(w.willScenario)

        b.lWillFirstMsg.text = Store.t("willFirstMessage")
        b.etWillFirstMsg.setText(w.willFirstMessage)

        b.lWillPrompt.text = Store.t("willSystemPrompt")
        b.etWillPrompt.setText(w.willSystemPrompt)

        val totalEntries = if (isNew) draftEntries.size else Store.state.entries.count { it.worldBookId == id }
        b.lEntries.text = "${Store.t("entries")} $totalEntries"
        b.btnAddEntry.text = "+ ${Store.t("addEntry")}"
        b.btnDelete.text = Store.t("delete")

        paintChips()
        showSection()
        paintEntries()
    }

    private fun paintChips() {
        b.sectionChips.removeAllViews()
        val labels = mapOf(
            "description" to Store.t("overview"),
            "geography" to Store.t("geography"),
            "history" to Store.t("history"),
            "institutions" to Store.t("institutions"),
            "culture" to Store.t("culture"),
            "personalNotes" to Store.t("personal"),
        )
        keys.forEach { k ->
            b.sectionChips.addView(chip(this, labels[k] ?: k, k == section) {
                writeSection()
                section = k
                paintChips()
                showSection()
            })
        }
    }

    private fun field(w: WorldBook): String = when (section) {
        "geography" -> w.geography
        "history" -> w.history
        "institutions" -> w.institutions
        "culture" -> w.culture
        "personalNotes" -> w.personalNotes
        else -> w.description
    }

    private fun writeSection() {
        val w = current()
        val v = b.etSection.text.toString()
        when (section) {
            "geography" -> w.geography = v
            "history" -> w.history = v
            "institutions" -> w.institutions = v
            "culture" -> w.culture = v
            "personalNotes" -> w.personalNotes = v
            else -> w.description = v
        }
    }

    private fun showSection() {
        val w = current()
        b.etSection.setText(field(w))
    }

    private fun paintEntries() {
        b.entryList.removeAllViews()
        val entries = if (isNew) draftEntries else Store.state.entries.filter { it.worldBookId == id }
        entries.sortedByDescending { it.priority }.forEach { e ->
            inflateRow(b.entryList, e.name, e.keys.joinToString(", ").ifBlank { "—" }, "p${e.priority}") {
                editEntry(e)
            }
        }
    }

    private fun editEntry(existing: WorldBookEntry?) {
        val e = existing ?: WorldBookEntry(Store.nid(), id, Store.t("addEntry"), insertionPosition = "after_char")
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        fun field(hint: String, value: String, multi: Boolean = false): EditText {
            val et = EditText(this)
            et.hint = hint
            et.setText(value)
            et.setTextColor(getColor(R.color.ink))
            et.setHintTextColor(getColor(R.color.muted))
            if (multi) et.minLines = 4
            box.addView(et)
            return et
        }
        val name = field(Store.t("name"), e.name)
        val keysEt = field(Store.t("tags"), e.keys.joinToString(","))
        val content = field(Store.t("description"), e.content, true)
        AlertDialog.Builder(this)
            .setTitle(Store.t("addEntry"))
            .setView(box)
            .setPositiveButton(Store.t("save")) { _, _ ->
                e.name = name.text.toString()
                e.keys = keysEt.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                e.content = content.text.toString()
                if (isNew) {
                    if (draftEntries.none { it.id == e.id }) draftEntries.add(e)
                } else {
                    if (Store.state.entries.none { it.id == e.id }) Store.state.entries.add(e)
                    Store.persist()
                }
                paintEntries()
            }
            .setNegativeButton(Store.t("cancel"), null)
            .setNeutralButton(Store.t("delete")) { _, _ ->
                if (isNew) {
                    draftEntries.removeAll { it.id == e.id }
                } else {
                    Store.state.entries.removeAll { it.id == e.id }
                    Store.persist()
                }
                paintEntries()
            }
            .show()
    }

    private fun save(commitToStore: Boolean = false) {
        val w = current()
        writeSection()
        w.name = b.etName.text.toString().ifBlank { Store.t("newWorld") }
        w.willName = b.etWillName.text.toString().ifBlank { Store.t("worldWill") }
        w.willDescription = b.etWillDesc.text.toString()
        w.willScenario = b.etWillScenario.text.toString()
        w.willFirstMessage = b.etWillFirstMsg.text.toString().ifBlank {
            if (Store.state.locale == "en") "Welcome to ${w.name}. Where would you like to begin your journey?" else "「世界的心跳在此刻与你共鸣。你想从何处开启在【${w.name}】的故事？」"
        }
        w.willSystemPrompt = b.etWillPrompt.text.toString().ifBlank {
            if (Store.state.locale == "en") "You are the World Will and Narrator for ${w.name}. Vividly describe environments, atmosphere, and NPCs. React to {{user}}'s actions, but never speak or act on behalf of {{user}}." else "你是【${w.name}】的【世界意志】与故事讲述者（World Will / Narrator）。\n根据世界法则与设定，生动描绘环境与NPC，推动情节，绝不代替玩家（{{user}}）发言或行动。"
        }
        w.updatedAt = Store.now()

        if (commitToStore) {
            if (isNew && Store.state.worldBooks.none { it.id == w.id }) {
                Store.state.worldBooks.add(0, w)
                draftEntries.forEach {
                    if (Store.state.entries.none { e -> e.id == it.id }) Store.state.entries.add(it)
                }
                isNew = false
            }
            Store.persist()
        }
    }
}
