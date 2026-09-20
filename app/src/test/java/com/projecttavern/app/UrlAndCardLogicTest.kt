package com.projecttavern.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlAndCardLogicTest {
    @Test
    fun openaiUrlNormalizesCommonEndpoints() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Llm.openaiUrl(ApiProfile(endpoint = "https://api.openai.com/v1")),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            Llm.openaiUrl(ApiProfile(endpoint = "https://api.deepseek.com/v1")),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            Llm.openaiUrl(ApiProfile(endpoint = "https://generativelanguage.googleapis.com/v1beta/openai")),
        )
        assertEquals(
            "http://192.168.1.8:11434/v1/chat/completions",
            Llm.openaiUrl(ApiProfile(endpoint = "http://192.168.1.8:11434/v1")),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            Llm.openaiUrl(ApiProfile(endpoint = "https://api.openai.com/v1/chat/completions")),
        )
    }

    @Test
    fun claudeUrlNormalizesMessagesPath() {
        assertEquals("https://api.anthropic.com/v1/messages", Llm.claudeUrl(ApiProfile(endpoint = "https://api.anthropic.com")))
        assertEquals("https://api.anthropic.com/v1/messages", Llm.claudeUrl(ApiProfile(endpoint = "https://api.anthropic.com/v1")))
        assertEquals("https://api.anthropic.com/v1/messages", Llm.claudeUrl(ApiProfile(endpoint = "https://api.anthropic.com/v1/messages")))
    }

    @Test
    fun privateHostsAllowLanAndLocalhostOnly() {
        assertTrue(Llm.isPrivateHost("localhost"))
        assertTrue(Llm.isPrivateHost("127.0.0.1"))
        assertTrue(Llm.isPrivateHost("10.0.0.2"))
        assertTrue(Llm.isPrivateHost("192.168.1.8"))
        assertTrue(Llm.isPrivateHost("172.16.0.4"))
        assertFalse(Llm.isPrivateHost("api.openai.com"))
        assertFalse(Llm.isPrivateHost("8.8.8.8"))
        assertFalse(Llm.isPrivateHost("1.1.1.1"))
    }

    @Test
    fun importKindRejectsBackupAndUnknownObjects() {
        assertEquals("backup", Cards.importKind("""{"characters":[],"conversations":[],"worldBooks":[]}"""))
        assertEquals("unknown", Cards.importKind("""{"foo":1,"bar":2}"""))
        assertEquals("chara_v2", Cards.importKind("""{"spec":"chara_card_v2","data":{"name":"A"}}"""))
        assertEquals("world", Cards.importKind("""{"spec":"project_tavern_world_v1","world":{},"entries":[]}"""))
    }
}
