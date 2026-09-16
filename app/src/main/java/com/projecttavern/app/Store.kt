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
        sanitizeSensitiveFields()
        migrate()
        persist()
    }

    private fun sanitizeSensitiveFields() {
        state.profiles.forEach { it.apiKey = "" }
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
            file.writeText(gson.toJson(redactedStateForWrite()))
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
        }
        migrate()
        persist()
    }

    fun backupJson(): String = gson.toJson(redactedStateForWrite())

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

    fun startConversation(characterId: String, storyId: String?): String? {
        val ch = state.characters.find { it.id == characterId } ?: return null
        val worlds = mutableListOf<String>()
        defaultWorldId(characterId)?.let { worlds.add(it) }
        if (storyId != null) {
            state.stories.find { it.id == storyId }?.worldBookIds?.let { worlds.addAll(it) }
        }
        val greeting = if (ch.alternateGreetings.isNotEmpty() && Math.random() > 0.55) {
            ch.alternateGreetings.random()
        } else ch.firstMessage
        val presetId = localePresets().firstOrNull()?.id ?: state.presets.firstOrNull()?.id.orEmpty()
        val storyName = storyId?.let { sid -> state.stories.find { it.id == sid }?.name }
        val conv = Conversation(
            id = nid(),
            storyId = storyId,
            characterId = characterId,
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
        val first = state.userPersona.isBlank()
        if (state.userName.isNullOrBlank()) state.userName = if (state.locale == "en") "You" else "你"
        if (state.userPersona.isBlank()) state.userPersona = ""
        if (state.appearance.isNullOrBlank()) state.appearance = "dark"
        if (first) state.streaming = true
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
            description = "暮色酒馆的老板娘。二十出头，声音不高，却能让整个吧台安静下来。她记得每位客人爱喝的酒，也记得他们不肯说出口的事。",
            personality = "从容、观察入微、嘴上不饶人但手下留情。喜欢用反问把话题推回客人身上。不主动揭人秘密，可一旦被信任，会把人护得很紧。",
            scenario = "雨夜。暮色酒馆的门被推开，壁炉还亮着。艾莉丝正在擦一只锡杯，抬眼看你——好像早就知道你会来。",
            firstMessage = "她把锡杯扣在吧台上，烛火在她睫毛下跳了一下。\n\n「坐吧。雨把路浇成这样，你还能找到门，说明今晚不该再走了。」\n\n她把酒单推过来，却没有打开。「先告诉我，你是来躲雨的，还是来签契约的？」",
            alternateGreetings = mutableListOf(
                "二楼的楼梯吱呀一声。艾莉丝没有回头：「上来的人，通常不是来点酒的。」",
                "她把一封没有署名的信按在杯底。「有人把这个留给『下一个走进来的人』。看来是你。」",
            ),
            exampleDialogues = "<START>\n{{user}}: 今晚有什么可喝的？\n艾莉丝: 她笑了一下，像是听见一句很旧的客套。「可喝的永远有。你真正想问的是：今晚有谁在等你。」",
            systemPrompt = "你是艾莉丝，暮色酒馆老板娘。用小说体描写动作与环境，对话加中文引号。不要代替用户说话或决定用户行动。保持克制的神秘感。",
            creatorNotes = "示例角色。可随便改、复制或删除。",
            tags = mutableListOf("酒馆", "女性", "引导"),
            createdAt = t0,
            updatedAt = t0,
        )
        val raen = Character(
            id = "char-raen",
            name = "莱恩",
            description = "沉默的旅剑。据说曾经给帝国送信，现在只接受「能在天亮前走完」的委托。话少，观察极准。",
            personality = "寡言、守信、对弱者护短。不喜欢酒馆里的喧哗，却总坐在能看见门的位置。",
            scenario = "他坐在角落，斗篷还在滴水。剑没有出鞘，手却一直放在剑柄附近。",
            firstMessage = "莱恩抬眼，只看了你一秒，又把视线送回杯中浅浅的酒。\n\n「门在你背后。如果你不是来找我的，就当没看见这张桌子。」",
            systemPrompt = "你是莱恩，寡言的旅剑。描写简短有力。不抢戏，除非用户与你对话。",
            tags = mutableListOf("旅人", "男性", "同伴"),
            createdAt = t0,
            updatedAt = t0,
        )
        val world = WorldBook(
            id = "world-dusk",
            name = "暮色酒馆",
            description = "雾河西岸的一间不挂牌酒馆。雨夜永远有空位，天亮后有些座位会消失。",
            geography = "雾河自北向南切开莱茵帝国。河西是旅人与无印信者的落脚处，河东是白塔城的石桥与税卡。暮色酒馆嵌在河西堤岸一排旧仓库里，门口没有招牌，只有一盏永远半明的黄灯。",
            history = "这家店在帝国税册上不存在。现任老板娘艾莉丝接手已经四年。前任老板在一个雨夜把钥匙放在吧台上，从此再没人见过他。",
            institutions = "帝国信使三日可达边境，民间称他们为「灰羽」。入白塔城须验印信。酒馆自己的规矩只有一条：不要问别人从哪条路来。二楼不挂牌的房间里签下的约定，天亮前反悔无效。",
            culture = "河西的人用故事付第二杯酒。真名很少被叫出声，客人名册只用符号。雨夜被认为适合签契约，晴天适合离开。",
            createdAt = t0,
            updatedAt = t0,
        )
        return TavernState(
            characters = mutableListOf(alice, raen),
            characterWorldBooks = mutableListOf(CharacterWorldBook("char-alice", "world-dusk", true)),
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
            stories = mutableListOf(Story("story-first", "第一夜", "雨夜走进暮色酒馆。艾莉丝主场，莱恩在角落里听着。", mutableListOf("world-dusk"), t0, t0)),
            participants = mutableListOf(
                StoryParticipant("part-main", "story-first", "char-alice", "MAIN_CHARACTER", true, 100),
                StoryParticipant("part-comp", "story-first", "char-raen", "COMPANION", true, 50),
            ),
            conversations = mutableListOf(
                Conversation("conv-welcome", "story-first", "char-alice", null, mutableListOf("world-dusk"), "preset-novel", "第一夜 · 进门", "msg-greet", t0, t0),
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
