package com.kaonixx.zeroclaw.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kaonixx.zeroclaw.ui.*
import com.kaonixx.zeroclaw.ui.screens.OnboardingScreen

@Composable
fun NavGraph(navController: NavHostController, isPaired: Boolean, onPair: suspend (String) -> Unit) {
    NavHost(
        navController = navController,
        startDestination = if (isPaired) Screen.Dashboard.route else Screen.Pairing.route
    ) {
        composable(Screen.Pairing.route) {
            PairingScreen(onPair = onPair)
        }

        composable(Screen.Dashboard.route) {
            DashboardScreen()
        }

        composable(Screen.AgentsList.route) {
            AgentsListScreen(
                onAgentClick = { alias ->
                    navController.navigate("agent/$alias")
                }
            )
        }

        composable(
            route = Screen.AgentChat.route,
            arguments = listOf(navArgument("alias") { type = NavType.StringType })
        ) { backStackEntry ->
            val alias = backStackEntry.arguments?.getString("alias") ?: ""
            AgentChatScreen(
                alias = alias,
                onBack = { navController.popBackStack() },
                onWorkspace = { navController.navigate("agent/$alias/workspace") }
            )
        }

        composable(
            route = Screen.AgentWorkspace.route,
            arguments = listOf(navArgument("alias") { type = NavType.StringType })
        ) { backStackEntry ->
            val alias = backStackEntry.arguments?.getString("alias") ?: ""
            WorkspaceExplorerScreen(
                alias = alias,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Tools.route) {
            ToolsScreen()
        }

        composable(Screen.Cron.route) {
            CronScreen()
        }

        composable(Screen.Integrations.route) {
            IntegrationsScreen()
        }

        composable(Screen.Config.route) {
            ConfigScreen(onSectionClick = { section ->
                navController.navigate("config/$section")
            })
        }

        composable(
            route = Screen.ConfigSection.route,
            arguments = listOf(navArgument("section") { type = NavType.StringType })
        ) { backStackEntry ->
            val section = backStackEntry.arguments?.getString("section") ?: ""
            ConfigSectionScreen(section = section, onBack = { navController.popBackStack() })
        }

        composable(Screen.Logs.route) {
            LogsScreen()
        }

        composable(Screen.Doctor.route) {
            DoctorScreen()
        }

        composable(Screen.Canvas.route) {
            CanvasScreen()
        }

        composable(Screen.Quickstart.route) {
            OnboardingScreen(onComplete = {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Quickstart.route) { inclusive = true }
                }
            })
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(onComplete = {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                }
            })
        }
    }
}
