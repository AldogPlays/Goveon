package com.goveon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.goveon.app.ui.GoveonApp

class MainActivity : ComponentActivity() {
    private val goveonViewModel by lazy { ViewModelProvider(this)[GoveonViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GoveonApp(goveonViewModel) }
    }
    override fun onStart() { super.onStart(); goveonViewModel.resumeNetworking() }
    override fun onStop() { goveonViewModel.pauseNetworking(); super.onStop() }
}
