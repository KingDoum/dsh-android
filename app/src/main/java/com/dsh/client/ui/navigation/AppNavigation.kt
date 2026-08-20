package com.dsh.client.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dsh.client.ui.chat.ChatScreen
import com.dsh.client.ui.onboarding.OnboardingScreen
import com.dsh.client.ui.sessionlist.SessionListScreen
import com.dsh.client.ui.settings.SettingsScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val iconFilled: ImageVector) {
    data object Sessions : Screen("sessions", "首页", Icons.Outlined.Home, Icons.Filled.Home)
    data object Chat : Screen("chat/{sessionId}", "会话", Icons.Outlined.Chat, Icons.Filled.Chat) {
        fun createRoute(sessionId: String) = "chat/$sessionId"
    }
    data object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

val bottomNavItems = listOf(Screen.Sessions, Screen.Chat, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE) }
    val onboardingDone = prefs.getBoolean("onboarding_done", false)
    var showOnboarding by remember { mutableStateOf(onboardingDone) }

    if (!showOnboarding) {
        OnboardingScreen(
            onComplete = {
                prefs.edit().putBoolean("onboarding_done", true).apply()
                showOnboarding = true
            }
        )
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // P2-2: 大屏/折叠屏适配 — 宽屏(≥600dp)时用侧边导航栏
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    val isChatScreen = currentDestination?.route == Screen.Chat.route
    val isMainScreen = !isChatScreen
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentDestination?.route) {
        if (currentDestination?.route == Screen.Sessions.route) activeSessionId = null
    }

    val navigateToSession: (String) -> Unit = { sessionId ->
        activeSessionId = sessionId
        navController.navigate(Screen.Chat.createRoute(sessionId)) {
            if (isWideScreen) launchSingleTop = true else popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        }
    }

    Scaffold(
        bottomBar = {
            if (isMainScreen && !isWideScreen) {
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
                                if (screen.route == Screen.Chat.route) {
                                    val sessionId = activeSessionId
                                    if (sessionId == null) return@NavigationBarItem
                                    navController.navigate(Screen.Chat.createRoute(sessionId)) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                    return@NavigationBarItem
                                }
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
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

        if (isWideScreen && isMainScreen) {
            // 宽屏：侧边栏 + 内容
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "DSH",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationRailItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.iconFilled else screen.icon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            selected = selected,
                            onClick = {
                                if (screen.route == Screen.Chat.route) {
                                    val sessionId = activeSessionId
                                    if (sessionId == null) return@NavigationRailItem
                                    navController.navigate(Screen.Chat.createRoute(sessionId)) {
                                        launchSingleTop = true
                                    }
                                    return@NavigationRailItem
                                }
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AppNavHost(
                        navController = navController,
                        isWideScreen = true,
                        activeSessionId = activeSessionId,
                        onNavigateToSession = navigateToSession,
                        setActiveSession = { activeSessionId = it }
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                AppNavHost(
                    navController = navController,
                    isWideScreen = false,
                    activeSessionId = activeSessionId,
                    onNavigateToSession = navigateToSession,
                    setActiveSession = { activeSessionId = it }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    isWideScreen: Boolean,
    activeSessionId: String?,
    onNavigateToSession: (String) -> Unit,
    setActiveSession: (String?) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Sessions.route,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Screen.Sessions.route) {
            SessionListScreen(
                onSessionClick = onNavigateToSession,
                onNewSession = { sessionId ->
                    setActiveSession(sessionId)
                    navController.navigate(Screen.Chat.createRoute(sessionId)) {
                        popUpTo(Screen.Sessions.route)
                    }
                }
            )
        }
        composable(Screen.Chat.route) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            LaunchedEffect(sessionId) { setActiveSession(sessionId) }
            ChatScreen(
                sessionId = sessionId,
                isWideScreen = isWideScreen,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}
