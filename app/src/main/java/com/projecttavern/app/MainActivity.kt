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
import com.projecttavern.app.databinding.ActivityMainBinding
import com.projecttavern.app.databinding.PanelListBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val import =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            kotlin.concurrent.thread {
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@thread
                    if (Cards.importKind(json) != "backup") {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) Toast.makeText(this, Store.t("backupInvalid"), Toast.LENGTH_SHORT).show()
                        }
                        return@thread
                    }
                    val next = Store.gson.fromJson(json, TavernState::class.java)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (next == null) {
                            Toast.makeText(this, Store.t("backupInvalid"), Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        confirm(this, Store.t("restoreBackupQ"), Store.t("restore")) {
                            Store.replace(next)
                            refresh()
                            Toast.makeText(this, Store.t("backupRestored"), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this, e.message ?: Store.t("backupInvalid"), Toast.LENGTH_LONG).show()
                    }
                }
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
            openScreen(CharacterActivity::class.java) { it.putExtra("isNew", true) }
        }
        b.panelCharacters.fab.setOnLongClickListener {
            showImportCharacterDialog()
            true
        }
        b.panelWorlds.fab.setOnClickListener {
            openScreen(WorldActivity::class.java) { it.putExtra("isNew", true) }
        }
        b.panelStories.fab.setOnClickListener {
            openScreen(StoryActivity::class.java) { it.putExtra("isNew", true) }
        }
        b.panelChats.fab.visibility = View.VISIBLE
        b.panelChats.fab.setOnClickListener { pickNewChat() }

        // 顶部统计胶囊点击直接切换至对应面板
        b.statCharacters.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_characters }
        b.statWorlds.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_worlds }
        b.statStories.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_stories }
        b.statChats.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_chats }

        val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var searchCharRunnable: Runnable? = null
        b.panelCharacters.search.addTextChangedListener(SimpleWatcher {
            searchCharRunnable?.let { searchHandler.removeCallbacks(it) }
            val r = Runnable { refreshCharacters() }
            searchCharRunnable = r
            searchHandler.postDelayed(r, 150)
        })
        var searchWorldRunnable: Runnable? = null
        b.panelWorlds.search.addTextChangedListener(SimpleWatcher {
            searchWorldRunnable?.let { searchHandler.removeCallbacks(it) }
            val r = Runnable { refreshWorlds() }
            searchWorldRunnable = r
            searchHandler.postDelayed(r, 150)
        })
        var searchStoryRunnable: Runnable? = null
        b.panelStories.search.addTextChangedListener(SimpleWatcher {
            searchStoryRunnable?.let { searchHandler.removeCallbacks(it) }
            val r = Runnable { refreshStories() }
            searchStoryRunnable = r
            searchHandler.postDelayed(r, 150)
        })
        var searchChatRunnable: Runnable? = null
        b.panelChats.search.addTextChangedListener(SimpleWatcher {
            searchChatRunnable?.let { searchHandler.removeCallbacks(it) }
            val r = Runnable { refreshChats() }
            searchChatRunnable = r
            searchHandler.postDelayed(r, 150)
        })
        wireSettings()
        show(R.id.nav_characters)
        maybeShowSetup()
        applyOpenSettings(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenSettings(intent)
    }

    private fun applyOpenSettings(intent: Intent?) {
        if (intent?.getBooleanExtra("openSettings", false) == true) {
            b.bottomNav.selectedItemId = R.id.nav_settings
        }
    }

    private val importAny = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        kotlin.concurrent.thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@thread
                val result = Cards.importBytes(this, bytes)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when {
                        result.character != null -> {
                            refresh()
                            Toast.makeText(this, "${Store.t("characterImported")}: ${result.character.name}", Toast.LENGTH_SHORT).show()
                        }
                        result.world != null -> {
                            refresh()
                            Toast.makeText(this, "${Store.t("worldImported")}: ${result.world.name}", Toast.LENGTH_SHORT).show()
                        }
                        else -> Toast.makeText(this, Store.t("invalidCharacterJson"), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: Store.t("invalidCharacterJson"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun maybeShowSetup() {
        if (Store.state.setupDone) return
        if (Store.activeProfile()?.apiKey?.isNotBlank() == true) {
            Store.state.setupDone = true
            Store.persist()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("setupTitle"))
            .setMessage(Store.t("setupBody"))
            .setPositiveButton(Store.t("setupGo")) { _, _ ->
                b.bottomNav.selectedItemId = R.id.nav_settings
                val p = Store.state.profiles.firstOrNull() ?: ApiProfile(
                    Store.nid(),
                    if (Store.state.locale == "en") "OpenAI" else "OpenAI 兼容",
                    "openai",
                    "https://api.openai.com/v1",
                    "gpt-4o-mini",
                    ""
                ).also {
                    Store.state.profiles.add(it)
                    Store.state.activeProfileId = it.id
                    Store.persist()
                }
                openScreen(ProfileActivity::class.java) { it.putExtra("id", p.id) }
            }
            .setNegativeButton(Store.t("setupSkip")) { _, _ ->
                Store.state.setupDone = true
                Store.persist()
            }
            .setCancelable(false)
            .show()
    }

    private fun offerCharacterChat(characterId: String) {
        val last = Store.lastPrivateChat(characterId)
        if (last == null) {
            val id = Store.startConversation(characterId, null) ?: return
            openScreen(ChatActivity::class.java) { it.putExtra("id", id) }
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(arrayOf(Store.t("continueLastChat"), Store.t("startNewChat"))) { _, which ->
                val id = if (which == 0) last.id else Store.startConversation(characterId, null)
                if (id != null) openScreen(ChatActivity::class.java) { it.putExtra("id", id) }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun offerWorldChat(worldBookId: String) {
        val last = Store.lastWorldWillChat(worldBookId)
        if (last == null) {
            val convId = Store.startWorldWillConversation(worldBookId) ?: return
            openScreen(ChatActivity::class.java) { it.putExtra("id", convId) }
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(arrayOf(Store.t("continueLastChat"), Store.t("startNewChat"))) { _, which ->
                val convId = if (which == 0) last.id else Store.startWorldWillConversation(worldBookId)
                if (convId != null) openScreen(ChatActivity::class.java) { it.putExtra("id", convId) }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun pickNewChat() {
        val kinds = mutableListOf(
            Store.t("characters") to { pickCharacterForChat() },
            Store.t("worldWill") to { pickWorldForChat() },
            Store.t("stories") to { pickStoryForChat() },
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("pickChatKind"))
            .setItems(kinds.map { it.first }.toTypedArray()) { _, which ->
                kinds[which].second()
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun pickCharacterForChat() {
        val chars = Store.state.characters
        if (chars.isEmpty()) {
            Toast.makeText(this, Store.t("emptyCharacters"), Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("pickCharacter"))
            .setItems(chars.map { it.name }.toTypedArray()) { _, which ->
                offerCharacterChat(chars[which].id)
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun pickWorldForChat() {
        val worlds = Store.state.worldBooks
        if (worlds.isEmpty()) {
            Toast.makeText(this, Store.t("emptyWorlds"), Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("pickWorld"))
            .setItems(worlds.map { it.name }.toTypedArray()) { _, which ->
                offerWorldChat(worlds[which].id)
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun pickStoryForChat() {
        val stories = Store.state.stories
        if (stories.isEmpty()) {
            Toast.makeText(this, Store.t("emptyStories"), Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Store.t("pickStory"))
            .setItems(stories.map { it.name }.toTypedArray()) { _, which ->
                offerStoryChat(stories[which].id)
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private fun offerStoryChat(storyId: String) {
        val last = Store.lastStoryChat(storyId)
        if (last == null) {
            val id = Store.startConversation(null, storyId) ?: return
            openScreen(ChatActivity::class.java) { it.putExtra("id", id) }
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(arrayOf(Store.t("continueLastChat"), Store.t("startNewChat"))) { _, which ->
                val id = if (which == 0) last.id else Store.startConversation(null, storyId)
                if (id != null) openScreen(ChatActivity::class.java) { it.putExtra("id", id) }
            }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private var currentNavId: Int = R.id.nav_characters

    override fun onResume() {
        super.onResume()
        refreshHeaderAndNav()
        show(currentNavId)
    }

    private fun show(id: Int) {
        currentNavId = id
        b.panelCharacters.root.visibility = goneIf(id != R.id.nav_characters)
        b.panelWorlds.root.visibility = goneIf(id != R.id.nav_worlds)
        b.panelStories.root.visibility = goneIf(id != R.id.nav_stories)
        b.panelChats.root.visibility = goneIf(id != R.id.nav_chats)
        b.panelSettings.root.visibility = goneIf(id != R.id.nav_settings)
        // 按需刷新：只刷新当前激活的面板，减少不必要的全量重绘
        when (id) {
            R.id.nav_characters -> refreshCharacters()
            R.id.nav_worlds -> refreshWorlds()
            R.id.nav_stories -> refreshStories()
            R.id.nav_chats -> refreshChats()
            R.id.nav_settings -> refreshSettings()
        }
    }

    private fun goneIf(hide: Boolean) = if (hide) View.GONE else View.VISIBLE

    private fun refresh() {
        refreshHeaderAndNav()
        show(currentNavId)
    }

    private fun refreshHeaderAndNav() {
        val loc = Store.state.locale
        b.brand.text = if (loc == "en") "TAVERN" else "暮色酒馆"
        b.tagline.text = Store.t("tagline")
        b.localeBadge.text = if (loc == "en") "EN" else "中文"
        b.bottomNav.menu.findItem(R.id.nav_characters).title = Store.t("navCharacters")
        b.bottomNav.menu.findItem(R.id.nav_worlds).title = Store.t("navWorlds")
        b.bottomNav.menu.findItem(R.id.nav_stories).title = Store.t("navStories")
        b.bottomNav.menu.findItem(R.id.nav_chats).title = Store.t("navChats")
        b.bottomNav.menu.findItem(R.id.nav_settings).title = Store.t("navSettings")
        val charLabel = if (loc == "en") "Chars" else "角色"
        val worldLabel = if (loc == "en") "Worlds" else "世界"
        val storyLabel = if (loc == "en") "Stories" else "故事"
        val chatLabel = if (loc == "en") "Chats" else "对话"
        b.statCharacters.text = "${Store.state.characters.size} $charLabel"
        b.statWorlds.text = "${Store.state.worldBooks.size} $worldLabel"
        b.statStories.text = "${Store.state.stories.size} $storyLabel"
        b.statChats.text = "${Store.state.conversations.size} $chatLabel"
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
        setupPanel(b.panelChats, Store.t("chats"), Store.t("privateHint"), true)
        b.panelCharacters.search.hint = Store.t("search")
        b.panelWorlds.search.hint = Store.t("search")
        b.panelStories.search.hint = Store.t("search")
        b.panelChats.search.hint = Store.t("searchChats")
    }

    private fun setupPanel(p: PanelListBinding, title: String, hint: String, fab: Boolean) {
        p.panelTitle.text = title
        p.panelHint.text = hint
        p.panelHint.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
        p.search.visibility = View.VISIBLE
        p.fab.visibility = if (fab) View.VISIBLE else View.GONE
        when {
            p === b.panelCharacters -> {
                p.panelAction.visibility = View.VISIBLE
                p.panelAction.text = Store.t("importCharacter")
                p.panelAction.setOnClickListener { showImportCharacterDialog() }
            }
            p === b.panelWorlds -> {
                p.panelAction.visibility = View.VISIBLE
                p.panelAction.text = Store.t("importWorld")
                p.panelAction.setOnClickListener { importAny.launch("*/*") }
            }
            else -> p.panelAction.visibility = View.GONE
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
                kotlin.concurrent.thread {
                    val result = Cards.importJson(this@MainActivity, json)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (result.character != null) {
                            refresh()
                            Toast.makeText(this@MainActivity, "${Store.t("characterImported")}: ${result.character.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, Store.t("invalidCharacterJson"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNeutralButton(Store.t("importFile")) { _, _ -> importAny.launch("*/*") }
            .setNegativeButton(Store.t("cancel"), null)
            .show()
    }

    private val MAX_DISPLAY = 60

    private fun addMaxDisplayNotice(container: LinearLayout, shown: Int, total: Int) {
        if (total > shown) {
            val tv = TextView(this).apply {
                text = "${Store.t("maxDisplayNotice")} ($shown / $total)"
                setTextColor(getColor(R.color.muted))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(16), 0, dp(24))
            }
            container.addView(tv)
        }
    }

    private fun refreshCharacters() {
        val q = b.panelCharacters.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val list = Store.state.characters.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.tags.any { t -> t.lowercase().contains(q) }
        }.sortedWith(compareByDescending<Character> { it.isPinned }.thenByDescending { it.updatedAt })
        val displayed = if (list.size > MAX_DISPLAY) list.take(MAX_DISPLAY) else list
        fill(b.panelCharacters.list) {
            if (displayed.isEmpty()) {
                val tv = TextView(this)
                tv.text = Store.t("emptyCharacters")
                tv.setTextColor(getColor(R.color.muted))
                tv.textSize = 14f
                tv.setPadding(0, dp(24), 0, 0)
                it.addView(tv)
            }
            displayed.forEach { c ->
                val row = inflateSwipeRow(
                    it,
                    title = c.name,
                    subtitle = c.description.ifBlank { Store.t("noDesc") },
                    meta = Store.t("privateChat"),
                    avatarPath = c.avatar,
                    isPinned = c.isPinned,
                    onClick = { openScreen(CharacterActivity::class.java) { intent -> intent.putExtra("id", c.id) } },
                    onPin = {
                        c.isPinned = !c.isPinned
                        Store.persist()
                        refreshCharacters()
                    },
                    onDelete = {
                        confirm(this@MainActivity, Store.t("deleteQ")) {
                            Store.deleteCharacter(c.id)
                            Store.persist()
                            refresh()
                        }
                    }
                )
                row.findViewById<TextView>(R.id.meta).setOnClickListener { _ ->
                    offerCharacterChat(c.id)
                }
            }
            addMaxDisplayNotice(it, displayed.size, list.size)
        }
    }

    private fun refreshWorlds() {
        val q = b.panelWorlds.search.text?.toString()?.trim()?.lowercase() ?: ""
        val worlds = Store.state.worldBooks
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) || it.willName.lowercase().contains(q) }
            .sortedWith(compareByDescending<WorldBook> { it.isPinned }.thenByDescending { it.updatedAt })
        val displayed = if (worlds.size > MAX_DISPLAY) worlds.take(MAX_DISPLAY) else worlds
        fill(b.panelWorlds.list) {
            if (displayed.isEmpty()) {
                val tv = TextView(this)
                tv.text = Store.t("emptyWorlds")
                tv.setTextColor(getColor(R.color.muted))
                tv.textSize = 14f
                tv.setPadding(0, dp(24), 0, 0)
                it.addView(tv)
            }
            displayed.forEach { w ->
                val n = Store.state.entries.count { e -> e.worldBookId == w.id }
                val willName = w.willName.ifBlank { Store.t("worldWill") }
                val row = inflateSwipeRow(
                    it,
                    title = w.name,
                    subtitle = w.description.ifBlank { "$n ${Store.t("entries")} · $willName" },
                    meta = Store.t("chatWithWill"),
                    avatarPath = w.willAvatar,
                    isPinned = w.isPinned,
                    onClick = { openScreen(WorldActivity::class.java) { intent -> intent.putExtra("id", w.id) } },
                    onPin = {
                        w.isPinned = !w.isPinned
                        Store.persist()
                        refreshWorlds()
                    },
                    onDelete = {
                        confirm(this@MainActivity, Store.t("deleteQ")) {
                            Store.deleteWorld(w.id)
                            Store.persist()
                            refresh()
                        }
                    }
                )
                row.findViewById<TextView>(R.id.meta).setOnClickListener { _ ->
                    offerWorldChat(w.id)
                }
            }
            addMaxDisplayNotice(it, displayed.size, worlds.size)
        }
    }

    private fun refreshStories() {
        val q = b.panelStories.search.text?.toString()?.trim()?.lowercase() ?: ""
        val stories = Store.state.stories
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
            .sortedWith(compareByDescending<Story> { it.isPinned }.thenByDescending { it.updatedAt })
        val displayed = if (stories.size > MAX_DISPLAY) stories.take(MAX_DISPLAY) else stories
        fill(b.panelStories.list) {
            if (displayed.isEmpty()) {
                val tv = TextView(this)
                tv.text = Store.t("emptyStories")
                tv.setTextColor(getColor(R.color.muted))
                tv.textSize = 14f
                tv.setPadding(0, dp(24), 0, 0)
                it.addView(tv)
            }
            displayed.forEach { st ->
                val n = Store.state.participants.count { it.storyId == st.id }
                val chats = Store.state.conversations.count { it.storyId == st.id }
                val row = inflateSwipeRow(
                    it,
                    title = st.name,
                    subtitle = st.description.ifBlank { "$n ${Store.t("people")} · $chats ${Store.t("conversations")}" },
                    meta = Store.t("startChat"),
                    isPinned = st.isPinned,
                    onClick = { openScreen(StoryActivity::class.java) { intent -> intent.putExtra("id", st.id) } },
                    onPin = {
                        st.isPinned = !st.isPinned
                        Store.persist()
                        refreshStories()
                    },
                    onDelete = {
                        confirm(this@MainActivity, Store.t("deleteQ")) {
                            Store.state.stories.removeAll { it.id == st.id }
                            Store.state.participants.removeAll { it.storyId == st.id }
                            Store.state.conversations.filter { it.storyId == st.id }.forEach { it.storyId = null }
                            Store.persist()
                            refresh()
                        }
                    }
                )
                row.findViewById<TextView>(R.id.meta).setOnClickListener { _ ->
                    offerStoryChat(st.id)
                }
            }
            addMaxDisplayNotice(it, displayed.size, stories.size)
        }
    }

    private fun refreshChats() {
        val q = b.panelChats.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val allConvs = Store.state.conversations.filter { c ->
            if (q.isEmpty()) true
            else {
                val titleHit = c.title.lowercase().contains(q)
                val bodyHit = Store.state.messages.any { m ->
                    m.conversationId == c.id && (m.content.lowercase().contains(q) || Engine.display(m).lowercase().contains(q))
                }
                titleHit || bodyHit
            }
        }.sortedWith(compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.updatedAt })
        val displayed = if (allConvs.size > MAX_DISPLAY) allConvs.take(MAX_DISPLAY) else allConvs
        fill(b.panelChats.list) {
            if (displayed.isEmpty()) {
                val tv = TextView(this)
                tv.text = Store.t("noChats")
                tv.setTextColor(getColor(R.color.muted))
                tv.textSize = 14f
                tv.setPadding(0, dp(24), 0, 0)
                it.addView(tv)
                return@fill
            }
            displayed.forEach { c ->
                val ch = Store.state.characters.find { it.id == c.characterId }
                val world = if (ch == null && c.worldBookIds.isNotEmpty()) {
                    Store.state.worldBooks.find { it.id in c.worldBookIds }
                } else null
                val story = Store.state.stories.find { s -> s.id == c.storyId }
                // 最后一条消息预览（最多60字，剥离斜体星号）
                val lastMsg = Store.state.messages
                    .filter { it.conversationId == c.id }
                    .maxByOrNull { it.createdAt }
                val lastPreview = if (lastMsg != null) {
                    val text = if (lastMsg.role == "assistant") Engine.display(lastMsg) else lastMsg.content
                    val cleanText = text.replace("*", "").trim()
                    cleanText.take(60).replace('\n', ' ').let { if (cleanText.length > 60) "$it…" else it }
                } else {
                    if (c.storyId == null) {
                        if (world != null) "${world.name} · ${world.willName.ifBlank { Store.t("worldWill") }}" else Store.t("privateChat")
                    } else {
                        story?.name ?: Store.t("stories")
                    }
                }
                val avatar = ch?.avatar ?: world?.willAvatar
                inflateSwipeRow(
                    it,
                    title = c.title,
                    subtitle = lastPreview,
                    meta = Store.t("open"),
                    avatarPath = avatar,
                    isPinned = c.isPinned,
                    onClick = { openScreen(ChatActivity::class.java) { intent -> intent.putExtra("id", c.id) } },
                    onPin = {
                        c.isPinned = !c.isPinned
                        Store.persist()
                        refreshChats()
                    },
                    onDelete = {
                        confirm(this@MainActivity, Store.t("deleteQ")) {
                            Store.deleteConversation(c.id)
                            Store.persist()
                            refreshChats()
                        }
                    }
                )
            }
            addMaxDisplayNotice(it, displayed.size, allConvs.size)
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
        s.cardPresetsEntry.setOnClickListener {
            openScreen(PresetActivity::class.java)
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
        s.btnImport.setOnClickListener { import.launch("*/*") }
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
            val kind = when {
                p.provider == "claude" -> "Claude"
                p.endpoint.contains("generativelanguage.googleapis.com") || p.model.contains("gemini", true) -> "Gemini"
                p.endpoint.contains("11434") -> "Ollama"
                else -> "OpenAI"
            }
            val star = if (p.id == Store.state.activeProfileId) "★" else ""
            val meta = if (star.isBlank()) kind else "$kind $star"
            inflateRow(s.profileList, p.name, "$kind · ${p.model}", meta) {
                Store.state.activeProfileId = p.id
                Store.persist()
                openScreen(ProfileActivity::class.java) { it.putExtra("id", p.id) }
            }
        }
        s.labelPresets.text = Store.t("presetManagement")
        s.presetsHint.text = Store.t("presetManagementHint")
        s.presetCountBadge.text = "${Store.localePresets().size}"
        s.labelChat.text = Store.t("chatOptions")
        s.labelStreaming.text = Store.t("streaming")
        s.valStreaming.text = if (Store.state.streaming) "ON" else "OFF"
        s.labelAutoSummary.text = Store.t("autoSummary")
        s.valAutoSummary.text = if (Store.state.autoSummary) "ON" else "OFF"
        s.labelAdvanced.text = Store.t("advanced")
        s.developerHint.text = Store.t("autoSummaryHint") + "\n" + Store.t("developerHint")
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
