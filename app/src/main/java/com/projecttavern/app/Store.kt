package com.projecttavern.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

object Store {
    private const val FILE = "tavern.json"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    lateinit var state: TavernState
        private set
    private lateinit var file: File
    private val listeners = mutableListOf<() -> Unit>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, FILE)
        val raw = if (file.exists()) file.readText() else ""
        state = if (raw.isNotBlank()) {
            try {
                gson.fromJson(raw, TavernState::class.java) ?: seed()
            } catch (_: Exception) {
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

    fun persist() {
        try {
            file.writeText(gson.toJson(state))
        } catch (_: Exception) {
        }
        listeners.forEach { it() }
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
        persist()
    }

    fun backupJson(): String = gson.toJson(redactedStateForWrite())

    fun exportCharacter(ch: Character): String = gson.toJson(ch)

    fun importCharacter(jsonStr: String): Character? {
        return try {
            val ch = gson.fromJson(jsonStr.trim(), Character::class.java)
            if (ch != null && ch.name.isNotBlank()) {
                ch.id = nid()
                ch.createdAt = now()
                ch.updatedAt = now()
                if (ch.tags == null) ch.tags = mutableListOf()
                if (ch.alternateGreetings == null) ch.alternateGreetings = mutableListOf()
                state.characters.add(0, ch)
                persist()
                ch
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun activeProfile(): ApiProfile? = state.profiles.find { it.id == state.activeProfileId }

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

    fun ensureWorldGm(worldId: String, worldName: String): Character {
        val existing = state.characters.find { it.id == "gm-$worldId" }
            ?: state.characters.find { defaultWorldId(it.id) == worldId && (it.tags.contains("GM") || it.name.endsWith("GM")) }
        val isEn = state.locale == "en"
        val cleanName = worldName.trim().ifBlank { if (isEn) "New World" else "新世界" }

        if (existing != null) {
            val oldName = existing.name.removeSuffix(" · GM").trim()
            val hasPlaceholder = existing.description.contains("新世界") || existing.description.contains("New World")
                || existing.systemPrompt.contains("新世界") || existing.systemPrompt.contains("New World")
            if (oldName != cleanName || hasPlaceholder) {
                existing.name = "$cleanName · GM"
                val targets = listOf(oldName, "新世界", "New World").filter { it.isNotBlank() && it != cleanName }
                for (target in targets) {
                    existing.description = existing.description.replace("【$target】", "【$cleanName】").replace(target, cleanName)
                    existing.scenario = existing.scenario.replace("【$target】", "【$cleanName】").replace(target, cleanName)
                    existing.firstMessage = existing.firstMessage.replace("【$target】", "【$cleanName】").replace(target, cleanName)
                    existing.systemPrompt = existing.systemPrompt.replace("【$target】", "【$cleanName】").replace(target, cleanName)
                }
                existing.updatedAt = now()
                persist()
            }
            return existing
        }
        val gm = Character(
            id = "gm-$worldId",
            name = "$cleanName · GM",
            description = if (isEn) "Game Master and narrator for $cleanName." else "负责主持与引导【$cleanName】的故事发展、环境描写与NPC互动。",
            personality = if (isEn) "Immersive, descriptive, observant storyteller." else "客观、富有沉浸感、生动的世界GM与故事讲述者。",
            scenario = if (isEn) "Guiding the journey in $cleanName." else "身处于【$cleanName】之中。",
            firstMessage = if (isEn) "Welcome to $cleanName. Where would you like to begin your adventure?" else "「欢迎来到【$cleanName】。命运的卷轴已然展开，你想从哪里开始你的冒险？」",
            systemPrompt = if (isEn) """
                You are the Game Master and World Narrator for $cleanName.
                Guide the narrative, depict scenery and NPCs vividly, and react to {{user}}'s actions without making decisions for {{user}}.
            """.trimIndent() else """
                你是【$cleanName】的地下城主/世界引导者（Game Master / Narrator）。
                你的任务：
                1. 根据世界书的背景设定与规则，生动描绘玩家所处的环境、遭遇的角色与发生的事件；
                2. 维持世界观的一致性与沉浸感，严格遵循世界书的地理、历史与常态设定；
                3. 推动剧情发展，在适当时候给予玩家选择与悬念，但绝不代替玩家（{{user}}）做出决定或发言；
                4. 采用小说化第三人称或旁白视角，语言优美、充满氛围感。
            """.trimIndent(),
            tags = mutableListOf("GM", "世界引导"),
            createdAt = now(),
            updatedAt = now(),
        )
        state.characters.add(gm)
        setDefaultWorld(gm.id, worldId)
        persist()
        return gm
    }

    fun startConversation(characterId: String? = null, storyId: String? = null, personaId: String? = null): String? {
        val cid = if (!characterId.isNullOrBlank()) {
            characterId
        } else if (storyId != null) {
            val story = state.stories.find { it.id == storyId }
            val main = state.participants.find { it.storyId == storyId && it.role == "MAIN_CHARACTER" }
                ?: state.participants.firstOrNull { it.storyId == storyId }
            if (main != null) {
                main.characterId
            } else {
                val wid = story?.worldBookIds?.firstOrNull() ?: state.worldBooks.firstOrNull()?.id
                val world = state.worldBooks.find { it.id == wid }
                if (world != null) {
                    ensureWorldGm(world.id, world.name).id
                } else state.characters.firstOrNull()?.id
            }
        } else state.characters.firstOrNull()?.id

        val ch = state.characters.find { it.id == cid } ?: return null
        val worlds = mutableListOf<String>()
        defaultWorldId(ch.id)?.let { worlds.add(it) }
        if (storyId != null) {
            state.stories.find { it.id == storyId }?.worldBookIds?.let { worlds.addAll(it) }
        }
        val greeting = if (ch.alternateGreetings.isNotEmpty() && Math.random() > 0.55) {
            ch.alternateGreetings.random()
        } else ch.firstMessage.ifBlank {
            if (state.locale == "en") "Hello, traveler." else "你好，旅人。"
        }
        val presetId = localePresets().firstOrNull()?.id ?: state.presets.firstOrNull()?.id.orEmpty()
        val storyName = storyId?.let { sid -> state.stories.find { it.id == sid }?.name }
        val effectivePersonaId = personaId
            ?: (if (storyId != null) state.stories.find { it.id == storyId }?.personaId else null)
            ?: state.activePersonaId
        val conv = Conversation(
            id = nid(),
            storyId = storyId,
            characterId = ch.id,
            personaId = effectivePersonaId,
            worldBookIds = worlds.distinct().toMutableList(),
            presetId = presetId,
            title = if (storyName != null) "$storyName · ${ch.name}" else ch.name,
            createdAt = now(),
            updatedAt = now(),
        )
        val greet = ChatMessage(
            id = nid(),
            conversationId = conv.id,
            parentId = null,
            role = "assistant",
            content = greeting,
            generations = mutableListOf(Generation(nid(), greeting, "greeting", "local", now())),
            generationIndex = 0,
            createdAt = now(),
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

        state.characters.forEach {
            if (it.tags == null) it.tags = mutableListOf()
            if (it.alternateGreetings == null) it.alternateGreetings = mutableListOf()
        }
        state.stories.forEach {
            if (it.worldBookIds == null) it.worldBookIds = mutableListOf()
        }
        state.conversations.forEach {
            if (it.worldBookIds == null) it.worldBookIds = mutableListOf()
        }
        state.messages.forEach {
            if (it.generations == null) it.generations = mutableListOf()
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
        state.worldBooks.forEach { w ->
            ensureWorldGm(w.id, w.name)
        }
        state.profiles.forEach { p ->
            if (p.provider != "claude") p.provider = "openai"
        }
        val seedPresets = seed().presets
        seedPresets.forEach { p ->
            if (state.presets.none { it.id == p.id }) state.presets.add(p)
        }
        state.presets.forEach { if (it.locale.isBlank()) it.locale = "zh" }
        if (state.locale != "en") state.locale = "zh"
        if (state.appearance !in listOf("dark", "light", "system")) state.appearance = "dark"
        if (state.userName.isBlank()) {
            state.userName = if (state.locale == "en") "You" else "你"
            state.streaming = true
        }
        applyAppearance()
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
            createdAt = t0,
            updatedAt = t0,
        )
        val gm = Character(
            id = "gm-world-dusk",
            name = "暮色酒馆 · GM",
            description = "负责主持与引导【暮色酒馆】世界的故事发展、环境描写与NPC互动。",
            personality = "客观、富有沉浸感、生动的世界GM与故事讲述者。",
            scenario = "身处于【暮色酒馆】之中，关注着旅人的每一步选择。",
            firstMessage = "「推开酒馆厚重的橡木门，湿冷的夜雨被隔绝在身后。吧台里的艾莉丝抬头看了你一眼，角落里的莱恩仍在擦拭着剑鞘。你打算走向何处？」",
            systemPrompt = "你是【暮色酒馆】的地下城主/世界引导者（Game Master / Narrator）。维持沉浸感与酒馆氛围，不替用户行动。",
            tags = mutableListOf("GM", "世界引导"),
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
            characters = mutableListOf(alice, raen, gm),
            characterWorldBooks = mutableListOf(
                CharacterWorldBook("char-alice", "world-dusk", true),
                CharacterWorldBook(gm.id, "world-dusk", true),
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
                Conversation(id = "conv-welcome", storyId = "story-first", characterId = "char-alice", worldBookIds = mutableListOf("world-dusk"), presetId = "preset-novel", personaId = defPersona.id, title = "第一夜 · 进门", tipMessageId = "msg-greet", createdAt = t0, updatedAt = t0),
            ),
            messages = mutableListOf(
                ChatMessage("msg-greet", "conv-welcome", null, "assistant", alice.firstMessage,
                    mutableListOf(Generation("gen-greet", alice.firstMessage, "greeting", "local", t0)), 0, t0),
            ),
            presets = mutableListOf(
                Preset("preset-novel", "小说模式", "zh", 0.9, 0.95, 900, 32000, 900, "以第三人称有限视角写小说。描写动作、光线、气味与停顿。对话用中文引号。不要替用户行动。一次回复控制在 150–400 字。"),
                Preset("preset-creative", "创意模式", "zh", 1.05, 0.96, 700, 24000, 700, "更奔放、意象更密。仍保持角色声音，不跳出设定。"),
                Preset("preset-fast", "快速模式", "zh", 0.7, 0.9, 400, 16000, 400, "短句，快节奏。少描写，多对话。"),
                Preset("preset-reason", "推理模式", "zh", 0.55, 0.85, 1100, 32000, 1100, "先在心里核对设定、前文与世界书，再写正文。不要把思考过程写出来。保持角色声音，不替用户行动。"),
                Preset("preset-novel-en", "Novel", "en", 0.9, 0.95, 900, 32000, 900, "Write in close third person. Describe gesture, light, smell, and pause. Dialogue in quotation marks. Never act for the user."),
                Preset("preset-creative-en", "Creative", "en", 1.05, 0.96, 700, 24000, 700, "Bolder imagery. Keep the character's voice."),
                Preset("preset-fast-en", "Fast", "en", 0.7, 0.9, 400, 16000, 400, "Short sentences. Fast pace. More dialogue, less description."),
                Preset("preset-reason-en", "Reasoning", "en", 0.55, 0.85, 1100, 32000, 1100, "Silently check continuity, lore, and character voice, then write. Do not show chain-of-thought. Never act for the user."),
            ),
            locale = "zh",
            appearance = "dark",
            userName = "你",
            userPersona = "一个走进暮色酒馆的旅人。话不多，观察入微。",
            streaming = true,
            autoSummary = false,
            developerMode = false,
        )
    }
}
