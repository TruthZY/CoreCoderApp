package com.corecoder.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entities for CoreCoder.
 */

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,               // UUID
    val title: String? = null,    // Auto-generated from first user message
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,                      // UUID
    val conversationId: String,
    val role: String,                    // user / assistant / tool / system
    val content: String? = null,
    val toolCalls: String? = null,       // JSON serialized tool calls
    val toolCallId: String? = null,
    val tokenCount: Int? = null,
    val createdAt: Long,
    val sortOrder: Int                   // Message ordering within conversation
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey
    val id: String,                      // UUID
    val name: String,                    // "OpenAI", "DeepSeek", etc.
    val apiKey: String,                  // API key (should be encrypted in production)
    val baseUrl: String? = null,
    val model: String,
    val isDefault: Boolean = false
)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey
    val id: String,                      // Folder name (skill identifier)
    val name: String,                    // Display name
    val description: String,             // First 100 chars of SKILL.md
    val folderPath: String,              // Full path to skill folder
    val enabled: Boolean = true,
    val createdAt: Long
)
