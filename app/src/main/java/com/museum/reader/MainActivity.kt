package com.museum.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.museum.reader.ui.MuseumReaderApp
import com.museum.reader.ui.MuseumReaderViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MuseumReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MuseumReaderApp(viewModel) }
        if (savedInstanceState?.getBoolean("incomingHandled") != true) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStop() {
        viewModel.onAppBackground()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("incomingHandled", true)
        super.onSaveInstanceState(outState)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(
                    if (intent.action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT,
                )?.toString().orEmpty()
                if (text.isNotBlank()) {
                    viewModel.createFromClipboard(text)
                } else {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    uri?.let(viewModel::importFile)
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let(viewModel::importFile)
        }
    }
}
