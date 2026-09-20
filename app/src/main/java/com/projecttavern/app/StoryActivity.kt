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
    private var isNew: Boolean = false
    private lateinit var draftStory: Story
    private val draftParticipants = mutableListOf<StoryParticipant>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isNew = intent.getBooleanExtra("isNew", false)
        id = intent.getStringExtra("id") ?: Store.nid()

        if (isNew) {
            draftStory = Story(
                id = id,
                name = "",
                createdAt = Store.now(),
                updatedAt = Store.now()
            )
        } else {
            val existing = Store.state.stories.find { it.id == id }
            if (existing == null) {
                finish()
                return
            }
            draftStory = existing.copy(
                worldBookIds = existing.worldBookIds.toMutableList(),
            )
        }

        b = ActivityStoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { askLeave() }
        b.btnSave.setOnClickListener {
            save(commitToStore = true)
            finish()
        }
        b.btnAddMain.setOnClickListener { pickCharacter("MAIN_CHARACTER") }
        b.btnAddCompanion.setOnClickListener { pickCharacter("COMPANION") }
        b.btnAddNpc.setOnClickListener { pickCharacter("NPC") }
        b.btnStart.setOnClickListener {
            save(commitToStore = true)
            val conv = Store.startConversation(null, id, current().personaId) ?: return@setOnClickListener
            startActivity(Intent(this, ChatActivity::class.java).putExtra("id", conv))
        }
        b.btnDelete.setOnClickListener {
            if (isNew) {
                finish()
                return@setOnClickListener
            }
            confirm(this, Store.t("deleteQ")) {
                Store.state.stories.removeAll { it.id == id }
                Store.state.participants.removeAll { it.storyId == id }
                Store.state.conversations.filter { it.storyId == id }.forEach { it.storyId = null }
                Store.persist()
                finish()
            }
        }
        b.tabStory.setOnClickListener { switchTab(0) }
        b.tabCast.setOnClickListener { switchTab(1) }
        b.tabWorlds.setOnClickListener { switchTab(2) }
        setupTouchToHideKeyboard(b.root, this)
        bind()
    }

    private var activeTab = 0

    private fun switchTab(tab: Int) {
        activeTab = tab
        hideKeyboard()
        b.panelStory.visibility = if (tab == 0) View.VISIBLE else View.GONE
        b.panelCast.visibility = if (tab == 1) View.VISIBLE else View.GONE
        b.panelWorlds.visibility = if (tab == 2) View.VISIBLE else View.GONE

        fun styleTab(tv: TextView, on: Boolean) {
            tv.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (on) R.color.on_candle else R.color.ink))
        }

        styleTab(b.tabStory, tab == 0)
        styleTab(b.tabCast, tab == 1)
        styleTab(b.tabWorlds, tab == 2)
        b.scrollContainer.smoothScrollTo(0, 0)
    }

    private fun current(): Story = draftStory

    private fun bind(keepForm: Boolean = false) {
        if (keepForm) applyFormToDraft()
        val st = current()
        b.headerTitle.text = if (isNew) Store.t("newStoryTitle") else st.name.ifBlank { Store.t("newStory") }
        b.btnSave.text = if (isNew) Store.t("create") else Store.t("save")
        b.btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE

        b.tabStory.text = Store.t("tabStory")
        b.tabCast.text = Store.t("tabCast")
        b.tabWorlds.text = Store.t("tabWorlds")
        b.lName.text = Store.t("name")
        if (!keepForm) b.etName.setText(st.name)
        b.lDesc.text = Store.t("storyDesc")
        if (!keepForm) b.etDesc.setText(st.description)
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
        val parts = if (isNew) draftParticipants else Store.state.participants.filter { it.storyId == id }
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
                    if (isNew) {
                        draftParticipants.removeAll { it.id == p.id }
                    } else {
                        Store.state.participants.removeAll { it.id == p.id }
                        Store.persist()
                    }
                    bind(keepForm = true)
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
                val participants = if (isNew) draftParticipants else Store.state.participants
                if (role == "MAIN_CHARACTER") {
                    participants.filter { it.storyId == id && it.role == "MAIN_CHARACTER" }.forEach { it.role = "COMPANION" }
                }
                val existing = participants.find { it.storyId == id && it.characterId == ch.id }
                if (existing != null) {
                    existing.role = role
                    existing.priority = if (role == "MAIN_CHARACTER") 100 else 50
                } else {
                    participants.add(StoryParticipant(Store.nid(), id, ch.id, role, true, if (role == "MAIN_CHARACTER") 100 else 50))
                }
                if (!isNew) Store.persist()
                bind(keepForm = true)
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

    private fun applyFormToDraft() {
        val st = current()
        st.name = b.etName.text.toString().ifBlank { Store.t("newStory") }
        st.description = b.etDesc.text.toString()
        val sel = b.spPersona.selectedItemPosition
        st.personaId = if (sel <= 0) null else Store.state.personas.getOrNull(sel - 1)?.id
        st.updatedAt = Store.now()
    }

    private fun isDirty(): Boolean {
        if (isNew) {
            return current().name != Store.t("newStory") ||
                current().description.isNotBlank() ||
                draftParticipants.isNotEmpty() ||
                current().worldBookIds.isNotEmpty()
        }
        val orig = Store.state.stories.find { it.id == id } ?: return true
        val st = current()
        return orig.name != st.name ||
            orig.description != st.description ||
            orig.personaId != st.personaId ||
            orig.worldBookIds != st.worldBookIds
    }

    private fun save(commitToStore: Boolean = false) {
        applyFormToDraft()
        val st = current()
        if (commitToStore) {
            val idx = Store.state.stories.indexOfFirst { it.id == st.id }
            if (idx >= 0) Store.state.stories[idx] = st
            else Store.state.stories.add(0, st)
            draftParticipants.forEach { p ->
                if (Store.state.participants.none { it.id == p.id }) Store.state.participants.add(p)
            }
            isNew = false
            Store.persist()
        }
    }
}
