package com.kaonixx.zeroclaw.navigation

sealed class Screen(val route: String, val label: String) {
    data object Pairing : Screen("pairing", "Pair")
    data object Dashboard : Screen("dashboard", "Dashboard")
    data object AgentsList : Screen("agents", "Agents")
    data object AgentChat : Screen("agent/{alias}", "Chat")
    data object AgentWorkspace : Screen("agent/{alias}/workspace", "Workspace")
    data object Tools : Screen("tools", "Tools")
    data object Cron : Screen("cron", "Cron")
    data object Integrations : Screen("integrations", "Integrations")
    data object Config : Screen("config", "Config")
    data object ConfigSection : Screen("config/{section}", "Config")
    data object Logs : Screen("logs", "Logs")
    data object Doctor : Screen("doctor", "Doctor")
    data object Canvas : Screen("canvas", "Canvas")
    data object Quickstart : Screen("quickstart", "Quickstart")
    data object Onboarding : Screen("onboarding", "Setup")

    companion object {
        val navItems = listOf(
            Dashboard, AgentsList, Tools, Cron,
            Integrations, Config, Logs, Doctor, Canvas
        )
    }
}
