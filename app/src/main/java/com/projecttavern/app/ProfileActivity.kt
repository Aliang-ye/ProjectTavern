package com.projecttavern.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.projecttavern.app.databinding.ActivityProfileBinding

class ProfileActivity : AppCompatActivity() {
    private lateinit var b: ActivityProfileBinding
    private lateinit var id: String
    private var provider = "openai"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("id") ?: return finish()
        b = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(b.root)
        val p = Store.state.profiles.find { it.id == id } ?: return finish()
        provider = if (p.provider == "claude") "claude" else "openai"
        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { save(); finish() }
        b.btnOpenai.setOnClickListener { setProvider("openai") }
        b.btnClaude.setOnClickListener { setProvider("claude") }
        b.btnDelete.setOnClickListener {
            confirm(this, Store.t("deleteQ")) {
                Store.state.profiles.removeAll { it.id == id }
                if (Store.state.activeProfileId == id) Store.state.activeProfileId = Store.state.profiles.firstOrNull()?.id
                Store.persist()
                finish()
            }
        }
        bind(p)
    }

    private fun bind(p: ApiProfile) {
        b.headerTitle.text = Store.t("apiProfiles")
        b.btnSave.text = Store.t("save")
        b.lName.text = Store.t("name"); b.etName.setText(p.name)
        b.lProvider.text = Store.t("apiProfiles")
        b.btnOpenai.text = Store.t("providerOpenai")
        b.btnClaude.text = Store.t("providerClaude")
        b.lEndpoint.text = Store.t("endpoint"); b.etEndpoint.setText(p.endpoint)
        b.lModel.text = Store.t("model"); b.etModel.setText(p.model)
        b.lKey.text = Store.t("apiKey"); b.etKey.setText(p.apiKey)
        b.btnDelete.text = Store.t("delete")
        paintProvider()
    }

    private fun setProvider(kind: String) {
        provider = kind
        if (kind == "claude") {
            if (b.etEndpoint.text.toString().contains("openai") || b.etEndpoint.text.isNullOrBlank()) {
                b.etEndpoint.setText("https://api.anthropic.com")
            }
            if (!b.etModel.text.toString().startsWith("claude")) b.etModel.setText("claude-sonnet-4-5")
        } else {
            if (b.etEndpoint.text.toString().contains("anthropic") || b.etEndpoint.text.isNullOrBlank()) {
                b.etEndpoint.setText("https://api.openai.com/v1")
            }
            if (b.etModel.text.toString().startsWith("claude")) b.etModel.setText("gpt-4o-mini")
        }
        paintProvider()
    }

    private fun paintProvider() {
        val onO = provider != "claude"
        b.btnOpenai.setBackgroundResource(if (onO) R.drawable.bg_chip_on else R.drawable.bg_chip)
        b.btnClaude.setBackgroundResource(if (!onO) R.drawable.bg_chip_on else R.drawable.bg_chip)
        b.btnOpenai.setTextColor(getColor(if (onO) R.color.on_candle else R.color.ink))
        b.btnClaude.setTextColor(getColor(if (!onO) R.color.on_candle else R.color.ink))
        b.providerHint.text = if (onO) Store.t("openaiHint") else Store.t("claudeHint")
    }

    private fun save() {
        val p = Store.state.profiles.find { it.id == id } ?: return
        p.name = b.etName.text.toString()
        p.provider = provider
        p.endpoint = b.etEndpoint.text.toString()
        p.model = b.etModel.text.toString()
        p.apiKey = b.etKey.text.toString()
        Store.state.activeProfileId = p.id
        Store.persist()
    }
}
