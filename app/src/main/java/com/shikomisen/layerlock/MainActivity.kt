package com.shikomisen.layerlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shikomisen.layerlock.ui.LayerLockApp
import com.shikomisen.layerlock.ui.theme.LayerlockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LayerlockTheme {
                LayerLockApp()
            }
        }
    }
}
