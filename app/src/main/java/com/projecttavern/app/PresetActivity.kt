package com.projecttavern.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PresetActivity : AppCompatActivity() {

    private lateinit var presetList: LinearLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageHint: TextView
    private lateinit var btnAddPreset: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preset)
        setupTouchToHideKeyboard(findViewById(android.R.id.content), this)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        pageTitle = findViewById(R.id.pageTitle)
        pageHint = findViewById(R.id.pageHint)
        btnAddPreset = findViewById(R.id.btnAddPreset)
        presetList = findViewById(R.id.presetList)

        pageTitle.text = Store.t("presetManagement")
        pageHint.text = Store.t("presetManagementHint")
        btnAddPreset.text = "+ ${Store.t("addPreset")}"

        btnAddPreset.setOnClickListener {
            val isEn = Store.state.locale == "en"
            val newP = Preset(
                id = Store.nid(),
                name = if (isEn) "New Mode" else "新生成模式",
                locale = Store.state.locale,
                temperature = 0.8,
                topP = 0.95,
                maxTokens = 300,
                contextLimit = 32000,
                responseBudget = 300,
                systemPrompt = if (isEn) "Vividly advance the story. Never act for {{user}}." else "自然生动地推进剧情，描写动作与环境。不替{{user}}行动。"
            )
            Store.state.presets.add(newP)
            Store.persist()
            renderPresets()
        }

        renderPresets()
    }

    private fun renderPresets() {
        presetList.removeAllViews()
        val presets = Store.localePresets().ifEmpty { Store.state.presets }
        presets.forEach { p ->
            inflatePresetRow(presetList, p, presets.size)
        }
    }

    private fun inflatePresetRow(parent: LinearLayout, p: Preset, totalCount: Int) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_preset, parent, false)
        val etName = v.findViewById<EditText>(R.id.etName)
        val etTemp = v.findViewById<EditText>(R.id.etTemp)
        val etTopP = v.findViewById<EditText>(R.id.etTopP)
        val etMax = v.findViewById<EditText>(R.id.etMax)
        val etContext = v.findViewById<EditText>(R.id.etContext)
        val etBudget = v.findViewById<EditText>(R.id.etBudget)
        val etPrompt = v.findViewById<EditText>(R.id.etPrompt)
        val btnDelete = v.findViewById<TextView>(R.id.btnDelete)

        v.findViewById<TextView>(R.id.lContext).text = Store.t("contextLimit")
        v.findViewById<TextView>(R.id.lBudget).text = Store.t("responseBudget")
        btnDelete.text = Store.t("delete")

        etName.setText(p.name)
        etTemp.setText(p.temperature.toString())
        etTopP.setText(p.topP.toString())
        etMax.setText(p.maxTokens.toString())
        etContext.setText(p.contextLimit.toString())
        etBudget.setText(p.responseBudget.toString())
        etPrompt.setText(p.systemPrompt)

        fun save() {
            p.name = etName.text.toString()
            p.temperature = etTemp.text.toString().toDoubleOrNull() ?: p.temperature
            p.topP = etTopP.text.toString().toDoubleOrNull() ?: p.topP
            p.maxTokens = etMax.text.toString().toIntOrNull() ?: p.maxTokens
            p.contextLimit = etContext.text.toString().toIntOrNull() ?: p.contextLimit
            p.responseBudget = etBudget.text.toString().toIntOrNull() ?: p.responseBudget
            p.systemPrompt = etPrompt.text.toString()
            Store.persist()
        }

        val w = SimpleWatcher { save() }
        etName.addTextChangedListener(w)
        etTemp.addTextChangedListener(w)
        etTopP.addTextChangedListener(w)
        etMax.addTextChangedListener(w)
        etContext.addTextChangedListener(w)
        etBudget.addTextChangedListener(w)
        etPrompt.addTextChangedListener(w)

        btnDelete.setOnClickListener {
            if (totalCount <= 1) {
                Toast.makeText(this, if (Store.state.locale == "en") "At least one preset must be retained." else "必须至少保留一个生成模式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirm(this, Store.t("deleteQ")) {
                Store.state.presets.removeAll { it.id == p.id }
                val fallback = Store.localePresets().firstOrNull()?.id ?: Store.state.presets.firstOrNull()?.id.orEmpty()
                Store.state.conversations.forEach { if (it.presetId == p.id) it.presetId = fallback }
                Store.persist()
                renderPresets()
            }
        }
        parent.addView(v)
    }
}
