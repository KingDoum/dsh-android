package com.dsh.client.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dsh.client.ui.sessionlist.SessionListScreen
import com.dsh.client.ui.chat.ChatScreen
import com.dsh.client.ui.settings.SettingsScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val iconFilled: ImageVector) {
    data object Sessions : Screen("sessions", "首页", Icons.Outlined.Home, Icons.Filled.Home)
    data object Chat : Screen("chat/{sessionId}", "会话", Icons.Outlined.Chat, Icons.Filled.Chat) {
        fun createRoute(sessionId: String) = "chat/$sessionId"
    }
    data object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
    data object NewSession : Screen("new_session", "新建", Icons.Outlined.Add, Icons.Filled.Add)
}

val bottomNavItems = listOf(Screen.Sessions, Screen.Chat, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determine if we're in a chat session
    val isChatScreen = currentDestination?.route == Screen.Chat.route
    val isMainScreen = !isChatScreen

    Scaffold(
        bottomBar = {
            if (isMainScreen) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.iconFilled else screen.icon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Sessions.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Sessions.route) {
                SessionListScreen(
                    onSessionClick = { sessionId ->
                        navController.navigate(Screen.Chat.createRoute(sessionId))
                    },
                    onNewSession = {
                        navController.navigate(Screen.NewSession.route)
                    }
                )
            }
            composable(Screen.Chat.route) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                ChatScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            composable(Screen.NewSession.route) {
                // Create session and navigate to chat
                SessionListScreen(
                    onSessionClick = { sessionId ->
                        navController.navigate(Screen.Chat.createRoute(sessionId))
                    },
                    onNewSession = {
                        navController.navigate(Screen.NewSession.route)
                    }
                )
            }
        }
    }
}
