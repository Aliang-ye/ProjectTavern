package com.projecttavern.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.projecttavern.app.databinding.ActivityMainBinding
import com.projecttavern.app.databinding.PanelListBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val gson = Gson()
    private val import =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
                val next = gson.fromJson(json, TavernState::class.java)
                if (next != null) Store.replace(next)
                refresh()
                Toast.makeText(this, if (Store.state.locale == "en") "Backup restored" else "备份已恢复", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, if (Store.state.locale == "en") "Import failed: ${e.message ?: "invalid JSON"}" else "导入失败：${e.message ?: "JSON 格式不正确"}", Toast.LENGTH_LONG).show()
            }
        }

    private val pickPersonaAvatar =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val active = Store.state.personas.find { it.id == Store.state.activePersonaId } ?: Store.state.personas.firstOrNull()
                if (active != null) {
                    val path = saveAvatar(this, active.id, uri)
                    if (path != null) {
                        active.avatar = path
                        active.updatedAt = Store.now()
                        Store.persist()
                        refreshSettings()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        b.panelCharacters.fab.setOnClickListener {
            val defaultGreeting = if (Store.state.locale == "en") "Hello, traveler. What brings you here?" else "你好，旅人。找我有什么事吗？"
            val c = Character(id = Store.nid(), name = Store.t("newCharacter"), firstMessage = defaultGreeting, createdAt = Store.now(), updatedAt = Store.now())
            Store.state.characters.add(0, c)
            Store.persist()
            openScreen(CharacterActivity::class.java) { it.putExtra("id", c.id) }
        }
        b.panelCharacters.fab.setOnLongClickListener {
            showImportCharacterDialog()
            true
        }
        b.panelWorlds.fab.setOnClickListener {
            val w = WorldBook(id = Store.nid(), name = Store.t("newWorld"), createdAt = Store.now(), updatedAt = Store.now())
            Store.state.worldBooks.add(0, w)
            Store.persist()
            openScreen(WorldActivity::class.java) { it.putExtra("id", w.id) }
        }
        b.panelStories.fab.setOnClickListener {
            val st = Story(id = Store.nid(), name = Store.t("newStory"), createdAt = Store.now(), updatedAt = Store.now())
            Store.state.stories.add(0, st)
            Store.persist()
            openScreen(StoryActivity::class.java) { it.putExtra("id", st.id) }
        }
        b.panelChats.fab.visibility = View.GONE
        b.panelCharacters.search.addTextChangedListener(SimpleWatcher { refreshCharacters() })
        wireSettings()
        show(R.id.nav_characters)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun show(id: Int) {
        b.panelCharacters.root.visibility = goneIf(id != R.id.nav_characters)
        b.panelWorlds.root.visibility = goneIf(id != R.id.nav_worlds)
        b.panelStories.root.visibility = goneIf(id != R.id.nav_stories)
        b.panelChats.root.visibility = goneIf(id != R.id.nav_chats)
        b.panelSettings.root.visibility = goneIf(id != R.id.nav_settings)
        refresh()
    }

    private fun goneIf(hide: Boolean) = if (hide) View.GONE else View.VISIBLE

    private fun refresh() {
        val loc = Store.state.locale
        b.brand.text = if (loc == "en") "TAVERN" else "暮色酒馆"
        b.tagline.text = Store.t("tagline")
        b.localeBadge.text = if (loc == "en") "EN" else "中文"
        b.bottomNav.menu.findItem(R.id.nav_characters).title = Store.t("navCharacters")
        b.bottomNav.menu.findItem(R.id.nav_worlds).title = Store.t("navWorlds")
        b.bottomNav.menu.findItem(R.id.nav_stories).title = Store.t("navStories")
        b.bottomNav.menu.findItem(R.id.nav_chats).title = Store.t("navChats")
        b.bottomNav.menu.findItem(R.id.nav_settings).title = Store.t("navSettings")
        b.statCharacters.text = "${Store.state.characters.size}"
        b.statWorlds.text = "${Store.state.worldBooks.size}"
        b.statStories.text = "${Store.state.stories.size}"
        b.statChats.text = "${Store.state.conversations.size}"
        val active = Store.activeProfile()
        val ready = active != null && active.apiKey.isNotBlank()
        b.statusBanner.text = if (loc == "en") {
            if (ready) "Ready • ${Store.state.profiles.size} profiles • API configured" else "Needs setup • ${Store.state.profiles.size} profiles • add an API key"
        } else {
            if (ready) "已就绪 • ${Store.state.profiles.size} 个配置 • API 可用" else "待配置 • ${Store.state.profiles.size} 个配置 • 需要补充 API Key"
        }
        setupPanel(b.panelCharacters, Store.t("characters"), Store.t("privateHint"), true)
        setupPanel(b.panelWorlds, Store.t("worlds"), Store.t("sectionsHint"), true)
        setupPanel(b.panelStories, Store.t("stories"), "", true)
        setupPanel(b.panelChats, Store.t("chats"), Store.t("privateHint"), false)
        b.panelCharacters.search.hint = Store.t("search")
        refreshCharacters()
        refreshWorlds()
        refreshStories()
        refreshChats()
        refreshSettings()
    }

    private fun setupPanel(p: PanelListBinding, title: String, hint: String, fab: Boolean) {
        p.panelTitle.text = title
        p.panelHint.text = hint
        p.panelHint.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
        p.search.visibility = if (p === b.panelCharacters) View.VISIBLE else View.GONE
        p.fab.visibility = if (fab) View.VISIBLE else View.GONE
        if (p === b.panelCharacters) {
            p.panelAction.visibility = View.VISIBLE
            p.panelAction.text = Store.t("importCharacter")
            p.panelAction.setOnClickListener { showImportCharacterDialog() }
        } else {
            p.panelAction.visibility = View.GONE
        }
    }

    private fun showImportCharacterDialog() {
        val et = EditText(this).apply {
            hint = Store.t("pasteCharacterJson")
            minLines = 4
            maxLines = 10
            isVerticalScrollBarEnabled = true
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.ink))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.muted))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("importCharacter"))
            .setView(container)
            .setPositiveButton(Store.t("import")) { _, _ ->
                val json = et.text.toString().trim()
                if (json.isBlank()) return@setPositiveButton
                val ch = Store.importCharacter(json)
                if (ch != null) {
                    refresh()
                    Toast.makeText(this, "${Store.t("characterImported")}: ${ch.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, Store.t("invalidCharacterJson"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun refreshCharacters() {
        val q = b.panelCharacters.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val list = Store.state.characters.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.tags.any { t -> t.lowercase().contains(q) }
        }
        fill(b.panelCharacters.list) {
            list.forEach { c ->
                val row = inflateRow(it, c.name, c.description.ifBlank { Store.t("noDesc") }, Store.t("privateChat"), c.avatar) {
                    openScreen(CharacterActivity::class.java) { it.putExtra("id", c.id) }
                }
                row.findViewById<TextView>(R.id.meta).setOnClickListener { _ ->
                    val id = Store.startConversation(c.id, null) ?: return@setOnClickListener
                    openScreen(ChatActivity::class.java) { it.putExtra("id", id) }
                }
                row.setOnLongClickListener {
                    confirm(this@MainActivity, Store.t("deleteQ")) {
                        Store.state.characters.removeAll { it.id == c.id }
                        Store.state.characterWorldBooks.removeAll { it.characterId == c.id }
                        Store.state.participants.removeAll { it.characterId == c.id }
                        val removedConvs = Store.state.conversations.filter { it.characterId == c.id && it.storyId == null }
                        val removedIds = removedConvs.map { it.id }.toSet()
                        Store.state.messages.removeAll { it.conversationId in removedIds }
                        Store.state.conversations.removeAll { it.id in removedIds }
                        Store.persist()
                        refresh()
                    }
                    true
                }
            }
        }
    }

    private fun refreshWorlds() {
        fill(b.panelWorlds.list) {
            Store.state.worldBooks.forEach { w ->
                val n = Store.state.entries.count { e -> e.worldBookId == w.id }
                val filled = listOf(w.description, w.geography, w.history, w.institutions, w.culture, w.personalNotes).count { s -> s.isNotBlank() }
                val row = inflateRow(it, w.name, w.description.ifBlank { "$n ${Store.t("entries")}" }, "$filled/6") {
                    openScreen(WorldActivity::class.java) { it.putExtra("id", w.id) }
                }
                row.setOnLongClickListener {
                    confirm(this@MainActivity, Store.t("deleteQ")) {
                        Store.state.worldBooks.removeAll { it.id == w.id }
                        Store.state.entries.removeAll { it.worldBookId == w.id }
                        Store.state.characterWorldBooks.removeAll { it.worldBookId == w.id }
                        Store.state.characters.removeAll { it.id == "gm-${w.id}" }
                        Store.state.stories.forEach { it.worldBookIds.remove(w.id) }
                        Store.state.conversations.forEach { it.worldBookIds.remove(w.id) }
                        Store.persist()
                        refresh()
                    }
                    true
                }
            }
        }
    }

    private fun refreshStories() {
        fill(b.panelStories.list) {
            Store.state.stories.forEach { st ->
                val n = Store.state.participants.count { it.storyId == st.id }
                val chats = Store.state.conversations.count { it.storyId == st.id }
                val row = inflateRow(it, st.name, st.description.ifBlank { Store.t("stories") }, "$n ${Store.t("people")} · $chats") {
                    openScreen(StoryActivity::class.java) { it.putExtra("id", st.id) }
                }
                row.setOnLongClickListener {
                    confirm(this@MainActivity, Store.t("deleteQ")) {
                        Store.state.stories.removeAll { it.id == st.id }
                        Store.state.participants.removeAll { it.storyId == st.id }
                        Store.state.conversations.filter { it.storyId == st.id }.forEach { it.storyId = null }
                        Store.persist()
                        refresh()
                    }
                    true
                }
            }
        }
    }

    private fun refreshChats() {
        fill(b.panelChats.list) {
            val priv = Store.state.conversations.filter { it.storyId == null }.sortedByDescending { it.updatedAt }
            val story = Store.state.conversations.filter { it.storyId != null }.sortedByDescending { it.updatedAt }
            if (priv.isEmpty() && story.isEmpty()) {
                val tv = TextView(this)
                tv.text = Store.t("noChats")
                tv.setTextColor(getColor(R.color.muted))
                tv.textSize = 14f
                it.addView(tv)
            }
            priv.forEach { c ->
                val ch = Store.state.characters.find { it.id == c.characterId }
                val row = inflateRow(it, c.title, Store.t("privateChat"), Store.t("open"), ch?.avatar) {
                    openScreen(ChatActivity::class.java) { it.putExtra("id", c.id) }
                }
                row.setOnLongClickListener {
                    confirm(this@MainActivity, Store.t("deleteQ")) {
                        Store.state.messages.removeAll { m -> m.conversationId == c.id }
                        Store.state.conversations.removeAll { conv -> conv.id == c.id }
                        Store.persist()
                        refresh()
                    }
                    true
                }
            }
            story.forEach { c ->
                val ch = Store.state.characters.find { it.id == c.characterId }
                val sn = Store.state.stories.find { s -> s.id == c.storyId }?.name.orEmpty()
                val row = inflateRow(it, c.title, sn, Store.t("open"), ch?.avatar) {
                    openScreen(ChatActivity::class.java) { it.putExtra("id", c.id) }
                }
                row.setOnLongClickListener {
                    confirm(this@MainActivity, Store.t("deleteQ")) {
                        Store.state.messages.removeAll { m -> m.conversationId == c.id }
                        Store.state.conversations.removeAll { conv -> conv.id == c.id }
                        Store.persist()
                        refresh()
                    }
                    true
                }
            }
        }
    }

    private var settingsWired = false

    private fun wireSettings() {
        val s = b.panelSettings
        s.btnZh.setOnClickListener { Store.setLocale("zh"); refresh() }
        s.btnEn.setOnClickListener { Store.setLocale("en"); refresh() }
        s.btnDark.setOnClickListener { Store.setAppearance("dark"); refresh() }
        s.btnLight.setOnClickListener { Store.setAppearance("light"); refresh() }
        s.btnSystem.setOnClickListener { Store.setAppearance("system"); refresh() }
        s.btnNewPersona.setOnClickListener {
            val isEn = Store.state.locale == "en"
            val newP = Persona(
                id = Store.nid(),
                name = if (isEn) "New Persona" else "新角色卡",
                avatar = null,
                description = "",
                createdAt = Store.now(),
                updatedAt = Store.now(),
            )
            Store.state.personas.add(newP)
            Store.state.activePersonaId = newP.id
            Store.state.userName = newP.name
            Store.state.userPersona = newP.description
            Store.persist()
            refreshSettings()
        }
        s.personaAvatarContainer.setOnClickListener { pickPersonaAvatar.launch("image/*") }
        s.personaAvatarContainer.setOnLongClickListener {
            val active = Store.state.personas.find { it.id == Store.state.activePersonaId } ?: Store.state.personas.firstOrNull()
            if (active?.avatar != null) {
                confirm(this, Store.t("deleteQ")) {
                    active.avatar = null
                    active.updatedAt = Store.now()
                    Store.persist()
                    refreshSettings()
                }
            }
            true
        }
        s.btnNewProfile.setOnClickListener {
            val p = ApiProfile(Store.nid(), if (Store.state.locale == "en") "OpenAI" else "OpenAI 兼容", "openai", "https://api.openai.com/v1", "gpt-4o-mini", "")
            Store.state.profiles.add(p)
            Store.state.activeProfileId = p.id
            Store.persist()
            openScreen(ProfileActivity::class.java) { it.putExtra("id", p.id) }
        }
        s.btnNewPreset.setOnClickListener {
            val loc = Store.state.locale
            val p = Preset(Store.nid(), Store.t("newPreset"), loc, 0.9, 0.95, 800, 32000, 800, if (loc == "en") "Keep the character's voice. Never act for the user." else "保持角色声音。不要替用户行动。")
            Store.state.presets.add(p)
            Store.persist()
            refreshSettings()
        }
        s.rowStreaming.setOnClickListener {
            Store.state.streaming = !Store.state.streaming
            Store.persist()
            refreshSettings()
        }
        s.rowAutoSummary.setOnClickListener {
            Store.state.autoSummary = !Store.state.autoSummary
            Store.persist()
            refreshSettings()
        }
        s.rowDeveloper.setOnClickListener {
            Store.state.developerMode = !Store.state.developerMode
            Store.persist()
            refreshSettings()
        }
        s.btnExport.setOnClickListener { exportBackup() }
        s.btnImport.setOnClickListener { import.launch("application/json") }
        s.btnReset.setOnClickListener {
            confirm(this, Store.t("resetSeedQ")) {
                Store.reset()
                refresh()
            }
        }
        if (!settingsWired) {
            s.etUserName.addTextChangedListener(SimpleWatcher {
                val v = s.etUserName.text?.toString().orEmpty()
                if (v != Store.state.userName) {
                    Store.state.userName = v
                    val active = Store.state.personas.find { it.id == Store.state.activePersonaId } ?: Store.state.personas.firstOrNull()
                    if (active != null) {
                        active.name = v
                        active.updatedAt = Store.now()
                    }
                    Store.persist()
                    renderAvatar(s.personaAvatar, s.personaAvatarImg, v, active?.avatar)
                }
            })
            s.etUserPersona.addTextChangedListener(SimpleWatcher {
                val v = s.etUserPersona.text?.toString().orEmpty()
                if (v != Store.state.userPersona) {
                    Store.state.userPersona = v
                    val active = Store.state.personas.find { it.id == Store.state.activePersonaId } ?: Store.state.personas.firstOrNull()
                    if (active != null) {
                        active.description = v
                        active.updatedAt = Store.now()
                    }
                    Store.persist()
                }
            })
            settingsWired = true
        }
    }

    private fun paintChip(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        v.setTextColor(getColor(if (on) R.color.on_candle else R.color.ink))
    }

    private fun refreshSettings() {
        val s = b.panelSettings
        val onZh = Store.state.locale != "en"
        s.titleSettings.text = Store.t("settings")
        s.settingsHint.text = Store.t("settingsHint")
        s.labelAppearance.text = Store.t("appearance")
        s.appearanceHint.text = Store.t("appearanceHint")
        s.btnDark.text = Store.t("dark")
        s.btnLight.text = Store.t("light")
        s.btnSystem.text = Store.t("system")
        paintChip(s.btnDark, Store.state.appearance == "dark")
        paintChip(s.btnLight, Store.state.appearance == "light")
        paintChip(s.btnSystem, Store.state.appearance == "system")
        s.labelLanguage.text = Store.t("language")
        s.languageHint.text = Store.t("languageHint")
        paintChip(s.btnZh, onZh)
        paintChip(s.btnEn, !onZh)

        s.labelPersona.text = Store.t("personas")
        s.personaHint.text = Store.t("personaHint")
        s.btnNewPersona.text = "+ ${Store.t("addPersona")}"
        s.labelUserName.text = Store.t("personaName")
        s.labelUserPersona.text = Store.t("personaBio")

        val activePersona = Store.state.personas.find { it.id == Store.state.activePersonaId } ?: Store.state.personas.firstOrNull()
        if (activePersona != null) {
            if (Store.state.userName.isBlank() || Store.state.userName != activePersona.name) {
                Store.state.userName = activePersona.name
            }
            if (Store.state.userPersona != activePersona.description) {
                Store.state.userPersona = activePersona.description
            }
        }
        renderAvatar(s.personaAvatar, s.personaAvatarImg, activePersona?.name ?: "你", activePersona?.avatar)
        if (s.etUserName.text?.toString() != Store.state.userName) s.etUserName.setText(Store.state.userName)
        if (s.etUserPersona.text?.toString() != Store.state.userPersona) s.etUserPersona.setText(Store.state.userPersona)

        s.personaList.removeAllViews()
        Store.state.personas.forEach { p ->
            val isActive = p.id == Store.state.activePersonaId
            val meta = if (isActive) "★ ${Store.t("activePersona")}" else Store.t("useThisPersona")
            val row = inflateRow(s.personaList, p.name, p.description.ifBlank { Store.t("noDesc") }, meta, p.avatar) {
                Store.state.activePersonaId = p.id
                Store.state.userName = p.name
                Store.state.userPersona = p.description
                Store.persist()
                refreshSettings()
            }
            row.setOnLongClickListener {
                if (Store.state.personas.size > 1) {
                    confirm(this, Store.t("deleteQ")) {
                        Store.state.personas.removeAll { it.id == p.id }
                        Store.state.stories.filter { it.personaId == p.id }.forEach { it.personaId = null }
                        Store.state.conversations.filter { it.personaId == p.id }.forEach { it.personaId = null }
                        if (Store.state.activePersonaId == p.id) {
                            val next = Store.state.personas.firstOrNull()
                            Store.state.activePersonaId = next?.id
                            if (next != null) {
                                Store.state.userName = next.name
                                Store.state.userPersona = next.description
                            }
                        }
                        Store.persist()
                        refreshSettings()
                    }
                }
                true
            }
        }
        s.labelProfiles.text = Store.t("apiProfiles")
        s.btnNewProfile.text = "+ ${Store.t("newProfile")}"
        s.noProfiles.text = Store.t("noProfiles")
        s.noProfiles.visibility = if (Store.state.profiles.isEmpty()) View.VISIBLE else View.GONE
        s.profileList.removeAllViews()
        Store.state.profiles.forEach { p ->
            val kind = if (p.provider == "claude") "Claude" else "OpenAI"
            val star = if (p.id == Store.state.activeProfileId) "★" else ""
            val meta = if (star.isBlank()) kind else "$kind $star"
            inflateRow(s.profileList, p.name, "$kind · ${p.model}", meta) {
                Store.state.activeProfileId = p.id
                Store.persist()
                openScreen(ProfileActivity::class.java) { it.putExtra("id", p.id) }
            }
        }
        s.labelPresets.text = Store.t("presets")
        s.presetsHint.text = Store.t("presetsHint")
        s.btnNewPreset.text = "+ ${Store.t("newPreset")}"
        s.presetList.removeAllViews()
        Store.localePresets().forEach { p -> inflatePreset(s.presetList, p) }
        s.labelChat.text = Store.t("chatOptions")
        s.labelStreaming.text = Store.t("streaming")
        s.valStreaming.text = if (Store.state.streaming) "ON" else "OFF"
        s.labelAutoSummary.text = Store.t("autoSummary")
        s.valAutoSummary.text = if (Store.state.autoSummary) "ON" else "OFF"
        s.labelAdvanced.text = Store.t("advanced")
        s.developerHint.text = Store.t("developerHint")
        s.labelDeveloper.text = Store.t("developerMode")
        s.valDeveloper.text = if (Store.state.developerMode) "ON" else "OFF"
        s.labelData.text = Store.t("data")
        s.btnExport.text = Store.t("exportBackup")
        s.btnImport.text = Store.t("restoreBackup")
        s.btnReset.text = Store.t("resetSeed")
        s.labelAbout.text = Store.t("about")
        s.aboutBody.text = Store.t("aboutBody")
        s.aboutLine.text = Store.t("aboutLine")
    }

    private fun inflatePreset(parent: LinearLayout, p: Preset) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_preset, parent, false)
        val etName = v.findViewById<EditText>(R.id.etName)
        val etTemp = v.findViewById<EditText>(R.id.etTemp)
        val etTopP = v.findViewById<EditText>(R.id.etTopP)
        val etMax = v.findViewById<EditText>(R.id.etMax)
        val etContext = v.findViewById<EditText>(R.id.etContext)
        val etBudget = v.findViewById<EditText>(R.id.etBudget)
        val etPrompt = v.findViewById<EditText>(R.id.etPrompt)
        v.findViewById<TextView>(R.id.lContext).text = Store.t("contextLimit")
        v.findViewById<TextView>(R.id.lBudget).text = Store.t("responseBudget")
        v.findViewById<TextView>(R.id.btnDelete).text = Store.t("delete")
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
        v.findViewById<TextView>(R.id.btnDelete).setOnClickListener {
            confirm(this, Store.t("deleteQ")) {
                Store.state.presets.removeAll { it.id == p.id }
                val fallback = Store.localePresets().firstOrNull()?.id ?: Store.state.presets.firstOrNull()?.id.orEmpty()
                Store.state.conversations.forEach { if (it.presetId == p.id) it.presetId = fallback }
                Store.persist()
                refreshSettings()
            }
        }
        parent.addView(v)
    }

    private fun exportBackup() {
        val f = File(cacheDir, "tavern-backup.json")
        f.writeText(Store.backupJson())
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, Store.t("exportBackup")))
    }

    private fun fill(list: LinearLayout, block: (LinearLayout) -> Unit) {
        list.removeAllViews()
        block(list)
    }
}

class SimpleWatcher(val after: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) { after() }
}
