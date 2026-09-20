package com.projecttavern.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

object Store {
    private const val FILE = "tavern.json"
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    lateinit var state: TavernState
        private set
    private lateinit var file: File
    private val listeners = mutableListOf<() -> Unit>()
    lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        file = File(ctx.filesDir, FILE)
        val raw = if (file.exists()) file.readText() else ""
        state = if (raw.isNotBlank()) {
            try {
                gson.fromJson(raw, TavernState::class.java) ?: seed()
            } catch (_: Exception) {
                // 解析失败时备份损坏文件，而非直接清空用户数据
                try {
                    val bak = File(ctx.filesDir, "tavern.json.bak.${System.currentTimeMillis()}")
                    file.copyTo(bak, overwrite = true)
                } catch (_: Exception) {}
                seed()
            }
        } else seed()
        if (!raw.contains("\"userName\"")) {
            state.userName = if (state.locale == "en") "You" else "你"
            if (state.userPersona.isNullOrBlank()) state.userPersona = "一个走进暮色酒馆的旅人。话不多，观察入微。"
            state.streaming = true
            state.appearance = "dark"
        }
        migrate()
        hydrateKeys()
        persist()
    }

    fun t(key: String) = I18n.t(state.locale, key)

    fun nid() = UUID.randomUUID().toString()
    fun now() = System.currentTimeMillis()

    fun listen(cb: () -> Unit): () -> Unit {
        listeners.add(cb)
        return { listeners.remove(cb) }
    }

    private fun redactedStateForWrite(): TavernState {
        val copy = gson.fromJson(gson.toJson(state), TavernState::class.java)
        copy.profiles.forEach { it.apiKey = "" }
        return copy
    }

    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val stateLock = Any()

    fun <T> locked(block: () -> T): T = synchronized(stateLock, block)

    internal fun attachStateForTests(next: TavernState) {
        state = next
    }

    fun persist() {
        if (!::file.isInitialized) return
        val snapshot = locked {
            state.copy(
                characters = ArrayList(state.characters),
                characterWorldBooks = ArrayList(state.characterWorldBooks),
                worldBooks = ArrayList(state.worldBooks),
                entries = ArrayList(state.entries),
                stories = ArrayList(state.stories),
                participants = ArrayList(state.participants),
                conversations = ArrayList(state.conversations.map { it.copy(worldBookIds = ArrayList(it.worldBookIds)) }),
                messages = ArrayList(state.messages.map { m ->
                    m.copy(generations = ArrayList(m.generations.map { g -> g.copy() }))
                }),
                presets = ArrayList(state.presets),
                profiles = ArrayList(state.profiles.map { it.copy() }),
                personas = ArrayList(state.personas),
                memories = ArrayList(state.memories)
            ).also { it.profiles.forEach { p -> p.apiKey = "" } }
        }
        if (::appContext.isInitialized) {
            state.profiles.forEach { p ->
                if (p.apiKey.isNotBlank()) Secrets.save(appContext, p.id, p.apiKey)
            }
        }
        ioExecutor.execute {
            try {
                val json = gson.toJson(snapshot)
                val tmp = File(file.parent, "${file.name}.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            } catch (_: Exception) {}
        }
        listeners.forEach { it() }
    }

    fun hydrateKeys() {
        if (!::appContext.isInitialized) return
        state.profiles.forEach { p ->
            val stored = Secrets.get(appContext, p.id)
            if (stored.isNotBlank()) p.apiKey = stored
        }
    }

    fun materializeDataUrl(ctx: Context, dataUrl: String, stem: String): String? {
        return try {
            val comma = dataUrl.indexOf(",")
            if (comma == -1) return null
            val b64 = dataUrl.substring(comma + 1)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val dir = File(ctx.filesDir, "avatars").apply { mkdirs() }
            val dest = File(dir, "${stem}_${System.currentTimeMillis()}.jpg")
            dest.writeBytes(bytes)
            dest.absolutePath
        } catch (_: Exception) { null }
    }

    fun setLocale(locale: String) {
        state.locale = locale
        val match = state.presets.firstOrNull { it.locale == locale }?.id
        if (match != null) {
            state.conversations.forEach { c ->
                val cur = state.presets.find { it.id == c.presetId }
                if (cur?.locale != locale) c.presetId = match
            }
        }
        persist()
    }

    fun reset() {
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("tavern_secrets", Context.MODE_PRIVATE).edit().clear().apply()
        }
        state = seed()
        persist()
    }

    fun replace(next: TavernState) {
        val keys = state.profiles
        val active = state.activeProfileId
        state = next
        if (state.profiles.isEmpty()) {
            state.profiles = keys
            state.activeProfileId = active
        } else {
            state.profiles.forEach { p ->
                val old = keys.find { it.id == p.id } ?: keys.find { it.name == p.name }
                if (p.apiKey.isBlank() && old != null && old.apiKey.isNotBlank()) {
                    p.apiKey = old.apiKey
                }
            }
        }
        migrate()
        if (::appContext.isInitialized) {
            fun land(path: String?, stem: String): String? {
                if (path.isNullOrBlank()) return path
                if (path.startsWith("data:")) return materializeDataUrl(appContext, path, stem) ?: path
                return path
            }
            state.characters.forEach { it.avatar = land(it.avatar, "avatar_${it.id}") }
            state.worldBooks.forEach { it.willAvatar = land(it.willAvatar, "will_${it.id}") }
            state.personas.forEach { it.avatar = land(it.avatar, "persona_${it.id}") }
        }
        persist()
    }

    fun backupJson(): String {
        val copy = redactedStateForWrite()
        fun embed(path: String?): String? {
            if (path.isNullOrBlank()) return path
            if (path.startsWith("data:") || path.startsWith("http")) return path
            return try {
                val f = File(path)
                if (!f.exists()) return path
                val bytes = f.readBytes()
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (_: Exception) { path }
        }
        copy.characters.forEach { it.avatar = embed(it.avatar) }
        copy.worldBooks.forEach { it.willAvatar = embed(it.willAvatar) }
        copy.personas.forEach { it.avatar = embed(it.avatar) }
        return gson.toJson(copy)
    }

    fun exportCharacter(ch: Character): String {
        val copy = ch.copy(
            tags = ch.tags.toMutableList(),
            alternateGreetings = ch.alternateGreetings.toMutableList()
        )
        if (!copy.avatar.isNullOrBlank() && !copy.avatar!!.startsWith("data:") && !copy.avatar!!.startsWith("http")) {
            try {
                val avatarFile = File(copy.avatar!!)
                if (avatarFile.exists() && avatarFile.isFile) {
                    // 先解码图片边界，按最大边 640px 计算合适的 inSampleSize，避免大图导致 OOM
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath, bounds)
                    val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                    var sampleSize = 1
                    while (maxSide / sampleSize > 640) {
                        sampleSize *= 2
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bmp = android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath, opts)
                    if (bmp != null) {
                        val baos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                        bmp.recycle()
                        val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                        copy.avatar = "data:image/jpeg;base64,$b64"
                    }
                }
            } catch (_: Exception) {}
        }
        return gson.toJson(copy)
    }

    fun importCharacter(jsonStr: String): Character? {
        return try {
            val ch = gson.fromJson(jsonStr.trim(), Character::class.java)
            if (ch != null && !ch.name.isNullOrBlank()) {
                val timestamp = now()
                ch.id = nid()
                ch.createdAt = timestamp
                ch.updatedAt = timestamp
                ch.name = ch.name ?: ""
                ch.description = ch.description ?: ""
                ch.personality = ch.personality ?: ""
                ch.scenario = ch.scenario ?: ""
                ch.firstMessage = ch.firstMessage ?: ""
                ch.exampleDialogues = ch.exampleDialogues ?: ""
                ch.systemPrompt = ch.systemPrompt ?: ""
                ch.creatorNotes = ch.creatorNotes ?: ""
                ch.tags = ch.tags ?: mutableListOf()
                ch.alternateGreetings = ch.alternateGreetings ?: mutableListOf()
                // 如果头像为嵌入式 Base64，落地为本地图片文件，确保跨设备导入头像不丢失
                if (ch.avatar?.startsWith("data:") == true && ::appContext.isInitialized) {
                    ch.avatar = materializeDataUrl(appContext, ch.avatar!!, "avatar_${ch.id}") ?: ch.avatar
                }
                state.characters.add(0, ch)
                persist()
                ch
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun activeProfile(): ApiProfile? = state.profiles.find { it.id == state.activeProfileId }

    fun profileFor(conv: Conversation?): ApiProfile? {
        val id = conv?.profileId
        if (!id.isNullOrBlank()) state.profiles.find { it.id == id }?.let { return it }
        return activeProfile()
    }

    fun localePresets() = state.presets.filter { it.locale == state.locale }

    fun defaultWorldId(characterId: String) =
        state.characterWorldBooks.find { it.characterId == characterId && it.isDefault }?.worldBookId

    fun setDefaultWorld(characterId: String, worldBookId: String?) {
        state.characterWorldBooks.removeAll { it.characterId == characterId }
        if (!worldBookId.isNullOrBlank()) {
            state.characterWorldBooks.add(CharacterWorldBook(characterId, worldBookId, true))
        }
        persist()
    }

    fun startWorldWillConversation(worldBookId: String): String? {
        val w = state.worldBooks.find { it.id == worldBookId } ?: return null
        val willName = w.willName.ifBlank { if (state.locale == "en") "World Will" else "世界意志" }
        val timestamp = now()
        val conv = Conversation(
            id = nid(),
            storyId = null,
            characterId = "",
            worldBookIds = mutableListOf(w.id),
            presetId = state.presets.firstOrNull { it.locale == state.locale }?.id ?: state.presets.firstOrNull()?.id ?: "preset-default",
            personaId = state.activePersonaId,
            profileId = state.activeProfileId,
            title = "${w.name} · $willName",
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        val persona = state.personas.find { it.id == conv.personaId } ?: state.personas.firstOrNull()
        val userName = persona?.name?.ifBlank { null } ?: state.userName.ifBlank { if (state.locale == "en") "You" else "你" }
        val rawGreeting = w.willFirstMessage.ifBlank {
            if (state.locale == "en") "Welcome to ${w.name}." else "「欢迎来到【${w.name}】。世界的心跳在此刻与你共鸣，你想从何处开启你的故事？」"
        }
        val allGreetings = (listOf(rawGreeting).filter { it.isNotBlank() } + w.willAlternateGreetings.filter { it.isNotBlank() })
        val greetingStrings = if (allGreetings.isEmpty()) listOf(rawGreeting) else allGreetings
        val generations = greetingStrings.map { gText ->
            val filled = Engine.fillMacros(gText, userName, willName)
            Generation(nid(), filled, "greeting", "local", timestamp)
        }.toMutableList()
        val firstContent = generations[0].content
        val greet = ChatMessage(
            id = nid(),
            conversationId = conv.id,
            parentId = null,
            role = "assistant",
            content = firstContent,
            generations = generations,
            generationIndex = 0,
            createdAt = timestamp,
        )
        conv.tipMessageId = greet.id
        state.conversations.add(0, conv)
        state.messages.add(greet)
        persist()
        return conv.id
    }

    fun lastPrivateChat(characterId: String): Conversation? {
        return state.conversations
            .filter { it.characterId == characterId && it.storyId == null }
            .maxByOrNull { it.updatedAt }
    }

    fun lastWorldWillChat(worldBookId: String): Conversation? {
        return state.conversations
            .filter { it.storyId == null && it.characterId.isBlank() && worldBookId in it.worldBookIds }
            .maxByOrNull { it.updatedAt }
    }

    fun lastStoryChat(storyId: String): Conversation? {
        return state.conversations.filter { it.storyId == storyId }.maxByOrNull { it.updatedAt }
    }

    fun deleteWorld(worldBookId: String) {
        state.worldBooks.removeAll { it.id == worldBookId }
        state.entries.removeAll { it.worldBookId == worldBookId }
        state.characterWorldBooks.removeAll { it.worldBookId == worldBookId }
        state.stories.forEach { it.worldBookIds.remove(worldBookId) }
        val willIds = state.conversations
            .filter { it.storyId == null && it.characterId.isBlank() && worldBookId in it.worldBookIds }
            .map { it.id }
            .toSet()
        deleteConversations(willIds)
        state.conversations.forEach { it.worldBookIds.remove(worldBookId) }
    }

    fun deleteConversation(id: String) {
        deleteConversations(setOf(id))
    }

    fun deleteConversations(ids: Set<String>) {
        if (ids.isEmpty()) return
        state.conversations.removeAll { it.id in ids }
        state.messages.removeAll { it.conversationId in ids }
        state.memories.removeAll { it.conversationId in ids }
    }

    fun startConversation(characterId: String? = null, storyId: String? = null, personaId: String? = null): String? {
        val story = if (storyId != null) state.stories.find { it.id == storyId } else null
        val preferredPreset = if (state.locale == "en") "preset-default-en" else "preset-default"
        val effectivePresetId = state.presets.firstOrNull { it.id == preferredPreset }?.id
            ?: state.presets.firstOrNull { it.locale == state.locale }?.id
            ?: state.presets.firstOrNull()?.id
            ?: preferredPreset
        val timestamp = now()
        if (characterId.isNullOrBlank() && storyId != null) {
            val main = state.participants.find { it.storyId == storyId && it.role == "MAIN_CHARACTER" }
                ?: state.participants.firstOrNull { it.storyId == storyId }
            if (main != null) {
                return startConversation(main.characterId, storyId, personaId)
            } else {
                val wid = story?.worldBookIds?.firstOrNull() ?: state.worldBooks.firstOrNull()?.id
                val world = state.worldBooks.find { it.id == wid }
                val willName = world?.willName?.ifBlank { null } ?: if (state.locale == "en") "World Will" else "世界意志"
                val conv = Conversation(
                    id = nid(),
                    storyId = storyId,
                    characterId = "",
                    worldBookIds = story?.worldBookIds ?: mutableListOf(),
                    presetId = effectivePresetId,
                    personaId = personaId ?: story?.personaId ?: state.activePersonaId,
                    profileId = state.activeProfileId,
                    title = "${story?.name ?: "Story"} · $willName",
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
                val persona = state.personas.find { it.id == conv.personaId } ?: state.personas.firstOrNull()
                val userName = persona?.name?.ifBlank { null } ?: state.userName.ifBlank { if (state.locale == "en") "You" else "你" }
                val rawGreeting = world?.willFirstMessage?.ifBlank { null } ?: if (state.locale == "en") "The story begins in ${world?.name ?: "this world"}." else "「故事在【${world?.name ?: "这个世界"}】拉开帷幕。你打算迈向何方？」"
                val allGreetings = (listOf(rawGreeting).filter { it.isNotBlank() } + (world?.willAlternateGreetings ?: emptyList()).filter { it.isNotBlank() })
                val greetingStrings = if (allGreetings.isEmpty()) listOf(rawGreeting) else allGreetings
                val generations = greetingStrings.map { gText ->
                    val filled = Engine.fillMacros(gText, userName, willName)
                    Generation(nid(), filled, "greeting", "local", timestamp)
                }.toMutableList()
                val firstContent = generations[0].content
                val greet = ChatMessage(
                    id = nid(),
                    conversationId = conv.id,
                    parentId = null,
                    role = "assistant",
                    content = firstContent,
                    generations = generations,
                    generationIndex = 0,
                    createdAt = timestamp,
                )
                conv.tipMessageId = greet.id
                state.conversations.add(0, conv)
                state.messages.add(greet)
                persist()
                return conv.id
            }
        }
        val ch = state.characters.find { it.id == characterId } ?: return null
        val worlds = mutableListOf<String>()
        if (storyId != null) {
            story?.worldBookIds?.let { worlds.addAll(it) }
        } else {
            defaultWorldId(ch.id)?.let { worlds.add(it) }
        }
        val conv = Conversation(
            id = nid(),
            storyId = storyId,
            characterId = ch.id,
            worldBookIds = worlds,
            presetId = effectivePresetId,
            personaId = personaId ?: story?.personaId ?: state.activePersonaId,
            profileId = state.activeProfileId,
            title = if (story != null) "${story.name} · ${ch.name}" else ch.name,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        val persona = state.personas.find { it.id == conv.personaId } ?: state.personas.firstOrNull()
        val userName = persona?.name?.ifBlank { null } ?: state.userName.ifBlank { if (state.locale == "en") "You" else "你" }
        // 支持首开场白与全部备用开场白，且自动替换 {{user}} 与 {{char}} 宏
        val allGreetings = (listOf(ch.firstMessage).filter { it.isNotBlank() } + ch.alternateGreetings.filter { it.isNotBlank() })
        val greetingStrings = if (allGreetings.isEmpty()) listOf(if (state.locale == "en") "Hello." else "你好。") else allGreetings
        val generations = greetingStrings.map { gText ->
            val filled = Engine.fillMacros(gText, userName, ch.name)
            Generation(nid(), filled, "greeting", "local", timestamp)
        }.toMutableList()
        val firstContent = generations[0].content
        val greet = ChatMessage(
            id = nid(),
            conversationId = conv.id,
            parentId = null,
            role = "assistant",
            content = firstContent,
            generations = generations,
            generationIndex = 0,
            createdAt = timestamp,
        )
        conv.tipMessageId = greet.id
        state.conversations.add(0, conv)
        state.messages.add(greet)
        persist()
        return conv.id
    }

    private fun migrate() {
        if (state.characters == null) state.characters = mutableListOf()
        if (state.characterWorldBooks == null) state.characterWorldBooks = mutableListOf()
        if (state.worldBooks == null) state.worldBooks = mutableListOf()
        if (state.entries == null) state.entries = mutableListOf()
        if (state.stories == null) state.stories = mutableListOf()
        if (state.participants == null) state.participants = mutableListOf()
        if (state.conversations == null) state.conversations = mutableListOf()
        if (state.messages == null) state.messages = mutableListOf()
        if (state.presets == null) state.presets = mutableListOf()
        if (state.profiles == null) state.profiles = mutableListOf()
        if (state.personas == null) state.personas = mutableListOf()
        if (state.memories == null) state.memories = mutableListOf()

        state.characters.removeAll { it.id.startsWith("gm-") || (it.tags.contains("GM") && it.name.contains("GM")) }
        state.characterWorldBooks.removeAll { it.characterId.startsWith("gm-") }

        state.characters.forEach {
            if (it.tags == null) it.tags = mutableListOf()
            if (it.alternateGreetings == null) it.alternateGreetings = mutableListOf()
        }
        state.worldBooks.forEach {
            if (it.willAlternateGreetings == null) it.willAlternateGreetings = mutableListOf()
        }
        state.stories.forEach {
            if (it.worldBookIds == null) it.worldBookIds = mutableListOf()
        }
        state.conversations.forEach {
            if (it.worldBookIds == null) it.worldBookIds = mutableListOf()
            if (it.draftText == null) it.draftText = ""
        }
        state.messages.forEach {
            if (it.generations == null) it.generations = mutableListOf()
        }

        ensureBuiltinPresets()
        state.conversations.forEach { c ->
            if (state.presets.none { it.id == c.presetId }) {
                c.presetId = if (state.locale == "en") "preset-default-en" else "preset-default"
            }
        }

        state.worldBooks.forEach { w ->
            if (w.willName.isNullOrBlank()) w.willName = if (state.locale == "en") "World Will" else "世界意志"
            if (w.willFirstMessage.isNullOrBlank()) {
                w.willFirstMessage = if (state.locale == "en") "Welcome to ${w.name}. Where would you like to begin your journey?" else "「世界的心跳在此刻与你共鸣。你想从何处开启在【${w.name}】的故事？」"
            }
            if (w.willSystemPrompt.isNullOrBlank()) {
                w.willSystemPrompt = if (state.locale == "en") {
                    "You are the World Will and Narrator for ${w.name}. Vividly describe environments, atmosphere, and NPCs. React to {{user}}'s actions, but never speak or act on behalf of {{user}}."
                } else {
                    "你是【${w.name}】的【世界意志】与故事讲述者（World Will / Narrator）。\n根据世界法则与设定，生动描绘环境与NPC，推动情节，绝不代替玩家（{{user}}）发言或行动。"
                }
            }
        }

        val first = state.userPersona.isBlank()
        if (state.userName.isNullOrBlank()) state.userName = if (state.locale == "en") "You" else "你"
        if (state.userPersona.isBlank()) state.userPersona = ""
        if (state.appearance.isNullOrBlank()) state.appearance = "dark"
        if (first) state.streaming = true
        if (state.personas.isEmpty()) {
            val defaultPersona = Persona(
                id = "persona-default",
                name = state.userName.ifBlank { if (state.locale == "en") "You" else "你" },
                avatar = null,
                description = state.userPersona,
                createdAt = now(),
                updatedAt = now(),
            )
            state.personas.add(defaultPersona)
            state.activePersonaId = defaultPersona.id
        }
        if (state.activePersonaId == null || state.personas.none { it.id == state.activePersonaId }) {
            state.activePersonaId = state.personas.firstOrNull()?.id
        }
        state.profiles.forEach { p ->
            if (p.provider != "claude") p.provider = "openai"
        }
        state.presets.forEach { if (it.locale.isBlank()) it.locale = "zh" }
        if (state.locale != "en") state.locale = "zh"
        if (state.appearance !in listOf("dark", "light", "system")) state.appearance = "dark"
        if (state.profiles.any { it.apiKey.isNotBlank() }) state.setupDone = true
        applyAppearance()
    }

    private fun ensureBuiltinPresets() {
        fun addIfMissing(id: String, name: String, locale: String, temp: Double, max: Int, budget: Int, prompt: String) {
            if (state.presets.none { it.id == id }) {
                state.presets.add(Preset(id, name, locale, temp, 0.95, max, 24000, budget, prompt))
            }
        }
        addIfMissing("preset-novel", "小说模式", "zh", 0.9, 900, 900, "第三人称有限视角。写动作、光线、气味与停顿。不替 {{user}} 行动。一次写完整场景。")
        addIfMissing("preset-creative", "创意模式", "zh", 1.05, 900, 900, "意象更密，仍保持角色声音。允许比喻与感官细节，不替 {{user}} 行动。")
        addIfMissing("preset-fast", "快速模式", "zh", 0.8, 400, 400, "短句、多对话。节奏快，一次回复控制在几轮对白内。不替 {{user}} 行动。")
        addIfMissing("preset-reason", "推理模式", "zh", 0.6, 900, 900, "先在内心核对设定与前文，再写正文。不要把思考过程写出来。不替 {{user}} 行动。")
        addIfMissing("preset-novel-en", "Novel", "en", 0.9, 900, 900, "Limited third person. Write action, light, scent and pauses. Never act for {{user}}.")
        addIfMissing("preset-creative-en", "Creative", "en", 1.05, 900, 900, "Denser imagery while keeping the character's voice. Never act for {{user}}.")
        addIfMissing("preset-fast-en", "Fast", "en", 0.8, 400, 400, "Short lines, lots of dialogue. Never act for {{user}}.")
        addIfMissing("preset-reason-en", "Reasoning", "en", 0.6, 900, 900, "Silently check canon, then write. Do not show the reasoning. Never act for {{user}}.")
        if (state.presets.none { it.id == "preset-default" }) {
            state.presets.add(0, Preset("preset-default", "默认模式", "zh", 0.85, 0.95, 700, 24000, 700, "自然生动地推进剧情。不替用户行动。"))
        }
        if (state.presets.none { it.id == "preset-default-en" }) {
            state.presets.add(Preset("preset-default-en", "Default", "en", 0.85, 0.95, 700, 24000, 700, "Vividly advance the story. Never act for {{user}}."))
        }
    }

    fun applyAppearance() {
        val mode = when (state.appearance) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "system" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun setAppearance(v: String) {
        state.appearance = v
        persist()
        applyAppearance()
    }

    fun seed(): TavernState {
        val t0 = 1_704_000_000_000L
        val alice = Character(
            id = "char-alice",
            name = "艾莉丝",
            description = "暮色酒馆的老板娘。观察入微，少言沉稳。",
            personality = "从容、敏锐。喜欢把话题推回客人身上。",
            scenario = "雨夜，暮色酒馆。壁炉微温。",
            firstMessage = "「坐吧。先告诉我，你是来躲雨的，还是来谈交易的？」",
            alternateGreetings = mutableListOf(),
            exampleDialogues = "",
            systemPrompt = "你是艾莉丝，暮色酒馆老板娘。保持沉稳与克制。",
            creatorNotes = "开源社区示例角色。完整世界观剧本请在 Release APK 体验。",
            tags = mutableListOf("酒馆", "引导"),
            createdAt = t0,
            updatedAt = t0,
        )
        val raen = Character(
            id = "char-raen",
            name = "莱恩",
            description = "沉默的旅剑，话少，行动利落。",
            personality = "寡言、守信。",
            scenario = "坐在酒馆角落，斗篷微湿。",
            firstMessage = "「如果你不是来找我的，就当没看见这张桌子。」",
            systemPrompt = "你是莱恩，寡言的旅剑。不抢戏。",
            tags = mutableListOf("旅人", "同伴"),
            createdAt = t0,
            updatedAt = t0,
        )
        val world = WorldBook(
            id = "world-dusk",
            name = "暮色酒馆",
            description = "雾河西岸的避风港湾。",
            geography = "坐落在雾河西岸堤坝旁。",
            history = "存在已久，来客从不问过往出处。",
            institutions = "酒馆内禁止私斗。",
            culture = "用故事换取美酒。",
            willName = "世界意志",
            willDescription = "负责主持与引导【暮色酒馆】世界的故事发展、环境描写与NPC互动。",
            willScenario = "身处于【暮色酒馆】之中，关注着旅人的每一步选择。",
            willFirstMessage = "「推开酒馆厚重的橡木门，湿冷的夜雨被隔绝在身后。吧台里的艾莉丝抬头看了你一眼，角落里的莱恩仍在擦拭着剑鞘。你打算走向何处？」",
            willSystemPrompt = "你是【暮色酒馆】的世界意志与环境讲述者（World Will / Narrator）。维持沉浸感与酒馆氛围，不替用户行动。",
            createdAt = t0,
            updatedAt = t0,
        )
        val defPersona = Persona(
            id = "persona-default",
            name = "你",
            avatar = null,
            description = "一个走进暮色酒馆的旅人。话不多，观察入微。",
            createdAt = t0,
            updatedAt = t0,
        )
        return TavernState(
            characters = mutableListOf(alice, raen),
            characterWorldBooks = mutableListOf(
                CharacterWorldBook("char-alice", "world-dusk", true),
            ),
            personas = mutableListOf(defPersona),
            activePersonaId = defPersona.id,
            worldBooks = mutableListOf(world),
            entries = mutableListOf(
                WorldBookEntry("entry-const", "world-dusk", "酒馆常态", mutableListOf(), mutableListOf(),
                    "暮色酒馆灯火昏黄，橡木吧台擦得发亮，壁炉里炭火噼啪。空气里有麦芽、湿呢子大衣和旧纸的味道。店规只有一条：不要问别人从哪条路来。",
                    80, true, true, 100, "before_char"),
                WorldBookEntry("entry-empire", "world-dusk", "莱茵帝国", mutableListOf("帝国", "莱茵"), mutableListOf("皇帝"),
                    "莱茵帝国以白塔为首都。皇帝的法令三日可达边境。民间把帝国信使称作「灰羽」，因为他们的斗篷从不干透。",
                    70, true, false, 100, "after_char"),
                WorldBookEntry("entry-capital", "world-dusk", "白塔城", mutableListOf("首都", "白塔"), mutableListOf("雾河"),
                    "白塔城坐落在雾河两岸。桥是白石的，塔是更白的石。入城要验印信；没有印信的人，通常会先被引到河西的酒馆「醒一醒」。",
                    75, true, false, 100, "after_char"),
            ),
            stories = mutableListOf(Story(id = "story-first", name = "第一夜", description = "雨夜走进暮色酒馆。艾莉丝主场，莱恩在角落里听着。", worldBookIds = mutableListOf("world-dusk"), personaId = defPersona.id, createdAt = t0, updatedAt = t0)),
            participants = mutableListOf(
                StoryParticipant("part-main", "story-first", "char-alice", "MAIN_CHARACTER", true, 100),
                StoryParticipant("part-comp", "story-first", "char-raen", "COMPANION", true, 50),
            ),
            conversations = mutableListOf(
                Conversation(id = "conv-welcome", storyId = "story-first", characterId = "char-alice", worldBookIds = mutableListOf("world-dusk"), presetId = "preset-default", personaId = defPersona.id, title = "第一夜 · 进门", tipMessageId = "msg-greet", createdAt = t0, updatedAt = t0),
            ),
            messages = mutableListOf(
                ChatMessage("msg-greet", "conv-welcome", null, "assistant", alice.firstMessage,
                    mutableListOf(Generation("gen-greet", alice.firstMessage, "greeting", "local", t0)), 0, t0),
            ),
            presets = mutableListOf(
                Preset("preset-novel", "小说模式", "zh", 0.9, 0.95, 900, 24000, 900, "第三人称有限视角。写动作、光线、气味与停顿。不替 {{user}} 行动。一次写完整场景。"),
                Preset("preset-creative", "创意模式", "zh", 1.05, 0.95, 900, 24000, 900, "意象更密，仍保持角色声音。不替 {{user}} 行动。"),
                Preset("preset-fast", "快速模式", "zh", 0.8, 0.95, 400, 24000, 400, "短句、多对话。不替 {{user}} 行动。"),
                Preset("preset-reason", "推理模式", "zh", 0.6, 0.95, 900, 24000, 900, "先核对设定再写正文，不把思考过程写出来。不替 {{user}} 行动。"),
                Preset("preset-novel-en", "Novel", "en", 0.9, 0.95, 900, 24000, 900, "Limited third person. Never act for {{user}}."),
                Preset("preset-creative-en", "Creative", "en", 1.05, 0.95, 900, 24000, 900, "Denser imagery. Never act for {{user}}."),
                Preset("preset-fast-en", "Fast", "en", 0.8, 0.95, 400, 24000, 400, "Short lines, lots of dialogue. Never act for {{user}}."),
                Preset("preset-reason-en", "Reasoning", "en", 0.6, 0.95, 900, 24000, 900, "Check canon silently, then write. Never act for {{user}}."),
                Preset("preset-default", "默认模式", "zh", 0.85, 0.95, 700, 24000, 700, "自然生动地推进剧情。不替用户行动。"),
                Preset("preset-default-en", "Default", "en", 0.85, 0.95, 700, 24000, 700, "Vividly advance the story. Never act for {{user}}."),
            ),
            locale = "zh",
            appearance = "dark",
            userName = "你",
            userPersona = "一个走进暮色酒馆的旅人。话不多，观察入微。",
            streaming = true,
            autoSummary = true,
            developerMode = false,
            setupDone = false,
        )
    }
}
