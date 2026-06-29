package com.corecoder.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corecoder.app.data.ConversationDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ConversationListItem(
    val id: String,
    val title: String?,
    val model: String,
    val updatedAt: Long,
    val preview: String = ""
)

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val conversationDao: ConversationDao
) : ViewModel() {

    val conversations: StateFlow<List<ConversationListItem>> = conversationDao
        .getAllConversations()
        .map { entities ->
            entities.map { entity ->
                ConversationListItem(
                    id = entity.id,
                    title = entity.title,
                    model = entity.model,
                    updatedAt = entity.updatedAt
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Generate a new conversation ID without writing to DB. DB record is created lazily on first message. */
    fun newConversationId(): String {
        return UUID.randomUUID().toString()
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationDao.deleteById(id)
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            conversationDao.updateTitle(id, newTitle)
        }
    }
}
