package com.jiangdg.demo

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
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

/** OBS/PRISM-style browser source preview overlay. */
class BrowserSourceOverlay(context: Context) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private val webView = WebView(context)
    private val toolbar = LinearLayout(context)
    private val resizeHandle = TextView(context)
    private var lastX = 0f
    private var lastY = 0f
    private var cropEnabled = false
    private var cropLeft = 0
    private var cropTop = 0
    private var cropRight = 0
    private var cropBottom = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        buildWebView()
        addView(content, LayoutParams(dp(320), dp(180)).apply { gravity = Gravity.CENTER })
        buildResizeHandle()
        buildToolbar()
    }

    @SuppressLint("SetJavaScriptEnabled")
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
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    content.translationX += event.rawX - lastX
                    content.translationY += event.rawY - lastY
                    lastX = event.rawX; lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun buildResizeHandle() {
        resizeHandle.text = "↘"
        resizeHandle.textSize = 17f
        resizeHandle.gravity = Gravity.CENTER
        resizeHandle.setTextColor(Color.WHITE)
        resizeHandle.background = rounded(0xE6292C33.toInt(), 8)
        content.addView(resizeHandle, LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        resizeHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    val lp = content.layoutParams
                    lp.width = max(dp(140), lp.width + dx.toInt())
                    lp.height = max(dp(80), lp.height + dy.toInt())
                    content.layoutParams = lp
                    updateCrop()
                    lastX = event.rawX; lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun buildToolbar() {
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(dp(4), dp(3), dp(4), dp(3))
        toolbar.background = rounded(0xF21A1C21.toInt(), 10)
        addButton("WEB") { showUrlDialog() }
        addButton("↻") { content.rotation = (content.rotation + 90f) % 360f }
        addButton("Crop") {
            cropEnabled = !cropEnabled
            updateCrop()
            Toast.makeText(context, if (cropEnabled) "Crop enabled" else "Crop disabled", Toast.LENGTH_SHORT).show()
        }
        addButton("✕") { removeSelf() }
        addView(toolbar, LayoutParams(-2, dp(40)).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
    }

    private fun addButton(label: String, action: () -> Unit) {
        TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER
            background = rounded(0xFF292C34.toInt(), 8)
            setOnClickListener { action() }
            toolbar.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(3) })
        }
    }

    private fun showUrlDialog() {
        val input = EditText(context).apply {
            hint = "https://example.com"
            setSingleLine(true)
            setText(webView.url ?: "")
        }
        AlertDialog.Builder(context)
            .setTitle("Browser source")
            .setMessage("Add a webpage, chat, scoreboard or HTML overlay")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                webView.loadUrl(url)
            }
            .show()
    }

    private fun updateCrop() {
        if (!cropEnabled) {
            cropLeft = 0; cropTop = 0; cropRight = 0; cropBottom = 0
            webView.translationX = 0f; webView.translationY = 0f
            content.clipBounds = null
            return
        }
        val w = content.width
        val h = content.height
        if (w <= 0 || h <= 0) return
        cropLeft = minOf(dp(20), w / 4)
        cropTop = minOf(dp(20), h / 4)
        cropRight = cropLeft; cropBottom = cropTop
        content.clipBounds = Rect(cropLeft, cropTop, w - cropRight, h - cropBottom)
    }

    private fun removeSelf() {
        webView.stopLoading()
        webView.destroy()
        (parent as? ViewGroup)?.removeView(this)
    }

    fun loadUrl(url: String) {
        webView.loadUrl(if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url")
    }

    fun setSourceSize(widthDp: Int, heightDp: Int) {
        content.layoutParams = content.layoutParams.apply { width = dp(widthDp); height = dp(heightDp) }
        content.requestLayout()
        updateCrop()
    }

    fun getSourceWebView(): WebView = webView

    fun getTransformState(): TransformState = TransformState(
        content.translationX, content.translationY, content.scaleX, content.scaleY,
        content.rotation, cropLeft, cropTop, cropRight, cropBottom
    )

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    data class TransformState(
        val translationX: Float, val translationY: Float, val scaleX: Float, val scaleY: Float,
        val rotation: Float, val cropLeft: Int, val cropTop: Int, val cropRight: Int, val cropBottom: Int
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
