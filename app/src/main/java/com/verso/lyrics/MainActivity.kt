package com.verso.lyrics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verso.lyrics.ui.EditorScreen
import com.verso.lyrics.ui.EditorViewModel
import com.verso.lyrics.ui.theme.VersoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VersoTheme {
                val vm: EditorViewModel = viewModel()
                EditorScreen(viewModel = vm)
            }
        }
    }
}
