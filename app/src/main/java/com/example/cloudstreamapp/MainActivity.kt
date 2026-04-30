package com.example.cloudstreamapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.cloudstreamapp.ui.main.MainScreen
import com.example.cloudstreamapp.ui.theme.CloudStreamAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloudStreamAppTheme {
                MainScreen()
            }
        }
    }
}
