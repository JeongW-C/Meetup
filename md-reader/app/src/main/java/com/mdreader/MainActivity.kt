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

    private companion object {
        const val DRIVE_PACKAGE = "com.google.android.apps.docs"
        const val DRIVE_NOTHING_PICKED =
            "Drive에서 고른 파일이 없습니다. Drive 앱이 Google 계정에 로그인되어 있지 않다면 '파일 열기'를 이용해 주세요."

        /** .md/.txt files (Drive often labels .md as octet-stream) plus Google Docs, read as plain text. */
        val PICKER_MIME_TYPES = arrayOf(
            "text/*",
            "application/octet-stream",
            "application/x-markdown",
            "application/vnd.google-apps.document",
        )
    }

    private val vm: ReaderViewModel by viewModels()

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri, remember = persistPermission(uri))
    }

    /** Google Drive's own file picker. Its files can't be kept for later, so they skip recent files. */
    private val pickFromDrive = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            vm.open(uri, remember = persistPermission(uri))
        } else {
            // Drive also closes right away like this when no Google account is signed in.
            vm.showMessage(DRIVE_NOTHING_PICKED)
        }
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
                    onOpenFile = { openDocument.launch(PICKER_MIME_TYPES) },
                    onOpenDrive = ::openFromDrive,
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

    /**
     * Opens Google Drive's picker so Drive files are one tap away. Without the Drive app, falls back
     * to the system picker, where Drive also shows up in the side menu once the app is installed.
     */
    private fun openFromDrive() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, PICKER_MIME_TYPES)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setPackage(DRIVE_PACKAGE)
        try {
            pickFromDrive.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.showMessage("Google Drive 앱이 없어 기본 파일 선택기를 엽니다. Drive 앱을 설치하고 로그인하면 왼쪽 메뉴에 Drive가 나타납니다.")
            openDocument.launch(PICKER_MIME_TYPES)
        }
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
