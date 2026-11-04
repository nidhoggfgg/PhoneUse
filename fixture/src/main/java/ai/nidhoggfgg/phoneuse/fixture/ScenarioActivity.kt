package ai.nidhoggfgg.phoneuse.fixture

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.*

/** Intent-controlled scenarios, entirely separate from Phone Use authorization UI. */
class ScenarioActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var body: LinearLayout
    private var popup: PopupWindow? = null
    private var dialog: AlertDialog? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "static"
        Counters.reset(scenario)
        if (scenario == "secure") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.keepScreenOn = true
        frame = FrameLayout(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(30), dp(12), dp(12)) }
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        body.addView(label("Scenario: $scenario").apply { id = R.id.status }, LinearLayout.LayoutParams(-1, dp(48)))
        when (scenario) {
            "target_replace", "carousel" -> {
                val button = target()
                body.addView(button)
                if (scenario == "target_replace") later {
                    val index = body.indexOfChild(button)
                    body.removeView(button); body.addView(target(), index)
                    Counters.increment("mutations", "target_replace")
                } else {
                    val decoration = label("Recommendation 0")
                    frame.addView(decoration, FrameLayout.LayoutParams(-1, -1))
                    fun refresh() {
                        decoration.text = "Recommendation ${SystemClock.elapsedRealtime()}"
                        Counters.increment("mutations", "carousel")
                        handler.postDelayed({ refresh() }, 75)
                    }
                    refresh()
                }
            }
            "delayed", "skeleton", "duplicate" -> {
                val skeleton = label("Loading...")
                body.addView(skeleton, LinearLayout.LayoutParams(-1, dp(64)))
                if (scenario != "skeleton") later {
                    body.removeView(skeleton)
                    body.addView(target())
                    if (scenario == "duplicate") body.addView(target())
                    Counters.increment("mutations", "ready")
                }
            }
            "focus", "keyboard", "selection", "selected_range" -> {
                if (scenario == "focus") body.addView(Button(this).apply {
                    id = R.id.focus_control; text = "Focus to edit"
                    setOnClickListener { Counters.increment("clicks", "focus_control"); body.removeView(this); addEditor() }
                }) else addEditor()
            }
            else -> buildList(scenario)
        }
    }

    private fun later(block: () -> Unit) = handler.postDelayed(block, intent.getIntExtra("delay_ms", 1200).toLong().coerceIn(50, 30_000))
    private fun label(value: String) = TextView(this).apply { text = value; textSize = 18f; setTextColor(Color.BLACK); gravity = Gravity.CENTER_VERTICAL }
    private fun target() = Button(this).apply {
        id = R.id.target; text = "Ready target"
        setOnClickListener { Counters.increment("clicks", "target") }
    }

    private fun stable(view: View, name: String, row: Int? = null) {
        if (row != null) view.tag = "item:$row"
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (Build.VERSION.SDK_INT >= 33) info.uniqueId = "fixture:$name"
                if (row != null) {
                    info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(row, 1, 0, 1, false)
                    if (intent.getStringExtra("scenario") != "unidentified_refresh")
                        info.extras.putString("ai.nidhoggfgg.phoneuse.logical_item_id", host.tag as? String)
                }
            }
        }
    }

    private fun buildList(scenario: String) {
        val scroll = object : ScrollView(this) {
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    Counters.increment("scroll_requests", "list", action)
                return super.performAccessibilityAction(action, arguments)
            }
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt); Counters.value("scroll_y", t)
            }
        }.apply { id = R.id.list; isFillViewport = true }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stable(scroll, "list"); stable(rows, "rows")
        val titles = mutableListOf<TextView>()
        val prices = mutableListOf<TextView>()
        repeat(25) { index ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            stable(row, "item:$index", index)
            val title = label("Item %02d".format(index)); titles.add(title); stable(title, "item:$index:title")
            val price = label("Price 100"); prices.add(price); stable(price, "item:$index:price")
            row.addView(title, LinearLayout.LayoutParams(0, dp(64), 1f))
            row.addView(price, LinearLayout.LayoutParams(0, dp(64), 1f))
            rows.addView(row, LinearLayout.LayoutParams(-1, dp(64)))
        }
        scroll.addView(rows, ViewGroup.LayoutParams(-1, -2))
        if (scenario == "descendant_overlay") {
            scroll.id = View.NO_ID
            val host = FrameLayout(this).apply { id = R.id.list }
            host.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(view, info)
                    info.isScrollable = true
                    if (Build.VERSION.SDK_INT >= 33) info.uniqueId = "fixture:custom-scroll-host"
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
                override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean =
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        scroll.performAccessibilityAction(action, args) else super.performAccessibilityAction(view, action, args)
            }
            host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            host.addView(Button(this).apply { id = R.id.overlay; text = "Inner blocker" },
                FrameLayout.LayoutParams(dp(128), dp(128), Gravity.TOP or Gravity.END))
            body.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        } else body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (scenario) {
            "text_refresh", "unidentified_refresh" -> later { prices[0].text = "Price 101"; Counters.increment("mutations", "price") }
            "rebind" -> later {
                val old = titles[0].text; titles[0].text = titles[1].text; titles[1].text = old
                val oldKey = rows.getChildAt(0).tag
                rows.getChildAt(0).tag = rows.getChildAt(1).tag; rows.getChildAt(1).tag = oldKey
                Counters.increment("mutations", "rebind")
            }
            "reorder" -> later {
                val old = rows.getChildAt(0); rows.removeViewAt(0); rows.addView(old, 1)
                Counters.increment("mutations", "reorder")
            }
            "replace" -> later {
                val old = rows.getChildAt(0); rows.removeViewAt(0)
                rows.addView(label("Replacement item"), 0, LinearLayout.LayoutParams(-1, dp(64)))
                old.contentDescription = "Detached"; Counters.increment("mutations", "replace")
            }
            "move" -> later { scroll.translationY = dp(20).toFloat(); Counters.increment("mutations", "move") }
            "user_scroll" -> later { scroll.scrollTo(0, dp(100)); Counters.increment("mutations", "user_scroll") }
            "popup", "full_popup" -> frame.post { showPopup(scenario == "full_popup") }
            "modal" -> later { dialog = AlertDialog.Builder(this).setTitle("Modal blocker").setMessage("Dismiss locally").setPositiveButton("Close", null).show() }
            "same_window" -> frame.post {
                frame.addView(Button(this).apply { id = R.id.overlay; text = "Same window"; elevation = dp(8).toFloat() },
                    FrameLayout.LayoutParams(dp(128), dp(128), Gravity.TOP or Gravity.END).apply { topMargin = dp(100) })
            }
            "animation" -> prices[0].animate().alpha(.2f).setDuration(500).withEndAction {
                prices[0].animate().alpha(1f).setDuration(500).start()
            }.start()
            "pip" -> frame.post { startActivity(Intent(this, PipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun showPopup(full: Boolean) {
        val content = Button(this).apply { id = R.id.overlay; text = "Independent overlay" }
        popup = PopupWindow(content, if (full) frame.width else dp(96), if (full) frame.height else dp(96), false).apply {
            setBackgroundDrawable(ColorDrawable(Color.YELLOW)); isOutsideTouchable = false
            setTouchModal(false); elevation = dp(8).toFloat()
            showAtLocation(frame, Gravity.TOP or Gravity.END, dp(12), dp(90))
        }
    }

    private fun addEditor() {
        val editor = object : EditText(this) {
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) Counters.increment("text_requests", "search", action)
                return super.performAccessibilityAction(action, arguments)
            }
        }.apply {
            id = R.id.search; hint = "Search"; isSingleLine = true
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        body.addView(editor, LinearLayout.LayoutParams(-1, dp(64)))
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { Counters.value("text", s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        Counters.value("text", "")
        if (intent.getStringExtra("scenario") == "selection") {
            editor.setText("原始文本")
            editor.setSelection(editor.length())
            later { editor.setSelection(0); Counters.increment("mutations", "selection") }
        }
        editor.requestFocus()
        if (intent.getStringExtra("scenario") == "selected_range") {
            editor.setText("今天晴天出门")
            editor.setSelection(2, 4)
        }
        editor.post { getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) findViewById<EditText>(R.id.search)?.let { editor ->
            editor.post { getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); popup?.dismiss(); dialog?.dismiss(); super.onDestroy()
    }
}

class PipActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Platform PiP fixture"; setBackgroundColor(Color.YELLOW) })
        window.decorView.post { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(1, 2)).build()) }
    }
}
