package com.example.ebookreader

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageView: PageView
    private lateinit var emptyHint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bottomBar: View
    private lateinit var seekBar: SeekBar
    private lateinit var pageIndicator: TextView

    private lateinit var prefs: SharedPreferences

    /** 지금 열려 있는 문서의 전체 텍스트 */
    private var text: CharSequence? = null
    private var fontSizeSp = DEFAULT_FONT_SP
    private var paginateJob: Job? = null
    /** 페이지 나누기가 끝나면 보여줄 글자 위치 */
    private var pendingOffset = 0

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // 영구 권한을 못 받아도 지금 읽는 데는 문제없다
                }
                openUri(uri, 0)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        pageView = findViewById(R.id.pageView)
        emptyHint = findViewById(R.id.emptyHint)
        progress = findViewById(R.id.progress)
        bottomBar = findViewById(R.id.bottomBar)
        seekBar = findViewById(R.id.seekBar)
        pageIndicator = findViewById(R.id.pageIndicator)
        setSupportActionBar(toolbar)

        prefs = getSharedPreferences("reader", MODE_PRIVATE)
        fontSizeSp = prefs.getInt(KEY_FONT_SP, DEFAULT_FONT_SP)

        setupTouch()
        pageView.onContentSizeChanged = { paginate(currentOffset()) }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) goToPage(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val incomingUri = incomingUri(intent)
        if (savedInstanceState == null && incomingUri != null) {
            openUri(incomingUri, 0)
        } else {
            // 마지막으로 읽던 파일과 위치를 이어서 연다 (화면 회전 포함).
            // 다른 앱이 잠깐만 빌려준 파일은 다시 못 열 수 있으니 실패해도 조용히 넘어간다.
            prefs.getString(KEY_URI, null)?.let { openUri(Uri.parse(it), prefs.getInt(KEY_OFFSET, 0), quiet = true) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri(intent)?.let { openUri(it, 0) }
    }

    /** 파일 관리자·드라이브의 "다음으로 열기"(VIEW) 또는 "공유/보내기"(SEND)로 받은 파일 */
    @Suppress("DEPRECATION")
    private fun incomingUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> {
            openDocument.launch(OPEN_MIME_TYPES)
            true
        }
        R.id.action_font_smaller -> {
            changeFontSize(-FONT_STEP_SP); true
        }
        R.id.action_font_larger -> {
            changeFontSize(FONT_STEP_SP); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** 볼륨 키·키보드로도 페이지를 넘기고, 키보드 +/- 로 글자 크기를 바꿀 수 있다 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (text != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> { nextPage(); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_DPAD_LEFT -> { prevPage(); return true }
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_NUMPAD_ADD -> { changeFontSize(FONT_STEP_SP); return true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { changeFontSize(-FONT_STEP_SP); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openUri(uri: Uri, offset: Int, quiet: Boolean = false) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            var error = R.string.open_failed
            val doc = try {
                // 구글 드라이브 파일은 여기서 내려받느라 시간이 걸릴 수 있다
                withContext(Dispatchers.IO) { DocumentLoader.load(this@MainActivity, uri) }
            } catch (e: DocumentLoader.NotTextException) {
                error = R.string.open_failed_not_text
                null
            } catch (e: Exception) {
                null
            }
            progress.visibility = View.GONE
            if (doc == null) {
                if (!quiet) Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                prefs.edit().remove(KEY_URI).remove(KEY_OFFSET).apply()
                return@launch
            }
            text = doc.text
            pageView.show(null, 0)
            supportActionBar?.title = doc.name
            emptyHint.visibility = View.GONE
            prefs.edit().putString(KEY_URI, uri.toString()).putInt(KEY_OFFSET, offset).apply()
            paginate(offset)
        }
    }

    private fun changeFontSize(deltaSp: Int) {
        val newSize = (fontSizeSp + deltaSp).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        if (newSize == fontSizeSp) return
        fontSizeSp = newSize
        prefs.edit().putInt(KEY_FONT_SP, fontSizeSp).apply()
        Toast.makeText(this, getString(R.string.font_size_toast, fontSizeSp), Toast.LENGTH_SHORT).show()
        // 지금 보던 글자가 들어 있는 페이지로 다시 이동
        paginate(currentOffset())
    }

    /** 지금 화면 첫 글자의 위치 */
    private fun currentOffset(): Int =
        pageView.pages?.pageStartOffset(pageView.page) ?: pendingOffset

    /** 현재 글자 크기와 화면 크기로 다시 페이지를 나눈 뒤, offset 글자가 있는 페이지를 보여준다 */
    private fun paginate(offset: Int) {
        val content = text ?: return
        pendingOffset = offset
        paginateJob?.cancel()
        pageView.doOnLayout {
            val width = pageView.contentWidth
            val height = pageView.contentHeight
            if (width <= 0 || height <= 0) return@doOnLayout
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat(), resources.displayMetrics,
                )
                color = MaterialColors.getColor(pageView, com.google.android.material.R.attr.colorOnSurface)
            }
            paginateJob = lifecycleScope.launch {
                progress.visibility = View.VISIBLE
                val result = withContext(Dispatchers.Default) {
                    Paginator.paginate(content, paint, width, height, LINE_SPACING)
                }
                progress.visibility = View.GONE
                pageView.show(result, result.pageForOffset(offset))
                seekBar.max = result.pageCount - 1
                onPageShown()
            }
        }
    }

    private fun goToPage(page: Int) {
        val pages = pageView.pages ?: return
        val target = page.coerceIn(0, pages.pageCount - 1)
        if (target == pageView.page) return
        pageView.show(pages, target)
        onPageShown()
    }

    private fun nextPage() = goToPage(pageView.page + 1)
    private fun prevPage() = goToPage(pageView.page - 1)

    private fun onPageShown() {
        val pages = pageView.pages ?: return
        pageIndicator.text = getString(R.string.page_indicator, pageView.page + 1, pages.pageCount)
        seekBar.progress = pageView.page
        prefs.edit().putInt(KEY_OFFSET, pages.pageStartOffset(pageView.page)).apply()
    }

    /** 왼쪽 탭 = 이전, 오른쪽 탭 = 다음, 가운데 탭 = 메뉴 숨기기/보이기, 좌우로 밀기 = 넘기기 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val w = pageView.width
                when {
                    e.x < w / 3f -> prevPage()
                    e.x > w * 2 / 3f -> nextPage()
                    else -> toggleBars()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityX) < abs(velocityY)) return false
                if (velocityX < 0) nextPage() else prevPage()
                return true
            }
        })
        pageView.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun toggleBars() {
        val visible = toolbar.visibility == View.VISIBLE
        val newVisibility = if (visible) View.GONE else View.VISIBLE
        toolbar.visibility = newVisibility
        bottomBar.visibility = newVisibility
        // 화면 높이가 바뀌면 onContentSizeChanged에서 다시 나눈다
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_OFFSET = "offset"
        private const val KEY_FONT_SP = "font_sp"

        private const val DEFAULT_FONT_SP = 18
        private const val MIN_FONT_SP = 12
        private const val MAX_FONT_SP = 40
        private const val FONT_STEP_SP = 2
        private const val LINE_SPACING = 1.5f

        /**
         * 파일 고르기 화면에서 고를 수 있는 형식.
         * .md 파일은 기기·구글 드라이브에 따라 octet-stream 이나 application/x-markdown 으로 잡히고,
         * 구글 문서는 드라이브가 텍스트로 변환해 줄 때 열 수 있다.
         */
        private val OPEN_MIME_TYPES = arrayOf(
            "text/*",
            "application/octet-stream",
            "application/markdown",
            "application/x-markdown",
            "application/vnd.google-apps.document",
        )
    }
}
