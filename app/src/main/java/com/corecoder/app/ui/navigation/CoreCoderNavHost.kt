package com.corecoder.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.corecoder.app.ui.screens.ChatScreen
import com.corecoder.app.ui.screens.ConversationListScreen
import com.corecoder.app.ui.screens.SettingsScreen
import com.corecoder.app.ui.screens.SkillsScreen

object Routes {
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val SKILLS = "skills"

    fun chat(conversationId: String) = "chat/$conversationId"
}

@Composable
fun CoreCoderNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.CONVERSATIONS
    ) {
        composable(Routes.CONVERSATIONS) {
            ConversationListScreen(
                onNavigateToChat = { conversationId ->
                    navController.navigate(Routes.chat(conversationId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSkills = { navController.navigate(Routes.SKILLS) }
            )
        }

        composable(Routes.SKILLS) {
            SkillsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
