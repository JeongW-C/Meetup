package com.example.webtoonviewer

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var list: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomBar: View
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var emptyHint: TextView
    private lateinit var progress: ProgressBar

    private lateinit var prefs: SharedPreferences
    private lateinit var decoder: TileDecoder
    private lateinit var adapter: TileAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private var pages: List<Page> = emptyList()
    private var loadJob: Job? = null
    private var barsVisible = true
    private var seeking = false

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach(::persistPermission)
                openSource(Source.Files(uris), ImagePosition(0, 0f))
            }
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistPermission(uri)
                openSource(Source.Folder(uri), ImagePosition(0, 0f))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        list = findViewById(R.id.list)
        toolbar = findViewById(R.id.toolbar)
        bottomBar = findViewById(R.id.bottomBar)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        emptyHint = findViewById(R.id.emptyHint)
        progress = findViewById(R.id.progress)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        prefs = getSharedPreferences("viewer", MODE_PRIVATE)
        decoder = TileDecoder(this)
        adapter = TileAdapter(lifecycleScope, decoder)
        layoutManager = PrefetchLayoutManager()
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)

        setupInsets()
        setupTouch()
        setupSeekBar()
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePositionUi()
                // 손가락으로 아래로 읽어 내려가면 메뉴를 치워서 화면을 넓게 쓴다
                if (barsVisible && dy > 0 && recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) setBarsVisible(false)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) savePosition()
            }
        })
        // 화면 폭이 바뀌면(분할 화면 등) 같은 위치를 유지한 채로 다시 맞춘다
        list.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && oldRight - oldLeft > 0 && pages.isNotEmpty()) {
                val pos = currentPosition()
                list.post { applyLayout(pos) }
            }
        }

        val incoming = incomingUris(intent)
        if (savedInstanceState == null && incoming.isNotEmpty()) {
            openSource(Source.Files(incoming), ImagePosition(0, 0f))
        } else {
            // 마지막으로 보던 이미지와 위치를 이어서 연다 (화면 회전 포함).
            // 다른 앱이 잠깐만 빌려준 파일은 다시 못 열 수 있으니 실패해도 조용히 넘어간다.
            Source.restore(prefs)?.let {
                val pos = ImagePosition(prefs.getInt(KEY_POS_IMAGE, 0), prefs.getFloat(KEY_POS_FRACTION, 0f))
                openSource(it, pos, quiet = true)
            }
        }
        updateEmptyState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val incoming = incomingUris(intent)
        if (incoming.isNotEmpty()) openSource(Source.Files(incoming), ImagePosition(0, 0f))
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        decoder.shutdown()
    }

    /** 갤러리·파일 관리자의 "다음으로 열기"(VIEW) 또는 "공유/보내기"(SEND, SEND_MULTIPLE)로 받은 이미지 */
    private fun incomingUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
            // 보통은 Uri 하나 또는 Uri 목록이지만, 문자열로 넘기는 앱(adb 테스트 포함)도 받아 준다
            @Suppress("DEPRECATION")
            when (val extra = intent.extras?.get(Intent.EXTRA_STREAM)) {
                is Uri -> listOf(extra)
                is String -> listOf(Uri.parse(extra))
                is Collection<*> -> extra.mapNotNull { item ->
                    when (item) {
                        is Uri -> item
                        is String -> Uri.parse(item)
                        else -> null
                    }
                }
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open_images -> {
            pickImages.launch(arrayOf("image/*")); true
        }
        R.id.action_open_folder -> {
            pickFolder.launch(null); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** 볼륨 키·키보드로 한 화면씩 스크롤. 메뉴 버튼 등에 포커스가 있어도 먼저 받도록 dispatchKeyEvent 에서 처리한다. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleScrollKey(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_UP && pages.isNotEmpty() && event.keyCode in SCROLL_KEYS) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleScrollKey(keyCode: Int): Boolean {
        if (pages.isNotEmpty()) {
            val step = (list.height * SCROLL_STEP).roundToInt()
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> { list.smoothScrollBy(0, step); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_DPAD_UP -> { list.smoothScrollBy(0, -step); return true }
                KeyEvent.KEYCODE_MOVE_HOME -> { scrollToY(0); return true }
                KeyEvent.KEYCODE_MOVE_END -> { scrollToY(Int.MAX_VALUE); return true }
            }
        }
        return false
    }

    private fun persistPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // 영구 권한을 못 받아도 지금 보는 데는 문제없다
        }
    }

    private fun openSource(source: Source, position: ImagePosition, quiet: Boolean = false) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            emptyHint.visibility = View.GONE
            try {
                val uris = when (source) {
                    is Source.Files -> source.uris
                    is Source.Folder -> PageLoader.listFolder(this@MainActivity, source.tree)
                }
                val loaded = PageLoader.load(this@MainActivity, uris)
                if (loaded.isEmpty()) {
                    if (!quiet) Toast.makeText(this@MainActivity, R.string.no_images, Toast.LENGTH_LONG).show()
                    return@launch
                }
                decoder.reset()
                pages = loaded
                source.save(prefs)
                supportActionBar?.title = when (source) {
                    is Source.Folder -> folderName(source.tree)
                    is Source.Files -> if (loaded.size == 1) loaded.first().name else getString(R.string.n_images, loaded.size)
                }
                supportActionBar?.subtitle = null
                list.doOnLayout { applyLayout(position) }
                setBarsVisible(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!quiet) {
                    Toast.makeText(this@MainActivity, getString(R.string.open_failed, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                progress.visibility = View.GONE
                updateEmptyState()
            }
        }
    }

    private fun folderName(tree: Uri): String = Source.folderName(tree) ?: getString(R.string.app_name)

    /** 현재 화면 폭에 맞춰 조각을 다시 나누고 [position] 으로 이동한다 */
    private fun applyLayout(position: ImagePosition) {
        val width = list.width
        if (width <= 0 || pages.isEmpty()) return
        decoder.clearCache()
        val layout = TileLayout(pages.map { it.size }, width)
        adapter.submit(pages, layout)
        scrollToY(layout.yOf(position))
        list.post { updatePositionUi() }
    }

    private fun scrollToY(y: Int) {
        val layout = adapter.layout ?: return
        if (layout.tiles.isEmpty()) return
        val target = y.coerceIn(0, maxScrollY(layout))
        val index = layout.tileIndexAt(target)
        layoutManager.scrollToPositionWithOffset(index, -(target - layout.tiles[index].top))
    }

    /** 이미지 영역(위쪽 여백 아래) 맨 위가 전체 이미지 묶음의 몇 픽셀 지점인지 */
    private fun currentY(): Int {
        val layout = adapter.layout ?: return 0
        val child = layoutManager.getChildAt(0) ?: return 0
        val position = layoutManager.getPosition(child)
        if (position < 0 || position >= layout.tiles.size) return 0
        return layout.tiles[position].top - (child.top - list.paddingTop)
    }

    private fun viewportHeight() = maxOf(1, list.height - list.paddingTop)

    private fun maxScrollY(layout: TileLayout) = maxOf(0, layout.totalHeight - viewportHeight())

    private fun currentPosition(): ImagePosition = adapter.layout?.positionOf(currentY()) ?: ImagePosition(0, 0f)

    private fun savePosition() {
        if (pages.isEmpty() || adapter.layout == null) return
        val pos = currentPosition()
        prefs.edit().putInt(KEY_POS_IMAGE, pos.imageIndex).putFloat(KEY_POS_FRACTION, pos.fraction).apply()
    }

    private fun updatePositionUi() {
        val layout = adapter.layout ?: return
        val scrollable = maxScrollY(layout)
        val y = currentY()
        val ratio = if (scrollable > 0) (y.toFloat() / scrollable).coerceIn(0f, 1f) else 1f
        if (!seeking) seekBar.progress = (ratio * seekBar.max).roundToInt()
        // 화면 가운데에 걸친 이미지를 "몇 번째 장"으로 보여준다
        val image = layout.positionOf(y + viewportHeight() / 2).imageIndex
        positionText.text = getString(R.string.position_format, (ratio * 100).roundToInt(), image + 1, pages.size)
        supportActionBar?.subtitle = if (pages.size > 1) pages[image].name else null
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val layout = adapter.layout ?: return
                val scrollable = maxScrollY(layout)
                scrollToY((scrollable.toLong() * value / bar.max).toInt())
            }

            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) { seeking = false; savePosition() }
        })
    }

    /** 화면을 한 번 탭하면 위/아래 메뉴를 숨기거나 보인다 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setBarsVisible(!barsVisible)
                return true
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun setBarsVisible(visible: Boolean) {
        // 아무것도 안 열었으면 열기 버튼이 늘 보여야 한다
        val show = visible || pages.isEmpty()
        barsVisible = show
        toolbar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show && pages.isNotEmpty()) View.VISIBLE else View.GONE
        val controller = WindowCompat.getInsetsController(window, root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            updatePositionUi()
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * 메뉴는 이미지 위에 겹쳐 띄우고, 상태 표시줄/내비게이션 바만 피한다.
     * 맨 처음 화면에서 이미지 윗부분이 메뉴에 가리지 않도록 목록 위쪽에 메뉴 높이만큼 여백을 둔다
     * (clipToPadding=false 라 스크롤하면 그 여백까지 이미지가 채운다).
     */
    private fun setupInsets() {
        val padLeft = bottomBar.paddingLeft
        val padRight = bottomBar.paddingRight
        val padBottom = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            bottomBar.updatePadding(
                left = padLeft + bars.left,
                right = padRight + bars.right,
                bottom = padBottom + bars.bottom,
            )
            insets
        }
        list.clipToPadding = false
        toolbar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && list.paddingTop != h) {
                val y = currentY()
                list.updatePadding(top = h)
                if (adapter.layout != null) list.post { scrollToY(y) }
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun updateEmptyState() {
        val empty = pages.isEmpty()
        emptyHint.visibility = if (empty && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        if (empty) setBarsVisible(true)
    }

    /** 화면 위아래로 한 화면씩 미리 읽어 두어 스크롤할 때 빈칸이 덜 보이게 한다 */
    private inner class PrefetchLayoutManager : LinearLayoutManager(this@MainActivity) {
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
            extraLayoutSpace[0] = list.height
            extraLayoutSpace[1] = list.height
        }
    }

    companion object {
        private const val KEY_POS_IMAGE = "posImage"
        private const val KEY_POS_FRACTION = "posFraction"
        private const val SCROLL_STEP = 0.85f
        private val SCROLL_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        )
    }
}
