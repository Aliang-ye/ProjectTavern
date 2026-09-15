package com.projecttavern.app

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.projecttavern.app.databinding.ActivityWorldBinding

class WorldActivity : AppCompatActivity() {
    private lateinit var b: ActivityWorldBinding
    private lateinit var id: String
    private var section = "description"
    private val keys = listOf("description", "geography", "history", "institutions", "culture", "personalNotes")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("id") ?: return finish()
        b = ActivityWorldBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { save(); finish() }
        b.btnTest.setOnClickListener {
            save()
            startActivity(Intent(this, EntryTestActivity::class.java).putExtra("id", id))
        }
        b.btnAddEntry.setOnClickListener { editEntry(null) }
        b.btnDelete.setOnClickListener {
            confirm(this, Store.t("deleteQ")) {
                Store.state.worldBooks.removeAll { it.id == id }
                Store.state.entries.removeAll { it.worldBookId == id }
                Store.persist()
                finish()
            }
        }
        b.etSection.addTextChangedListener(SimpleWatcher { writeSection() })
        bind()
    }

    private fun current() = Store.state.worldBooks.find { it.id == id }

    private fun bind() {
        val w = current() ?: return finish()
        b.headerTitle.text = w.name
        b.btnSave.text = Store.t("save")
        b.btnTest.text = Store.t("entryTest")
        b.lName.text = Store.t("name")
        b.etName.setText(w.name)
        b.sectionsHint.text = Store.t("sectionsHint")
        b.lEntries.text = "${Store.t("entries")} ${Store.state.entries.count { it.worldBookId == id }}"
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
        val w = current() ?: return
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
        val w = current() ?: return
        b.etSection.setText(field(w))
    }

    private fun paintEntries() {
        b.entryList.removeAllViews()
        Store.state.entries.filter { it.worldBookId == id }.sortedByDescending { it.priority }.forEach { e ->
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
                if (Store.state.entries.none { it.id == e.id }) Store.state.entries.add(e)
                Store.persist()
                bind()
            }
            .setNegativeButton(Store.t("cancel"), null)
            .setNeutralButton(Store.t("delete")) { _, _ ->
                Store.state.entries.removeAll { it.id == e.id }
                Store.persist()
                bind()
            }
            .show()
    }

    private fun save() {
        val w = current() ?: return
        writeSection()
        w.name = b.etName.text.toString()
        w.updatedAt = Store.now()
        Store.persist()
    }
}
