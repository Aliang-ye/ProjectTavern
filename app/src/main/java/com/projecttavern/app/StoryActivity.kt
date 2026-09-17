package com.projecttavern.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.projecttavern.app.databinding.ActivityStoryBinding

class StoryActivity : AppCompatActivity() {
    private lateinit var b: ActivityStoryBinding
    private lateinit var id: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("id") ?: return finish()
        b = ActivityStoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { save(); finish() }
        b.btnAddMain.setOnClickListener { pickCharacter("MAIN_CHARACTER") }
        b.btnAddCompanion.setOnClickListener { pickCharacter("COMPANION") }
        b.btnAddNpc.setOnClickListener { pickCharacter("NPC") }
        b.btnStart.setOnClickListener {
            save()
            val conv = Store.startConversation(null, id, current()?.personaId) ?: return@setOnClickListener
            startActivity(Intent(this, ChatActivity::class.java).putExtra("id", conv))
        }
        b.btnDelete.setOnClickListener {
            confirm(this, Store.t("deleteQ")) {
                Store.state.stories.removeAll { it.id == id }
                Store.state.participants.removeAll { it.storyId == id }
                Store.state.conversations.filter { it.storyId == id }.forEach { it.storyId = null }
                Store.persist()
                finish()
            }
        }
        bind()
    }

    private fun current() = Store.state.stories.find { it.id == id }

    private fun bind() {
        val st = current() ?: return finish()
        b.headerTitle.text = st.name
        b.btnSave.text = Store.t("save")
        b.lName.text = Store.t("name"); b.etName.setText(st.name)
        b.lDesc.text = Store.t("storyDesc"); b.etDesc.setText(st.description)
        b.lPersona.text = Store.t("storyPersona")
        b.lWorld.text = Store.t("storyWorld")
        b.lCast.text = Store.t("storyCast")
        b.btnAddMain.text = "+ ${Store.t("roleMain")}"
        b.btnAddCompanion.text = Store.t("addCompanion")
        b.btnAddNpc.text = Store.t("addNpc")
        b.btnStart.text = Store.t("startChat")
        b.btnDelete.text = Store.t("delete")

        val personaNames = listOf(Store.t("storyPersonaDefault")) + Store.state.personas.map { it.name }
        b.spPersona.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, personaNames)
        val pIdx = Store.state.personas.indexOfFirst { it.id == st.personaId }
        b.spPersona.setSelection(if (pIdx >= 0) pIdx + 1 else 0)

        b.worldList.removeAllViews()
        Store.state.worldBooks.forEach { w ->
            val cb = CheckBox(this)
            cb.text = w.name
            cb.setTextColor(getColor(R.color.ink))
            cb.isChecked = w.id in st.worldBookIds
            cb.setOnCheckedChangeListener { _, on ->
                if (on) { if (w.id !in st.worldBookIds) st.worldBookIds.add(w.id) }
                else st.worldBookIds.remove(w.id)
            }
            b.worldList.addView(cb)
        }
        b.castList.removeAllViews()
        val parts = Store.state.participants.filter { it.storyId == id }
        if (parts.isEmpty()) {
            val emptyTv = TextView(this)
            emptyTv.text = Store.t("noMainOptional")
            emptyTv.setTextColor(getColor(R.color.muted))
            emptyTv.textSize = 13f
            emptyTv.setPadding(0, dp(4), 0, dp(4))
            b.castList.addView(emptyTv)
        } else {
            parts.forEach { p ->
                val c = Store.state.characters.find { it.id == p.characterId }
                val role = when (p.role) {
                    "MAIN_CHARACTER" -> Store.t("roleMain")
                    "COMPANION" -> Store.t("roleCompanion")
                    else -> Store.t("roleNpc")
                }
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val tv = TextView(this)
                tv.text = "${c?.name ?: "?"} · $role"
                tv.setTextColor(getColor(R.color.ink))
                tv.textSize = 14f
                tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val rm = TextView(this)
                rm.text = Store.t("delete")
                rm.setTextColor(getColor(R.color.wine))
                rm.setOnClickListener {
                    Store.state.participants.removeAll { it.id == p.id }
                    Store.persist()
                    bind()
                }
                row.addView(tv); row.addView(rm)
                row.setPadding(0, dp(6), 0, dp(6))
                b.castList.addView(row)
            }
        }
    }

    private fun pickCharacter(role: String) {
        val names = Store.state.characters.map { it.name }.toTypedArray()
        if (names.isEmpty()) return
        AlertDialog.Builder(this)
            .setItems(names) { _, which ->
                val ch = Store.state.characters[which]
                if (role == "MAIN_CHARACTER") {
                    Store.state.participants.filter { it.storyId == id && it.role == "MAIN_CHARACTER" }.forEach { it.role = "COMPANION" }
                }
                val existing = Store.state.participants.find { it.storyId == id && it.characterId == ch.id }
                if (existing != null) {
                    existing.role = role
                    existing.priority = if (role == "MAIN_CHARACTER") 100 else 50
                } else {
                    Store.state.participants.add(StoryParticipant(Store.nid(), id, ch.id, role, true, if (role == "MAIN_CHARACTER") 100 else 50))
                }
                Store.persist()
                bind()
            }
            .show()
    }

    private fun save() {
        val st = current() ?: return
        st.name = b.etName.text.toString().ifBlank { Store.t("newStory") }
        st.description = b.etDesc.text.toString()
        val sel = b.spPersona.selectedItemPosition
        st.personaId = if (sel <= 0) null else Store.state.personas.getOrNull(sel - 1)?.id
        st.updatedAt = Store.now()
        Store.persist()
    }
}
