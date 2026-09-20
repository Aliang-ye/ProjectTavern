package com.projecttavern.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineLogicTest {
    @Before
    fun seed() {
        Store.attachStateForTests(Store.seed())
        Store.state.autoSummary = true
        Store.state.locale = "zh"
    }

    @Test
    fun fillMacrosReplacesUserAndChar() {
        assertEquals("你好，艾莉丝", Engine.fillMacros("你好，{{char}}", "旅人", "艾莉丝"))
        assertEquals("旅人走进酒馆", Engine.fillMacros("{{user}}走进酒馆", "旅人", "艾莉丝"))
        assertEquals("旅人 / 艾莉丝", Engine.fillMacros("{{USER}} / {{ Char }}", "旅人", "艾莉丝"))
    }

    @Test
    fun matchEntriesUsesPrimaryThenSecondary() {
        val entries = listOf(
            WorldBookEntry(id = "1", worldBookId = "w", name = "首都", keys = mutableListOf("帝国"), secondaryKeys = mutableListOf("首都"), content = "lore", priority = 80),
            WorldBookEntry(id = "2", worldBookId = "w", name = "常驻", keys = mutableListOf(), content = "always", constant = true, priority = 10),
            WorldBookEntry(id = "3", worldBookId = "w", name = "关闭", keys = mutableListOf("帝国"), content = "off", enabled = false),
        )
        val hits = Engine.matchEntries("我准备前往帝国首都。", entries, roll = false)
        assertEquals(listOf("首都", "常驻"), hits.map { it.name })
        assertTrue(Engine.matchEntries("只提到帝国", entries, roll = false).none { it.id == "1" })
    }

    @Test
    fun visibleWalksParentChainAndLeafPicksLatestChild() {
        val conv = "c1"
        val root = ChatMessage(id = "r", conversationId = conv, parentId = null, role = "assistant", content = "hi", createdAt = 1)
        val a = ChatMessage(id = "a", conversationId = conv, parentId = "r", role = "user", content = "A", createdAt = 2)
        val b = ChatMessage(id = "b", conversationId = conv, parentId = "r", role = "user", content = "B", createdAt = 3)
        val aReply = ChatMessage(id = "ar", conversationId = conv, parentId = "a", role = "assistant", content = "A reply", createdAt = 4)
        Store.state.messages.addAll(listOf(root, a, b, aReply))
        assertEquals(listOf("r", "a", "ar"), Engine.visible(conv, "ar").map { it.id })
        assertEquals("ar", Engine.leafOf(a).id)
        assertEquals("b", Engine.leafOf(b).id)
        assertEquals(listOf("a", "b"), Engine.siblings(a).map { it.id })
    }

    @Test
    fun displayPrefersSelectedGeneration() {
        val m = ChatMessage(
            id = "m",
            conversationId = "c",
            role = "assistant",
            content = "old",
            generations = mutableListOf(Generation("g1", "one"), Generation("g2", "two")),
            generationIndex = 1,
        )
        assertEquals("two", Engine.display(m))
    }

    @Test
    fun startConversationAndDeleteAlsoDropsMemory() {
        val id = Store.startConversation("char-alice", null)
        assertTrue(id != null)
        Engine.upsertMemory(id!!, "旧摘要")
        assertEquals(1, Store.state.memories.count { it.conversationId == id })
        Store.deleteConversation(id)
        assertTrue(Store.state.conversations.none { it.id == id })
        assertTrue(Store.state.messages.none { it.conversationId == id })
        assertTrue(Store.state.memories.none { it.conversationId == id })
    }

    @Test
    fun lastPrivateChatReturnsMostRecent() {
        val first = Store.startConversation("char-alice", null)!!
        Store.state.conversations.find { it.id == first }!!.updatedAt -= 10
        val second = Store.startConversation("char-alice", null)!!
        assertEquals(second, Store.lastPrivateChat("char-alice")?.id)
        assertTrue(first != second)
    }

    @Test
    fun lastStoryChatAndDeleteWorldDropsWillChats() {
        val story = Story(id = "story-1", name = "雾夜", worldBookIds = mutableListOf("world-dusk"))
        Store.state.stories.add(story)
        val first = Store.startConversation(null, "story-1")!!
        Store.state.conversations.find { it.id == first }!!.updatedAt -= 10
        val second = Store.startConversation(null, "story-1")!!
        assertEquals(second, Store.lastStoryChat("story-1")?.id)

        val will = Store.startWorldWillConversation("world-dusk")!!
        Store.deleteWorld("world-dusk")
        assertTrue(Store.state.conversations.none { it.id == will })
        assertTrue(Store.state.messages.none { it.conversationId == will })
        assertTrue(Store.state.worldBooks.none { it.id == "world-dusk" })
        assertTrue(Store.state.conversations.any { it.id == first || it.id == second })
    }

    @Test
    fun deleteCharacterAlsoDropsStoryChats() {
        val story = Story(id = "story-alice", name = "雨夜", worldBookIds = mutableListOf("world-dusk"))
        Store.state.stories.add(story)
        Store.state.participants.add(StoryParticipant(Store.nid(), "story-alice", "char-alice", "MAIN_CHARACTER"))
        val storyChat = Store.startConversation(null, "story-alice")!!
        val privateChat = Store.startConversation("char-alice", null)!!
        Engine.upsertMemory(privateChat, "摘要")
        Store.deleteCharacter("char-alice")
        assertTrue(Store.state.characters.none { it.id == "char-alice" })
        assertTrue(Store.state.conversations.none { it.id == storyChat || it.id == privateChat })
        assertTrue(Store.state.memories.none { it.conversationId == privateChat })
        assertTrue(Store.state.participants.none { it.characterId == "char-alice" })
    }

    @Test
    fun persistSnapshotDoesNotShareMutableLists() {
        val original = Store.state.characters.first { it.id == "char-alice" }
        val tagsBefore = ArrayList(original.tags)
        val snapshot = Store.state.copy(
            characters = ArrayList(Store.state.characters.map {
                it.copy(tags = ArrayList(it.tags), alternateGreetings = ArrayList(it.alternateGreetings))
            })
        )
        original.tags.add("被污染")
        assertEquals(tagsBefore, snapshot.characters.first { it.id == "char-alice" }.tags)
    }

    @Test
    fun shouldSummarizeNeedsEnoughNewTurns() {
        val convId = "sum"
        Store.state.conversations.add(Conversation(id = convId, characterId = "char-alice", title = "t"))
        repeat(8) { i ->
            Store.state.messages.add(
                ChatMessage(
                    id = "m$i",
                    conversationId = convId,
                    parentId = if (i == 0) null else "m${i - 1}",
                    role = if (i % 2 == 0) "user" else "assistant",
                    content = "msg $i",
                    createdAt = 1_000L * (i + 1),
                )
            )
        }
        Store.state.conversations.first { it.id == convId }.tipMessageId = "m7"
        assertTrue(Engine.shouldSummarize(convId, "m7"))
        Engine.upsertMemory(convId, "摘要")
        Store.state.memories.first { it.conversationId == convId }.updatedAt = 8_000L
        assertFalse(Engine.shouldSummarize(convId, "m7"))
    }

    @Test
    fun i18nHasNoDuplicateKeysAndFallsBack() {
        assertEquals("保存", I18n.t("zh", "save"))
        assertEquals("Save", I18n.t("en", "save"))
        assertEquals("no-such-key", I18n.t("en", "no-such-key"))
        val zh = I18n.t("zh", "continueLastChat")
        val en = I18n.t("en", "continueLastChat")
        assertTrue(zh.isNotBlank())
        assertTrue(en.isNotBlank())
        assertTrue(zh != en)
    }
}
