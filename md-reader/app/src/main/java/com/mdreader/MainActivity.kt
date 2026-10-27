package com.mdreader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.mdreader.ui.MdReaderTheme
import com.mdreader.ui.ReaderScreen

class MainActivity : ComponentActivity() {

    private val vm: ReaderViewModel by viewModels()

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri, remember = persistPermission(uri))
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MdReaderTheme {
                ReaderScreen(
                    vm = vm,
                    onOpenFile = { openDocument.launch(arrayOf("text/*", "application/octet-stream")) },
                    onOpenTtsSettings = ::openTtsSettings,
                )
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
        askForNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { vm.open(it, remember = persistPermission(it)) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> vm.open(stream, remember = persistPermission(stream))
                    !text.isNullOrBlank() -> vm.openSharedText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
                }
            }
        }
    }

    /** Keeps read access across restarts so the file can appear in recent files. */
    private fun persistPermission(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        false
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openTtsSettings() {
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        )
        for (candidate in candidates) {
            try {
                startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next screen.
            }
        }
        vm.showMessage("음성 설정 화면을 열 수 없습니다. 기기 설정에서 '텍스트 음성 변환'을 찾아 주세요.")
    }
}
