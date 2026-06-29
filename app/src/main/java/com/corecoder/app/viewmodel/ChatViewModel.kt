package com.corecoder.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corecoder.app.core.Agent
import com.corecoder.app.core.LLMClient
import com.corecoder.app.core.SkillManager
import com.corecoder.app.core.exec.CommandExecutor
import com.corecoder.app.core.exec.EnvironmentBootstrap
import com.corecoder.app.core.exec.ProotCommandExecutor
import com.corecoder.app.core.tools.ToolRegistry
import com.corecoder.app.data.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** Represents a single display item in the chat UI. */
data class ChatMessage(
    val id: String,
    val role: String,            // "user", "assistant", "tool"
    val content: String?,
    val toolName: String? = null,
    val toolArgs: Map<String, Any?>? = null,
    val toolResult: String? = null,
    val toolCallId: String? = null,
    val isStreaming: Boolean = false,
    val tokenCount: Int? = null  // prompt + completion tokens for this message's LLM turn
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val providerConfigDao: ProviderConfigDao,
    private val commandExecutor: CommandExecutor,
    private val skillDao: SkillDao,
    private val skillManager: SkillManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])
    private val gson = Gson()

    private var agent: Agent? = null
    private var chatJob: Job? = null
    private var conversationPersisted = false

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _currentTool = MutableStateFlow<Pair<String, Map<String, Any?>>?>(null)
    val currentTool: StateFlow<Pair<String, Map<String, Any?>>?> = _currentTool.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _executorStatus = MutableStateFlow("checking")
    val executorStatus: StateFlow<String> = _executorStatus.asStateFlow()

    private val _bootstrapProgress = MutableStateFlow<EnvironmentBootstrap.Progress>(EnvironmentBootstrap.Progress.Idle)
    val bootstrapProgress: StateFlow<EnvironmentBootstrap.Progress> = _bootstrapProgress.asStateFlow()

    init {
        loadConversation()
        observeBootstrap()
    }

    private fun observeBootstrap() {
        viewModelScope.launch {
            EnvironmentBootstrap.progress.collect { progress ->
                _bootstrapProgress.value = progress
            }
        }
    }

    private fun loadConversation() {
        viewModelScope.launch {
            val conversation = conversationDao.getById(conversationId)
            conversationPersisted = conversation != null
            _modelName.value = conversation?.model ?: "gpt-4o"

            // Initialize agent with provider config
            val provider = providerConfigDao.getDefault()
            val llm = LLMClient(
                model = provider?.model ?: conversation?.model ?: "gpt-4o",
                apiKey = provider?.apiKey ?: "",
                baseUrl = provider?.baseUrl,
                temperature = 0f,
                maxTokens = 4096
            )

            // Check execution backend availability
            val status = commandExecutor.checkAvailability()
            _executorStatus.value = status

            // Auto-bootstrap if not yet set up
            if (status == "not_bootstrapped") {
                startBootstrap()
            }

            // Load enabled skills
            val enabledSkills = skillDao.getEnabledSkills()

            agent = Agent(
                llm = llm,
                toolRegistry = ToolRegistry.createDefault(commandExecutor, skillManager),
                executorStatus = status,
                enabledSkills = enabledSkills
            )

            // Restore messages from DB (will be empty for new conversations)
            val savedMessages = messageDao.getMessagesForConversationSync(conversationId)
            val chatMessages = savedMessages.map { entity ->
                ChatMessage(
                    id = entity.id,
                    role = entity.role,
                    content = entity.content,
                    toolCallId = entity.toolCallId,
                    tokenCount = entity.tokenCount
                )
            }
            _messages.value = chatMessages

            // Restore agent message history
            for (entity in savedMessages) {
                val msg = JsonObject()
                msg.addProperty("role", entity.role)
                if (entity.content != null) msg.addProperty("content", entity.content)
                if (entity.toolCallId != null) msg.addProperty("tool_call_id", entity.toolCallId)
                if (entity.toolCalls != null) {
                    msg.add("tool_calls", gson.fromJson(entity.toolCalls, com.google.gson.JsonArray::class.java))
                }
                agent?.messages?.add(msg)
            }
        }
    }

    fun sendMessage(userInput: String) {
        if (userInput.isBlank() || _isProcessing.value) return

        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            _isProcessing.value = true
            _error.value = null

            // Add user message to UI
            val userMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = "user",
                content = userInput
            )
            _messages.value = _messages.value + userMsg

            // Lazily create conversation in DB on first message
            if (!conversationPersisted) {
                val provider = providerConfigDao.getDefault()
                val conversation = ConversationEntity(
                    id = conversationId,
                    title = null,
                    model = provider?.model ?: "gpt-4o",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                conversationDao.insert(conversation)
                conversationPersisted = true
            }

            // Save to DB
            saveMessage(userMsg)

            // Update conversation title from first user message
            val msgCount = messageDao.countForConversation(conversationId)
            if (msgCount <= 1) {
                val title = userInput.take(50).let {
                    if (userInput.length > 50) "$it..." else it
                }
                conversationDao.updateTitle(conversationId, title)
            }
            conversationDao.updateTimestamp(conversationId, System.currentTimeMillis())

            // Create streaming assistant message
            val assistantMsgId = UUID.randomUUID().toString()
            val assistantMsg = ChatMessage(
                id = assistantMsgId,
                role = "assistant",
                content = "",
                isStreaming = true
            )
            _messages.value = _messages.value + assistantMsg
            _streamingText.value = ""

            try {
                val ag = agent ?: throw IllegalStateException("Agent not initialized")

                val agentResp = ag.chat(
                    userInput = userInput,
                    onToken = { token ->
                        _streamingText.value += token
                    },
                    onTool = { name, args ->
                        _currentTool.value = Pair(name, args)

                        // Add tool call message
                        val toolMsg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = "tool",
                            content = null,
                            toolName = name,
                            toolArgs = args
                        )
                        _messages.value = _messages.value.dropLast(1) + toolMsg + assistantMsg.copy(
                            content = _streamingText.value,
                            isStreaming = true
                        )
                    }
                )

                val turnTokens = agentResp.totalTokens

                // Finalize assistant message
                val finalAssistantMsg = assistantMsg.copy(
                    content = agentResp.content,
                    isStreaming = false,
                    tokenCount = turnTokens
                )
                _messages.value = _messages.value.dropLast(1) + finalAssistantMsg
                saveMessage(finalAssistantMsg)

            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
                // Remove the streaming message on error
                _messages.value = _messages.value.filter { it.id != assistantMsgId }
            } finally {
                _currentTool.value = null
                _streamingText.value = ""
                _isProcessing.value = false
            }
        }
    }

    fun cancelChat() {
        chatJob?.cancel()
        _isProcessing.value = false
        _currentTool.value = null
    }

    fun resetConversation() {
        viewModelScope.launch {
            chatJob?.cancel()
            agent?.reset()
            messageDao.deleteAllForConversation(conversationId)
            _messages.value = emptyList()
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Start the Linux environment bootstrap (first launch).
     */
    fun startBootstrap() {
        viewModelScope.launch {
            val success = EnvironmentBootstrap.bootstrap(appContext)
            if (success) {
                _executorStatus.value = "ready"
            }
        }
    }

    /**
     * Re-check executor availability and update the UI state.
     * Called after bootstrap completes or if the environment has issues.
     */
    fun recheckExecutor() {
        viewModelScope.launch {
            val status = commandExecutor.checkAvailability()
            _executorStatus.value = status
        }
    }

    private suspend fun saveMessage(chatMsg: ChatMessage) = withContext(Dispatchers.IO) {
        val maxOrder = messageDao.getMaxSortOrder(conversationId) ?: 0
        val entity = MessageEntity(
            id = chatMsg.id,
            conversationId = conversationId,
            role = chatMsg.role,
            content = chatMsg.content,
            toolCallId = chatMsg.toolCallId,
            tokenCount = chatMsg.tokenCount,
            createdAt = System.currentTimeMillis(),
            sortOrder = maxOrder + 1
        )
        messageDao.insert(entity)
    }
}
