package com.projecttavern.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.projecttavern.app.databinding.ActivityCharacterBinding

class CharacterActivity : AppCompatActivity() {
    private lateinit var b: ActivityCharacterBinding
    private lateinit var id: String
    private var isNew: Boolean = false
    private lateinit var draftCharacter: Character

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val path = saveAvatar(this, id, uri)
            if (path != null) {
                current().avatar = path
                current().updatedAt = Store.now()
                if (!isNew) Store.persist()
                bind()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isNew = intent.getBooleanExtra("isNew", false)
        id = intent.getStringExtra("id") ?: Store.nid()

        if (isNew) {
            val defaultGreeting = if (Store.state.locale == "en") "Hello, traveler. What brings you here?" else "你好，旅人。找我有什么事吗？"
            draftCharacter = Character(
                id = id,
                name = "",
                firstMessage = defaultGreeting,
                createdAt = Store.now(),
                updatedAt = Store.now()
            )
        } else {
            val existing = Store.state.characters.find { it.id == id }
            if (existing == null) {
                finish()
                return
            }
            draftCharacter = existing.copy(
                tags = existing.tags.toMutableList(),
                alternateGreetings = existing.alternateGreetings.toMutableList(),
            )
        }

        b = ActivityCharacterBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener {
            save(commitToStore = true)
            finish()
        }
        // 长按保存按钮 → 保存并导出 JSON（方便分享角色卡）
        b.btnSave.setOnLongClickListener {
            save(commitToStore = true)
            exportCharacterJson()
            true
        }
        b.avatarContainer.setOnClickListener { pickAvatar.launch("image/*") }
        b.tapAvatar.setOnClickListener { pickAvatar.launch("image/*") }
        b.avatarContainer.setOnLongClickListener {
            val c = current()
            if (c.avatar != null) {
                confirm(this, Store.t("deleteQ")) {
                    c.avatar = null
                    if (!isNew) Store.persist()
                    bind()
                }
            }
            true
        }
        b.btnPrivate.setOnClickListener {
            save(commitToStore = true)
            val cid = Store.startConversation(id, null) ?: return@setOnClickListener
            startActivity(Intent(this, ChatActivity::class.java).putExtra("id", cid))
        }
        b.btnExport.setOnClickListener {
            save(commitToStore = !isNew)
            val ch = current()
            val opts = arrayOf(Store.t("exportCharacter"), Store.t("exportPng"))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(opts) { _, which ->
                    if (which == 1) exportCharacterPng() else {
                        val json = Cards.exportCharacterV2Json(ch)
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("character", json))
                        android.widget.Toast.makeText(this, Store.t("copied"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
        b.btnDup.setOnClickListener {
            save(commitToStore = !isNew)
            val ch = current()
            val copy = ch.copy(id = Store.nid(), name = "${ch.name} ${if (Store.state.locale == "en") "copy" else "副本"}", createdAt = Store.now(), updatedAt = Store.now())
            Store.state.characters.add(0, copy)
            Store.persist()
            startActivity(Intent(this, CharacterActivity::class.java).putExtra("id", copy.id))
            finish()
        }
        b.btnDelete.setOnClickListener {
            if (isNew) {
                finish()
                return@setOnClickListener
            }
            confirm(this, Store.t("deleteQ")) {
                Store.state.characters.removeAll { it.id == id }
                Store.state.characterWorldBooks.removeAll { it.characterId == id }
                Store.state.participants.removeAll { it.characterId == id }
                val removedConvs = Store.state.conversations.filter { it.characterId == id && it.storyId == null }
                val removedIds = removedConvs.map { it.id }.toSet()
                Store.state.messages.removeAll { it.conversationId in removedIds }
                Store.state.conversations.removeAll { it.id in removedIds }
                Store.persist()
                finish()
            }
        }
        b.tabBasic.setOnClickListener { switchTab(0) }
        b.tabPersona.setOnClickListener { switchTab(1) }
        b.tabDialogue.setOnClickListener { switchTab(2) }
        b.tabPrompt.setOnClickListener { switchTab(3) }
        setupTouchToHideKeyboard(b.root, this)
        bind()
    }

    private var activeTab = 0

    private fun switchTab(tab: Int) {
        activeTab = tab
        hideKeyboard()
        b.panelBasic.visibility = if (tab == 0) View.VISIBLE else View.GONE
        b.panelPersona.visibility = if (tab == 1) View.VISIBLE else View.GONE
        b.panelDialogue.visibility = if (tab == 2) View.VISIBLE else View.GONE
        b.panelPrompt.visibility = if (tab == 3) View.VISIBLE else View.GONE

        fun styleTab(tv: android.widget.TextView, on: Boolean) {
            tv.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (on) R.color.on_candle else R.color.ink))
        }

        styleTab(b.tabBasic, tab == 0)
        styleTab(b.tabPersona, tab == 1)
        styleTab(b.tabDialogue, tab == 2)
        styleTab(b.tabPrompt, tab == 3)
        b.scrollContainer.smoothScrollTo(0, 0)
    }

    private fun current(): Character = draftCharacter

    private fun bind() {
        val c = current()
        b.headerTitle.text = if (isNew) Store.t("newCharacterTitle") else c.name.ifBlank { Store.t("newCharacter") }
        b.btnSave.text = if (isNew) Store.t("create") else Store.t("save")
        b.btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE
        b.btnDup.visibility = if (isNew) View.GONE else View.VISIBLE
        b.btnExport.visibility = if (isNew) View.GONE else View.VISIBLE

        b.tabBasic.text = Store.t("tabBasic")
        b.tabPersona.text = Store.t("tabPersona")
        b.tabDialogue.text = Store.t("tabDialogue")
        b.tabPrompt.text = Store.t("tabPrompt")
        renderAvatar(b.avatar, b.avatarImg, c.name, c.avatar)
        b.tapAvatar.text = Store.t("tapAvatar")
        b.btnPrivate.text = Store.t("privateChat")
        b.privateHint.text = Store.t("privateHint")
        b.lName.text = Store.t("name"); b.etName.setText(c.name)
        b.lTags.text = Store.t("tags"); b.etTags.setText(c.tags.joinToString(","))
        b.lDesc.text = Store.t("description"); b.etDesc.setText(c.description)
        b.lPersonality.text = Store.t("personality"); b.etPersonality.setText(c.personality)
        b.lScenario.text = Store.t("scenario"); b.etScenario.setText(c.scenario)
        b.lFirst.text = Store.t("firstMessage"); b.etFirst.setText(c.firstMessage)
        b.lAlt.text = Store.t("altGreetings"); b.etAlt.setText(c.alternateGreetings.joinToString("\n"))
        b.lExamples.text = Store.t("exampleDialogues"); b.etExamples.setText(c.exampleDialogues)
        b.lSystem.text = Store.t("systemPrompt"); b.etSystem.setText(c.systemPrompt)
        b.lNotes.text = Store.t("creatorNotes"); b.etNotes.setText(c.creatorNotes)
        b.lWorld.text = Store.t("defaultWorld")
        b.btnExport.text = Store.t("exportCharacter")
        b.btnDup.text = Store.t("duplicate")
        b.btnDelete.text = Store.t("delete")
        val worlds = listOf(Store.t("none")) + Store.state.worldBooks.map { it.name }
        b.spWorld.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, worlds)
        val def = Store.defaultWorldId(id)
        val idx = Store.state.worldBooks.indexOfFirst { it.id == def }
        b.spWorld.setSelection(if (idx >= 0) idx + 1 else 0)
    }

    private fun save(commitToStore: Boolean = false) {
        val c = current()
        c.name = b.etName.text.toString().ifBlank { Store.t("newCharacter") }
        c.tags = b.etTags.text.toString().split(Regex("[,，;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        c.description = b.etDesc.text.toString()
        c.personality = b.etPersonality.text.toString()
        c.scenario = b.etScenario.text.toString()
        c.firstMessage = b.etFirst.text.toString()
        c.alternateGreetings = b.etAlt.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        c.exampleDialogues = b.etExamples.text.toString()
        c.systemPrompt = b.etSystem.text.toString()
        c.creatorNotes = b.etNotes.text.toString()
        c.updatedAt = Store.now()

        if (commitToStore) {
            if (isNew && Store.state.characters.none { it.id == c.id }) {
                Store.state.characters.add(0, c)
                isNew = false
            }
            val sel = b.spWorld.selectedItemPosition
            Store.setDefaultWorld(id, if (sel <= 0) null else Store.state.worldBooks.getOrNull(sel - 1)?.id)
            Store.persist()
        }
    }

    private fun exportCharacterJson() {
        val c = Store.state.characters.find { it.id == id } ?: draftCharacter
        val json = Cards.exportCharacterV2Json(c)
        val safeName = (c.name.ifBlank { "character" }).replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")
        val file = java.io.File(cacheDir, "$safeName.json")
        file.writeText(json)
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(android.content.Intent.createChooser(share, Store.t("exportCharacter")))
    }

    private fun exportCharacterPng() {
        val c = Store.state.characters.find { it.id == id } ?: draftCharacter
        kotlin.concurrent.thread {
            try {
                val file = Cards.exportCharacterPng(this, c)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                runOnUiThread {
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(android.content.Intent.createChooser(share, Store.t("exportPng")))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, e.message ?: Store.t("error"), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
