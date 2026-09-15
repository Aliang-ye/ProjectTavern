package com.projecttavern.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.projecttavern.app.databinding.ActivityEntryTestBinding

class EntryTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: return finish()
        val b = ActivityEntryTestBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.headerTitle.text = Store.t("entryTest")
        b.hint.text = Store.t("entryTestHint")
        b.btnRun.text = Store.t("run")
        b.etContext.setText(if (Store.state.locale == "en") "I am going to the imperial capital." else "我准备前往帝国首都。")
        b.btnBack.setOnClickListener { finish() }
        b.btnRun.setOnClickListener {
            val entries = Store.state.entries.filter { it.worldBookId == id }
            val hits = Engine.matchEntries(b.etContext.text.toString(), entries)
            val w = Store.state.worldBooks.find { it.id == id }
            val lore = w?.let { Engine.lore(it) }.orEmpty()
            b.result.text = buildString {
                if (lore.isNotBlank()) append(lore).append("\n\n")
                if (hits.isEmpty()) append(Store.t("entryTestHint"))
                hits.forEach { e ->
                    append("• ").append(e.name).append(" (p").append(e.priority).append(")\n")
                    append(e.content).append("\n\n")
                }
            }
        }
        b.btnRun.performClick()
    }
}
