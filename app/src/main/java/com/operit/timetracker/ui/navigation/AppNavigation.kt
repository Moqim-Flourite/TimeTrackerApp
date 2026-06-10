package com.operit.timetracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.operit.timetracker.ui.MainViewModel
import com.operit.timetracker.ui.screens.LogScreen
import com.operit.timetracker.ui.screens.MainScreen
import com.operit.timetracker.ui.screens.StatsScreen
import com.operit.timetracker.ui.screens.HistoryScreen
import com.operit.timetracker.ui.screens.ReviewScreen
import com.operit.timetracker.ui.viewmodel.TaskViewModel

@Composable
fun AppNavigation(viewModel: TaskViewModel, mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainScreen(viewModel = viewModel, mainViewModel = mainViewModel, navController = navController)
        }
        
        composable("stats") {
            StatsScreen(viewModel = viewModel, navController = navController)
        }
        
        composable("history") {
            HistoryScreen(viewModel = viewModel, navController = navController)
        }
        
        composable("log") {
            LogScreen(navController = navController)
        }
        
        composable("diary") {
            ReviewScreen(navController = navController)
        }
    }
}