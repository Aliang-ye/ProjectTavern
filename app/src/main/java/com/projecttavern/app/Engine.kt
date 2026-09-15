package com.projecttavern.app

data class PromptBuilt(
    val messages: List<Pair<String, String>>,
    val debug: String,
)

object Engine {
    fun matchEntries(text: String, entries: List<WorldBookEntry>): List<WorldBookEntry> {
        val src = text.lowercase()
        val out = mutableListOf<WorldBookEntry>()
        for (e in entries.filter { it.enabled }.sortedByDescending { it.priority }) {
            if (e.constant) {
                out.add(e)
                continue
            }
            if (e.keys.any { it.isNotBlank() && src.contains(it.lowercase()) } ||
                e.secondaryKeys.any { it.isNotBlank() && src.contains(it.lowercase()) }
            ) {
                out.add(e)
            }
        }
        return out
    }

    fun lore(w: WorldBook): String {
        val rows = listOf(
            "Overview" to w.description,
            "Geography" to w.geography,
            "History" to w.history,
            "Institutions" to w.institutions,
            "Culture" to w.culture,
            "Personal notes" to w.personalNotes,
        )
        return rows.filter { it.second.isNotBlank() }.joinToString("\n\n") { "[${it.first}] ${it.second.trim()}" }
    }

    fun visible(convId: String, tipId: String?): List<ChatMessage> {
        val msgs = Store.state.messages.filter { it.conversationId == convId }
        if (tipId == null) return emptyList()
        val path = mutableListOf<ChatMessage>()
        var cur: String? = tipId
        val guard = mutableSetOf<String>()
        while (cur != null && guard.add(cur)) {
            val m = msgs.find { it.id == cur } ?: break
            path.add(m)
            cur = m.parentId
        }
        return path.reversed()
    }

    fun display(m: ChatMessage): String {
        val g = m.generations.getOrNull(m.generationIndex) ?: m.generations.firstOrNull()
        return g?.content ?: m.content
    }

    fun build(conversationId: String): PromptBuilt {
        val s = Store.state
        val conv = s.conversations.find { it.id == conversationId } ?: return PromptBuilt(emptyList(), "")
        val ch = s.characters.find { it.id == conv.characterId }
        val preset = s.presets.find { it.id == conv.presetId } ?: Store.localePresets().firstOrNull()
        val history = visible(conversationId, conv.tipMessageId)
        val histText = history.joinToString("\n") { if (it.role == "assistant") display(it) else it.content }
        val worldIds = if (conv.worldBookIds.isNotEmpty()) conv.worldBookIds else {
            s.characterWorldBooks.filter { it.characterId == conv.characterId }.map { it.worldBookId }
        }
        val worlds = s.worldBooks.filter { it.id in worldIds }
        val entries = matchEntries(histText, s.entries.filter { it.worldBookId in worldIds })
        val lock = if (s.locale == "en") Store.t("lockEn") else Store.t("lockZh")
        val userName = s.userName.ifBlank { if (s.locale == "en") "You" else "你" }
        fun fill(t: String) = t.replace("{{user}}", userName)
        val sys = StringBuilder()
        sys.append("## LANGUAGE\n").append(lock).append("\n\n")
        sys.append("## PERSONA\nThe user / {{user}} is named ").append(userName)
        if (s.userPersona.isNotBlank()) sys.append("\n").append(s.userPersona)
        sys.append("\n\n")
        if (preset != null) sys.append("## SYSTEM\n").append(preset.systemPrompt).append("\n\n")
        if (ch?.systemPrompt?.isNotBlank() == true) sys.append(ch.systemPrompt).append("\n\n")
        for (w in worlds) {
            val l = lore(w)
            if (l.isNotBlank()) sys.append("## WORLD_LORE\n[").append(w.name).append("]\n").append(l).append("\n\n")
        }
        for (e in entries) sys.append("## WORLD\n[").append(e.name).append("] ").append(e.content).append("\n\n")
        if (ch != null) {
            sys.append("## CHARACTER\nName: ").append(ch.name)
                .append("\nDescription: ").append(fill(ch.description))
                .append("\nPersonality: ").append(ch.personality)
                .append("\nScenario: ").append(fill(ch.scenario)).append("\n\n")
            if (ch.exampleDialogues.isNotBlank()) sys.append("## EXAMPLE\n").append(fill(ch.exampleDialogues)).append("\n\n")
        }
        if (conv.storyId == null) {
            sys.append("## MODE\nThis is a private one-to-one chat with the character. No story cast.\n\n")
        } else {
            s.participants.filter { it.storyId == conv.storyId && it.role == "COMPANION" && it.enabled }.forEach { p ->
                val c = s.characters.find { it.id == p.characterId }
                if (c != null && c.id != conv.characterId) {
                    sys.append("## COMPANION\n").append(c.name).append(": ").append(c.description).append("\n")
                }
            }
        }
        s.memories.find { it.conversationId == conversationId }?.let {
            if (it.content.isNotBlank()) sys.append("## MEMORY\n").append(it.content).append("\n\n")
        }
        val messages = mutableListOf("system" to sys.toString().trim())
        for (m in history) {
            val role = if (m.role == "assistant") "assistant" else "user"
            messages.add(role to if (m.role == "assistant") display(m) else m.content)
        }
        val debug = "blocks=${messages.size} worlds=${worlds.joinToString { it.name }} entries=${entries.joinToString { it.name }}"
        return PromptBuilt(messages, debug)
    }
}
