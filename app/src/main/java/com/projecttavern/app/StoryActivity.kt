package com.projecttavern.app

import android.content.Intent
import android.os.Bundle
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
        b.btnAddCompanion.setOnClickListener { pickCharacter("COMPANION") }
        b.btnAddNpc.setOnClickListener { pickCharacter("NPC") }
        b.btnStart.setOnClickListener {
            save()
            val main = Store.state.participants.find { it.storyId == id && it.role == "MAIN_CHARACTER" }
                ?: Store.state.participants.find { it.storyId == id }
            val cid = main?.characterId ?: return@setOnClickListener
            val conv = Store.startConversation(cid, id) ?: return@setOnClickListener
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
        b.lWorld.text = Store.t("storyWorld")
        b.lCast.text = Store.t("storyCast")
        b.btnAddCompanion.text = Store.t("addCompanion")
        b.btnAddNpc.text = Store.t("addNpc")
        b.btnStart.text = Store.t("startChat")
        b.btnDelete.text = Store.t("delete")
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
        Store.state.participants.filter { it.storyId == id }.forEach { p ->
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
        if (Store.state.participants.none { it.storyId == id && it.role == "MAIN_CHARACTER" }) {
            pickHint()
        }
    }

    private fun pickHint() {
        val tv = TextView(this)
        tv.text = Store.t("roleMain")
        tv.setTextColor(getColor(R.color.candle))
        tv.setPadding(0, dp(8), 0, dp(8))
        tv.setOnClickListener { pickCharacter("MAIN_CHARACTER") }
        b.castList.addView(tv)
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
                Store.state.participants.add(StoryParticipant(Store.nid(), id, ch.id, role, true, if (role == "MAIN_CHARACTER") 100 else 50))
                Store.persist()
                bind()
            }
            .show()
    }

    private fun save() {
        val st = current() ?: return
        st.name = b.etName.text.toString()
        st.description = b.etDesc.text.toString()
        st.updatedAt = Store.now()
        Store.persist()
    }
}
