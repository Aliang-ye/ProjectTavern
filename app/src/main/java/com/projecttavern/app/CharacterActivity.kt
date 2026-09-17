package com.projecttavern.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.projecttavern.app.databinding.ActivityCharacterBinding

class CharacterActivity : AppCompatActivity() {
    private lateinit var b: ActivityCharacterBinding
    private lateinit var id: String

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val path = saveAvatar(this, id, uri)
            if (path != null) {
                current()?.avatar = path
                current()?.updatedAt = Store.now()
                Store.persist()
                bind()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("id") ?: return finish()
        b = ActivityCharacterBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { save(); finish() }
        b.avatarContainer.setOnClickListener { pickAvatar.launch("image/*") }
        b.tapAvatar.setOnClickListener { pickAvatar.launch("image/*") }
        b.avatarContainer.setOnLongClickListener {
            val c = current()
            if (c?.avatar != null) {
                confirm(this, Store.t("deleteQ")) {
                    c.avatar = null
                    Store.persist()
                    bind()
                }
            }
            true
        }
        b.btnPrivate.setOnClickListener {
            save()
            val cid = Store.startConversation(id, null) ?: return@setOnClickListener
            startActivity(Intent(this, ChatActivity::class.java).putExtra("id", cid))
        }
        b.btnDup.setOnClickListener {
            val ch = current() ?: return@setOnClickListener
            save()
            val copy = ch.copy(id = Store.nid(), name = "${ch.name} ${if (Store.state.locale == "en") "copy" else "副本"}", createdAt = Store.now(), updatedAt = Store.now())
            Store.state.characters.add(0, copy)
            Store.persist()
            startActivity(Intent(this, CharacterActivity::class.java).putExtra("id", copy.id))
            finish()
        }
        b.btnDelete.setOnClickListener {
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
        bind()
    }

    private fun current() = Store.state.characters.find { it.id == id }

    private fun bind() {
        val c = current() ?: return finish()
        b.headerTitle.text = c.name
        b.btnSave.text = Store.t("save")
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
        b.btnDup.text = Store.t("duplicate")
        b.btnDelete.text = Store.t("delete")
        val worlds = listOf(Store.t("none")) + Store.state.worldBooks.map { it.name }
        b.spWorld.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, worlds)
        val def = Store.defaultWorldId(id)
        val idx = Store.state.worldBooks.indexOfFirst { it.id == def }
        b.spWorld.setSelection(if (idx >= 0) idx + 1 else 0)
    }

    private fun save() {
        val c = current() ?: return
        c.name = b.etName.text.toString()
        c.tags = b.etTags.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        c.description = b.etDesc.text.toString()
        c.personality = b.etPersonality.text.toString()
        c.scenario = b.etScenario.text.toString()
        c.firstMessage = b.etFirst.text.toString()
        c.alternateGreetings = b.etAlt.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        c.exampleDialogues = b.etExamples.text.toString()
        c.systemPrompt = b.etSystem.text.toString()
        c.creatorNotes = b.etNotes.text.toString()
        c.updatedAt = Store.now()
        val sel = b.spWorld.selectedItemPosition
        Store.setDefaultWorld(id, if (sel <= 0) null else Store.state.worldBooks.getOrNull(sel - 1)?.id)
    }
}
