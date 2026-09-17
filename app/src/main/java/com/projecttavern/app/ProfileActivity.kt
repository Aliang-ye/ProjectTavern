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
        b.chipDeepseek.setOnClickListener {
            setProvider("openai")
            b.etEndpoint.setText("https://api.deepseek.com/v1")
            b.etModel.setText("deepseek-chat")
            if (b.etName.text.isNullOrBlank() || b.etName.text.toString().startsWith("Profile")) {
                b.etName.setText("DeepSeek")
            }
        }
        b.chipSilicon.setOnClickListener {
            setProvider("openai")
            b.etEndpoint.setText("https://api.siliconflow.cn/v1")
            b.etModel.setText("deepseek-ai/DeepSeek-V3")
            if (b.etName.text.isNullOrBlank() || b.etName.text.toString().startsWith("Profile")) {
                b.etName.setText("SiliconFlow")
            }
        }
        b.chipZhipu.setOnClickListener {
            setProvider("openai")
            b.etEndpoint.setText("https://open.bigmodel.cn/api/paas/v4")
            b.etModel.setText("glm-4-flash")
            if (b.etName.text.isNullOrBlank() || b.etName.text.toString().startsWith("Profile")) {
                b.etName.setText("智谱 GLM")
            }
        }
        b.chipOllama.setOnClickListener {
            setProvider("openai")
            b.etEndpoint.setText("http://10.0.2.2:11434/v1")
            b.etModel.setText("qwen2.5:7b")
            if (b.etName.text.isNullOrBlank() || b.etName.text.toString().startsWith("Profile")) {
                b.etName.setText("Ollama")
            }
        }
        b.chipOpenai.setOnClickListener {
            setProvider("openai")
            b.etEndpoint.setText("https://api.openai.com/v1")
            b.etModel.setText("gpt-4o-mini")
            if (b.etName.text.isNullOrBlank() || b.etName.text.toString().startsWith("Profile")) {
                b.etName.setText("OpenAI")
            }
        }
        b.btnPasteKey.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.replace("\r", "")?.replace("\n", "")?.trim()
            if (!text.isNullOrBlank()) {
                b.etKey.setText(text)
                android.widget.Toast.makeText(this, Store.t("keyPasted"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        setupTouchToHideKeyboard(b.root, this)
        b.btnTest.setOnClickListener {
            val endpoint = b.etEndpoint.text.toString().trim().ifBlank {
                if (provider == "claude") "https://api.anthropic.com" else "https://api.openai.com/v1"
            }
            val model = b.etModel.text.toString().trim().ifBlank {
                if (provider == "claude") "claude-sonnet-4-5" else "gpt-4o-mini"
            }
            val key = b.etKey.text.toString().replace("\r", "").replace("\n", "").trim()
            val tempProfile = ApiProfile(
                id = id,
                name = b.etName.text.toString(),
                provider = provider,
                endpoint = endpoint,
                model = model,
                apiKey = key
            )
            b.btnTest.isEnabled = false
            b.btnTest.text = Store.t("testing")
            b.tvTestResult.visibility = android.view.View.VISIBLE
            b.tvTestResult.setTextColor(getColor(R.color.muted))
            b.tvTestResult.text = Store.t("testing")
            kotlin.concurrent.thread {
                val (ok, msg) = Llm.testConnection(tempProfile)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    b.btnTest.isEnabled = true
                    b.btnTest.text = Store.t("testConnection")
                    b.tvTestResult.text = msg
                    b.tvTestResult.setTextColor(getColor(if (ok) R.color.candle else R.color.wine))
                }
            }
        }
        bind(p)
    }

    private fun bind(p: ApiProfile) {
        b.headerTitle.text = Store.t("apiProfiles")
        b.btnSave.text = Store.t("save")
        b.btnTest.text = Store.t("testConnection")
        b.lPresetProviders.text = Store.t("presetProviders")
        b.btnPasteKey.text = Store.t("pasteKey")
        b.lName.text = Store.t("name"); b.etName.setText(p.name)
        b.lProvider.text = if (Store.state.locale == "en") "Provider" else "提供商"
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
        p.name = b.etName.text.toString().trim().ifBlank { if (provider == "claude") "Claude" else "OpenAI" }
        p.provider = provider
        p.endpoint = b.etEndpoint.text.toString().trim().ifBlank {
            if (provider == "claude") "https://api.anthropic.com" else "https://api.openai.com/v1"
        }
        p.model = b.etModel.text.toString().trim().ifBlank {
            if (provider == "claude") "claude-sonnet-4-5" else "gpt-4o-mini"
        }
        p.apiKey = b.etKey.text.toString().replace("\r", "").replace("\n", "").trim()
        Store.state.activeProfileId = p.id
        Store.persist()
    }
}
