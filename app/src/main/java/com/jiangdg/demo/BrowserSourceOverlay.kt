package com.jiangdg.demo

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/**
 * A browser source that can be placed over the UVC preview.
 *
 * The source is deliberately kept as a normal Android view so it remains lightweight
 * and works with the existing demo UI. Recording/replay compositing is exposed as a
 * separate integration point; the stock CameraUVC encoder consumes the camera stream,
 * not Android view hierarchy pixels.
 */
class BrowserSourceOverlay(context: Context) : FrameLayout(context) {
    private val webView = WebView(context)
    private val content = FrameLayout(context)
    private val toolbar = LinearLayout(context)
    private var lastX = 0f
    private var lastY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var cropEnabled = false
    private var cropLeft = 0
    private var cropTop = 0
    private var cropRight = 0
    private var cropBottom = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        buildWebView()
        buildControls()
        addView(content, LayoutParams(dp(320), dp(180)))
        (content.layoutParams as LayoutParams).apply {
            gravity = Gravity.CENTER
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        content.addView(webView, LayoutParams(-1, -1))

        content.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    content.translationX += dx
                    content.translationY += dy
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    true
                }
                else -> false
            }
        }
    }

    private fun buildControls() {
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4))
        toolbar.setBackgroundColor(0xCC111111.toInt())
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.visibility = View.GONE

        addButton("↻") { content.rotation = (content.rotation + 90f) % 360f }
        addButton("Crop") {
            cropEnabled = !cropEnabled
            updateCrop()
            Toast.makeText(context, if (cropEnabled) "Crop mode: drag content" else "Crop mode off", Toast.LENGTH_SHORT).show()
        }
        addButton("URL") { showUrlDialog() }
        addButton("✕") { (parent as? ViewGroup)?.removeView(this) }

        val toolbarLp = LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        addView(toolbar, toolbarLp)
        setOnClickListener { toolbar.visibility = if (toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    }

    private fun addButton(label: String, action: () -> Unit) {
        TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener { action() }
            toolbar.addView(this)
        }
    }

    private fun showUrlDialog() {
        val input = EditText(context).apply {
            hint = "https://example.com"
            setSingleLine(true)
            setText(webView.url ?: "")
        }
        AlertDialog.Builder(context)
            .setTitle("Browser source URL")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Load") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    webView.loadUrl(url)
                }
            }
            .show()
    }

    private fun updateCrop() {
        if (!cropEnabled) {
            content.clipBounds = null
            return
        }
        val w = content.width
        val h = content.height
        if (w <= 0 || h <= 0) return
        val insetX = max(0, min(w / 4, dp(20)))
        val insetY = max(0, min(h / 4, dp(20)))
        cropLeft = insetX
        cropTop = insetY
        cropRight = insetX
        cropBottom = insetY
        content.clipBounds = Rect(cropLeft, cropTop, w - cropRight, h - cropBottom)
    }

    fun loadUrl(url: String) {
        webView.loadUrl(if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url")
    }

    fun setSourceSize(widthDp: Int, heightDp: Int) {
        content.layoutParams = content.layoutParams.apply {
            width = dp(widthDp)
            height = dp(heightDp)
        }
        content.requestLayout()
    }

    fun getSourceWebView(): WebView = webView

    /** Current UI transform, useful for a future GL/encoder compositor. */
    fun getTransformState(): TransformState = TransformState(
        content.translationX,
        content.translationY,
        content.scaleX,
        content.scaleY,
        content.rotation,
        cropLeft,
        cropTop,
        cropRight,
        cropBottom
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    data class TransformState(
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val rotation: Float,
        val cropLeft: Int,
        val cropTop: Int,
        val cropRight: Int,
        val cropBottom: Int
    )

    companion object {
        fun showUrlDialog(context: Context, parent: ViewGroup): BrowserSourceOverlay {
            val source = BrowserSourceOverlay(context)
            parent.addView(source, LayoutParams(-1, -1))
            source.showUrlDialog()
            return source
        }
    }
}
