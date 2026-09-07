package com.animeshahilya.neutts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.animeshahilya.neutts.ui.AppViewModel
import com.animeshahilya.neutts.ui.NeuTTSApp

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { NeuTTSApp(viewModel) }
        }
    }
}
