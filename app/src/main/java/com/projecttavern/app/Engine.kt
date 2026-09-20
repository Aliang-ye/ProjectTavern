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

    fun fillMacros(text: String, userName: String, charName: String): String {
        return text.replace("{{user}}", userName).replace("{{char}}", charName)
    }

    fun formatRpText(raw: String): CharSequence {
        if (!raw.contains("*")) return raw
        return try {
            val escaped = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val formatted = escaped.replace(Regex("\\*([^*\\n]+)\\*")) { matchResult ->
                "<i>${matchResult.groupValues[1]}</i>"
            }.replace("\n", "<br/>")
            android.text.SpannableStringBuilder(android.text.Html.fromHtml(formatted, android.text.Html.FROM_HTML_MODE_COMPACT))
        } catch (_: Exception) {
            raw
        }
    }

    private fun trimHistoryForContext(history: List<ChatMessage>, preset: Preset?): List<ChatMessage> {
        // [Community Edition] Sliding window context trimming. Neural memory summarization is bundled in release APK.
        if (preset == null || preset.contextLimit <= 0) return history
        val maxChars = preset.contextLimit.coerceAtLeast(4096)
        if (history.size <= 4) return history
        // O(N)：从尾部往前累计字符长度，而非每次都 joinToString 整个列表
        var total = 0
        val kept = ArrayDeque<ChatMessage>()
        for (m in history.asReversed()) {
            val len = (if (m.role == "assistant") display(m) else m.content).length + 1
            if (total + len > maxChars && kept.size >= 4) break
            kept.addFirst(m)
            total += len
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
        // 角色名称（用于 {{char}} 宏）
        val charName = ch?.name ?: if (worlds.isNotEmpty()) {
            val w0 = worlds.first()
            w0.willName.ifBlank { if (s.locale == "en") "World Will" else "世界意志" }
        } else ""
        fun fill(t: String) = fillMacros(t, userName, charName)

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
        } else if (worlds.isNotEmpty()) {
            val primaryWorld = worlds.first()
            val willName = primaryWorld.willName.ifBlank { if (s.locale == "en") "World Will" else "世界意志" }
            sys.append("## WORLD WILL (NARRATOR)\nName: ").append(willName)
            sys.append(" of [").append(primaryWorld.name).append("]")
            if (primaryWorld.willDescription.isNotBlank()) sys.append("\nDescription: ").append(fill(primaryWorld.willDescription))
            if (primaryWorld.willScenario.isNotBlank()) sys.append("\nScenario: ").append(fill(primaryWorld.willScenario))
            if (primaryWorld.willSystemPrompt.isNotBlank()) sys.append("\nInstructions: ").append(fill(primaryWorld.willSystemPrompt))
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
