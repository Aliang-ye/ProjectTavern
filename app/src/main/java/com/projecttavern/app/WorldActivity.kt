package com.projecttavern.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
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
                applyFormToDraft()
                w.willAvatar = saved
                renderAvatar(b.willAvatar, b.willAvatarImg, w.willName.ifBlank { Store.t("worldWill") }, w.willAvatar)
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
            draftWorld = existing.copy(
                willAlternateGreetings = existing.willAlternateGreetings.toMutableList()
            )
        }

        b = ActivityWorldBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupTouchToHideKeyboard(b.root, this)

        b.btnBack.setOnClickListener { askLeave() }
        b.btnSave.setOnClickListener {
            save(commitToStore = true)
            finish()
        }
        b.btnTest.setOnClickListener {
            if (isNew) {
                android.widget.Toast.makeText(this, Store.t("saveWorldFirst"), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save(commitToStore = true)
            startActivity(Intent(this, EntryTestActivity::class.java).putExtra("id", id))
        }
        b.btnTest.setOnLongClickListener {
            save(commitToStore = !isNew)
            if (!isNew) exportWorld()
            true
        }
        b.btnAddEntry.setOnClickListener { editEntry(null) }
        b.btnDelete.setOnClickListener {
            if (isNew) {
                finish()
                return@setOnClickListener
            }
            confirm(this, Store.t("deleteQ")) {
                Store.deleteWorld(id)
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
        baseline = snapshotOf(current())
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
            offerWorldChat(w.id)
        }

        b.lWillName.text = Store.t("willName")
        b.etWillName.setText(w.willName)

        b.lWillDesc.text = Store.t("description")
        b.etWillDesc.setText(w.willDescription)

        b.lWillScenario.text = Store.t("willScenario")
        b.etWillScenario.setText(w.willScenario)

        b.lWillFirstMsg.text = Store.t("willFirstMessage")
        b.etWillFirstMsg.setText(w.willFirstMessage)

        b.lWillAltMsg.text = Store.t("willAltGreetings")
        b.etWillAltMsg.hint = Store.t("willAltGreetingsHint")
        b.etWillAltMsg.setText(w.willAlternateGreetings.joinToString("\n"))

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
            val meta = buildString {
                if (e.constant) append("★ ${Store.t("constant")}") else append("p${e.priority}")
                if (e.probability < 100) append(" ${e.probability}%")
            }
            val sub = if (e.constant) "${Store.t("constantHint")} · ${e.content.take(30)}" else e.keys.joinToString(", ").ifBlank { "—" }
            inflateRow(b.entryList, e.name, sub, meta) {
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
        val secondaryEt = field(Store.t("secondaryKeys"), e.secondaryKeys.joinToString(","))
        val probabilityEt = field(Store.t("probability"), e.probability.toString())
        val positionEt = field(Store.t("insertPos"), e.insertionPosition)
        val cbConstant = CheckBox(this).apply {
            text = Store.t("constantEntry")
            isChecked = e.constant
            setTextColor(getColor(R.color.ink))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        box.addView(cbConstant)
        val content = field(Store.t("description"), e.content, true)
        val scroll = android.widget.ScrollView(this)
        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle(Store.t("addEntry"))
            .setView(scroll)
            .setPositiveButton(Store.t("save")) { _, _ ->
                e.name = name.text.toString()
                e.keys = keysEt.text.toString().split(Regex("[,，;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                e.secondaryKeys = secondaryEt.text.toString().split(Regex("[,，;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                e.probability = probabilityEt.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 100
                e.insertionPosition = if (positionEt.text.toString().contains("before")) "before_char" else "after_char"
                e.constant = cbConstant.isChecked
                e.content = content.text.toString()
                if (isNew) {
                    if (draftEntries.none { it.id == e.id }) draftEntries.add(e)
                } else {
                    if (Store.state.entries.none { it.id == e.id }) Store.state.entries.add(e)
                    Store.persist()
                }
                paintEntries()
                baseline = snapshotOf(current())
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
                baseline = snapshotOf(current())
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        askLeave()
    }

    private fun askLeave() {
        applyFormToDraft()
        if (!isDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(Store.t("unsavedTitle"))
            .setMessage(Store.t("unsavedBody"))
            .setPositiveButton(Store.t("save")) { _, _ ->
                save(commitToStore = true)
                finish()
            }
            .setNegativeButton(Store.t("discard")) { _, _ -> finish() }
            .setNeutralButton(Store.t("cancel"), null)
            .show()
    }

    private var baseline = ""

    private fun snapshotOf(w: WorldBook) = Store.gson.toJson(
        mapOf(
            "world" to w.copy(
                willAlternateGreetings = w.willAlternateGreetings.toMutableList(),
                createdAt = 0,
                updatedAt = 0,
            ),
            "entries" to (if (isNew) draftEntries else Store.state.entries.filter { it.worldBookId == id })
                .map { it.copy(keys = it.keys.toMutableList(), secondaryKeys = it.secondaryKeys.toMutableList()) },
        )
    )

    private fun applyFormToDraft() {
        val w = current()
        writeSection()
        w.name = b.etName.text.toString()
        w.willName = b.etWillName.text.toString()
        w.willDescription = b.etWillDesc.text.toString()
        w.willScenario = b.etWillScenario.text.toString()
        w.willFirstMessage = b.etWillFirstMsg.text.toString()
        w.willAlternateGreetings = b.etWillAltMsg.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        w.willSystemPrompt = b.etWillPrompt.text.toString()
    }

    private fun isDirty(): Boolean {
        applyFormToDraft()
        return snapshotOf(current()) != baseline
    }

    private fun offerWorldChat(worldBookId: String) {
        val last = Store.lastWorldWillChat(worldBookId)
        if (last == null) {
            val convId = Store.startWorldWillConversation(worldBookId) ?: return
            openScreen(ChatActivity::class.java) { it.putExtra("id", convId) }
            return
        }
        AlertDialog.Builder(this)
            .setItems(arrayOf(Store.t("continueLastChat"), Store.t("startNewChat"))) { _, which ->
                val convId = if (which == 0) last.id else Store.startWorldWillConversation(worldBookId)
                if (convId != null) openScreen(ChatActivity::class.java) { it.putExtra("id", convId) }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun save(commitToStore: Boolean = false) {
        applyFormToDraft()
        val w = current()
        w.name = w.name.ifBlank { Store.t("newWorld") }
        w.willName = w.willName.ifBlank { Store.t("worldWill") }
        if (w.willFirstMessage.isBlank()) {
            w.willFirstMessage = if (Store.state.locale == "en") "Welcome to ${w.name}. Where would you like to begin your journey?" else "「世界的心跳在此刻与你共鸣。你想从何处开启在【${w.name}】的故事？」"
        }
        if (w.willSystemPrompt.isBlank()) {
            w.willSystemPrompt = if (Store.state.locale == "en") "You are the World Will and Narrator for ${w.name}. Vividly describe environments, atmosphere, and NPCs. React to {{user}}'s actions, but never speak or act on behalf of {{user}}." else "你是【${w.name}】的【世界意志】与故事讲述者（World Will / Narrator）。\n根据世界法则与设定，生动描绘环境与NPC，推动情节，绝不代替玩家（{{user}}）发言或行动。"
        }
        w.updatedAt = Store.now()
        if (commitToStore) {
            val stored = w.copy(willAlternateGreetings = w.willAlternateGreetings.toMutableList())
            val idx = Store.state.worldBooks.indexOfFirst { it.id == stored.id }
            if (idx >= 0) Store.state.worldBooks[idx] = stored
            else Store.state.worldBooks.add(0, stored)
            draftWorld = stored.copy(willAlternateGreetings = stored.willAlternateGreetings.toMutableList())
            draftEntries.forEach { entry ->
                if (Store.state.entries.none { it.id == entry.id }) Store.state.entries.add(entry)
            }
            isNew = false
            Store.persist()
            baseline = snapshotOf(current())
        }
    }

    private fun exportWorld() {
        val w = Store.state.worldBooks.find { it.id == id } ?: current()
        val json = Cards.exportWorldPack(w)
        val safe = (w.name.ifBlank { "world" }).replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")
        val file = java.io.File(cacheDir, "$safe.world.json")
        file.writeText(json)
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, Store.t("exportWorld")))
    }
}
