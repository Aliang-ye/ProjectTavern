package com.projecttavern.app

/**
 * Project Tavern - Community Lite Edition
 * Note: Advanced multi-tiered lore orchestration, regex dynamic triggers,
 * and adaptive memory condensation are exclusive to the full release build.
 * See pre-compiled APK in GitHub Releases.
 */
data class PromptBuilt(
    val messages: List<Pair<String, String>>,
    val debug: String,
)

object Engine {
    fun matchEntries(text: String, entries: List<WorldBookEntry>): List<WorldBookEntry> {
        // [Community Edition] Basic constant entries and primary key matches
        val src = text.lowercase()
        return entries.filter { it.enabled && (it.constant || it.keys.any { k -> k.isNotBlank() && src.contains(k.lowercase()) }) }
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

    private fun trimHistoryForContext(history: List<ChatMessage>, preset: Preset?): List<ChatMessage> {
        // [Community Edition] Sliding window context trimming. Neural memory summarization is bundled in release APK.
        if (preset == null || preset.contextLimit <= 0) return history
        val maxChars = preset.contextLimit.coerceAtLeast(4096)
        val kept = history.toMutableList()
        while (kept.size > 4) {
            val text = kept.joinToString("\n") { if (it.role == "assistant") display(it) else it.content }
            if (text.length <= maxChars) break
            kept.removeFirst()
        }
        return kept
    }

    fun build(conversationId: String, tipMessageId: String? = null): PromptBuilt {
        val s = Store.state
        val conv = s.conversations.find { it.id == conversationId } ?: return PromptBuilt(emptyList(), "")
        val ch = s.characters.find { it.id == conv.characterId }
        val preset = s.presets.find { it.id == conv.presetId } ?: Store.localePresets().firstOrNull()
        val effectiveTip = tipMessageId ?: conv.tipMessageId
        val history = visible(conversationId, effectiveTip)
        val contextHistory = trimHistoryForContext(history, preset)
        val histText = contextHistory.joinToString("\n") { if (it.role == "assistant") display(it) else it.content }
        val worldIds = if (conv.worldBookIds.isNotEmpty()) conv.worldBookIds else {
            s.characterWorldBooks.filter { it.characterId == conv.characterId }.map { it.worldBookId }
        }
        val worlds = s.worldBooks.filter { it.id in worldIds }
        val entries = matchEntries(histText, s.entries.filter { it.worldBookId in worldIds })
        val lock = if (s.locale == "en") Store.t("lockEn") else Store.t("lockZh")
        val persona = s.personas.find { it.id == conv.personaId }
            ?: s.personas.find { it.id == s.activePersonaId }
            ?: s.personas.firstOrNull()
        val userName = persona?.name?.ifBlank { null } ?: s.userName.ifBlank { if (s.locale == "en") "You" else "你" }
        val userPersona = persona?.description?.ifBlank { null } ?: s.userPersona
        fun fill(t: String) = t.replace("{{user}}", userName)

        val sys = StringBuilder()
        sys.append("## LANGUAGE\n").append(lock).append("\n\n")
        sys.append("## PERSONA\nThe user is ").append(userName)
        if (userPersona.isNotBlank()) sys.append("\n").append(userPersona)
        sys.append("\n\n")
        if (preset != null && preset.systemPrompt.isNotBlank()) {
            sys.append("## SYSTEM\n").append(preset.systemPrompt).append("\n\n")
        }
        if (ch != null) {
            sys.append("## CHARACTER\nName: ").append(ch.name)
            if (ch.description.isNotBlank()) sys.append("\nDescription: ").append(fill(ch.description))
            if (ch.personality.isNotBlank()) sys.append("\nPersonality: ").append(fill(ch.personality))
            if (ch.scenario.isNotBlank()) sys.append("\nScenario: ").append(fill(ch.scenario))
            if (ch.systemPrompt.isNotBlank()) sys.append("\nInstructions: ").append(fill(ch.systemPrompt))
            if (ch.exampleDialogues.isNotBlank()) sys.append("\nExample Dialogues:\n").append(fill(ch.exampleDialogues))
            sys.append("\n\n")
        }

        if (conv.storyId != null) {
            val story = s.stories.find { it.id == conv.storyId }
            if (story != null) {
                sys.append("## STORY\nTitle: ").append(story.name)
                if (story.description.isNotBlank()) sys.append("\nPlot: ").append(fill(story.description))
                sys.append("\n\n")
                val parts = s.participants.filter { it.storyId == story.id && it.characterId != ch?.id }
                if (parts.isNotEmpty()) {
                    sys.append("## CAST\n")
                    for (p in parts) {
                        val pch = s.characters.find { it.id == p.characterId } ?: continue
                        val roleLabel = when (p.role) {
                            "MAIN_CHARACTER" -> if (s.locale == "en") "Protagonist" else "主角"
                            "COMPANION" -> if (s.locale == "en") "Companion" else "同伴"
                            else -> if (s.locale == "en") "NPC" else "NPC"
                        }
                        sys.append("- [").append(pch.name).append("] (").append(roleLabel).append("): ")
                            .append(fill(pch.description.ifBlank { pch.personality })).append("\n")
                    }
                    sys.append("\n")
                }
            }
        }

        // [Community Edition] Standard world lore attachment
        for (w in worlds) {
            val l = lore(w)
            if (l.isNotBlank()) sys.append("## WORLD\n[").append(w.name).append("]\n").append(l).append("\n\n")
        }
        for (e in entries) sys.append("## ENTRY\n[").append(e.name).append("] ").append(e.content).append("\n\n")

        val messages = mutableListOf("system" to sys.toString().trim())
        for (m in contextHistory) {
            val text = if (m.role == "assistant") display(m).trim() else m.content.trim()
            if (text.isNotBlank()) {
                val role = if (m.role == "assistant") "assistant" else "user"
                messages.add(role to text)
            }
        }
        val debug = "blocks=${messages.size} worlds=${worlds.size} entries=${entries.size} history=${contextHistory.size}"
        return PromptBuilt(messages, debug)
    }
}
