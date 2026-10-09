package com.engreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import com.engreader.app.ui.AppRoot
import com.engreader.app.ui.theme.EngReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EngReaderApp).container
        setContent {
            EngReaderTheme {
                Surface {
                    AppRoot(container)
                }
            }
        }
    }
}
