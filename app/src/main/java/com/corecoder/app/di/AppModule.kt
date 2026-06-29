package com.corecoder.app.di

import android.content.Context
import com.corecoder.app.core.exec.CommandExecutor
import com.corecoder.app.core.exec.ProotCommandExecutor
import com.corecoder.app.data.AppDatabase
import com.corecoder.app.data.ConversationDao
import com.corecoder.app.data.MessageDao
import com.corecoder.app.data.ProviderConfigDao
import com.corecoder.app.data.SkillDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao {
        return db.conversationDao()
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao {
        return db.messageDao()
    }

    @Provides
    fun provideProviderConfigDao(db: AppDatabase): ProviderConfigDao {
        return db.providerConfigDao()
    }

    @Provides
    fun provideSkillDao(db: AppDatabase): SkillDao {
        return db.skillDao()
    }

    /**
     * Provide the command execution backend.
     *
     * Uses [ProotCommandExecutor] — embedded proot + Ubuntu rootfs.
     * No external dependencies (Termux) required.
     */
    @Provides
    @Singleton
    fun provideCommandExecutor(@ApplicationContext context: Context): CommandExecutor {
        return ProotCommandExecutor(context)
    }
}
