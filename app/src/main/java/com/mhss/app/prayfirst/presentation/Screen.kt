package com.mhss.app.prayfirst.presentation

import com.mhss.app.prayfirst.R

sealed class Screen(val route: String, val titleRes: Int, val iconRes: Int, val iconSelectedres: Int) {
    // TODO(): Add screen icons
    data object Main: Screen(
        "main_screen",
        R.string.main_screen_title,
        R.drawable.home,
        R.drawable.home_fill
    )
    // Qibla Screen
    data object Qibla : Screen(
        "qibla_screen",
        R.string.qibla_screen_title,  // Title Resource
        R.drawable.qibla,  // Icon resource for default state
        R.drawable.qibla_fill // Icon resource for selected state
    )
    data object Settings: Screen(
        "settings_screen",
        R.string.settings_screen_title,
        R.drawable.settings,
        R.drawable.settings_fill
    )

}
