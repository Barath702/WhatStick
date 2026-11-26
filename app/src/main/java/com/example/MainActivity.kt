package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.navigation.WhatStickRoot
import com.example.ui.theme.WhatStickTheme
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeSettings by mainViewModel.themeSettings.collectAsStateWithLifecycle()
            WhatStickTheme(
                accentColor = themeSettings.accentColor,
                themeMode = themeSettings.themeMode
            ) {
                WhatStickRoot(mainViewModel = mainViewModel)
            }
        }
    }
}
