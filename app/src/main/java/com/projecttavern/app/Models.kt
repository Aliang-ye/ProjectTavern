package com.projecttavern.app

data class ApiProfile(
    var id: String = "",
    var name: String = "",
    var provider: String = "openai",
    var endpoint: String = "",
    var model: String = "",
    var apiKey: String = "",
)

data class Preset(
    var id: String = "",
    var name: String = "",
    var locale: String = "zh",
    var temperature: Double = 0.9,
    var topP: Double = 0.95,
    var maxTokens: Int = 900,
    var contextLimit: Int = 32000,
    var responseBudget: Int = 900,
    var systemPrompt: String = "",
)

data class Character(
    var id: String = "",
    var name: String = "",
    var avatar: String? = null,
    var description: String = "",
    var personality: String = "",
    var scenario: String = "",
    var firstMessage: String = "",
    var alternateGreetings: MutableList<String> = mutableListOf(),
    var exampleDialogues: String = "",
    var systemPrompt: String = "",
    var creatorNotes: String = "",
    var tags: MutableList<String> = mutableListOf(),
    var isPinned: Boolean = false,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
)

data class CharacterWorldBook(
    var characterId: String = "",
    var worldBookId: String = "",
    var isDefault: Boolean = true,
)

data class WorldBook(
    var id: String = "",
    var name: String = "",
    var description: String = "",
    var geography: String = "",
    var history: String = "",
    var institutions: String = "",
    var culture: String = "",
    var personalNotes: String = "",
    var willName: String = "世界意志",
    var willDescription: String = "",
    var willScenario: String = "",
    var willFirstMessage: String = "",
    var willSystemPrompt: String = "",
    var willAvatar: String? = null,
    var isPinned: Boolean = false,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
)

data class WorldBookEntry(
    var id: String = "",
    var worldBookId: String = "",
    var name: String = "",
    var keys: MutableList<String> = mutableListOf(),
    var secondaryKeys: MutableList<String> = mutableListOf(),
    var content: String = "",
    var priority: Int = 50,
    var enabled: Boolean = true,
    var constant: Boolean = false,
    var probability: Int = 100,
    var insertionPosition: String = "after_char",
)

data class Persona(
    var id: String = "",
    var name: String = "",
    var avatar: String? = null,
    var description: String = "",
    var tags: MutableList<String> = mutableListOf(),
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
)

data class Story(
    var id: String = "",
    var name: String = "",
    var description: String = "",
    var worldBookIds: MutableList<String> = mutableListOf(),
    var personaId: String? = null,
    var isPinned: Boolean = false,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
)

data class StoryParticipant(
    var id: String = "",
    var storyId: String = "",
    var characterId: String = "",
    var role: String = "COMPANION",
    var enabled: Boolean = true,
    var priority: Int = 50,
)

data class Generation(
    var id: String = "",
    var content: String = "",
    var model: String = "",
    var provider: String = "",
    var createdAt: Long = 0,
)

data class ChatMessage(
    var id: String = "",
    var conversationId: String = "",
    var parentId: String? = null,
    var role: String = "user",
    var content: String = "",
    var generations: MutableList<Generation> = mutableListOf(),
    var generationIndex: Int = 0,
    var createdAt: Long = 0,
)

data class Conversation(
    var id: String = "",
    var storyId: String? = null,
    var characterId: String = "",
    var characterVersionId: String? = null,
    var worldBookIds: MutableList<String> = mutableListOf(),
    var presetId: String = "",
    var personaId: String? = null,
    var title: String = "",
    var tipMessageId: String? = null,
    var isPinned: Boolean = false,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
)

data class Memory(
    var id: String = "",
    var conversationId: String = "",
    var type: String = "CHAT_SUMMARY",
    var content: String = "",
    var updatedAt: Long = 0,
)

data class TavernState(
    var characters: MutableList<Character> = mutableListOf(),
    var characterWorldBooks: MutableList<CharacterWorldBook> = mutableListOf(),
    var worldBooks: MutableList<WorldBook> = mutableListOf(),
    var entries: MutableList<WorldBookEntry> = mutableListOf(),
    var stories: MutableList<Story> = mutableListOf(),
    var participants: MutableList<StoryParticipant> = mutableListOf(),
    var conversations: MutableList<Conversation> = mutableListOf(),
    var messages: MutableList<ChatMessage> = mutableListOf(),
    var memories: MutableList<Memory> = mutableListOf(),
    var presets: MutableList<Preset> = mutableListOf(),
    var profiles: MutableList<ApiProfile> = mutableListOf(),
    var activeProfileId: String? = null,
    var personas: MutableList<Persona> = mutableListOf(),
    var activePersonaId: String? = null,
    var locale: String = "zh",
    var appearance: String = "dark",
    var userName: String = "你",
    var userPersona: String = "",
    var streaming: Boolean = true,
    var autoSummary: Boolean = false,
    var developerMode: Boolean = false,
)
