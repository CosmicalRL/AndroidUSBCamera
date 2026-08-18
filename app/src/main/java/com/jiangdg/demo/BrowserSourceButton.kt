package com.jiangdg.demo

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class BrowserSourceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnClickListener { addBrowserSource() }
    }

    private fun addBrowserSource() {
        val root = rootView as? ViewGroup ?: return
        val layer = root.findViewById<ViewGroup>(resources.getIdentifier("browserSourceLayer", "id", context.packageName))
            ?: return
        BrowserSourceOverlay.showUrlDialog(context, layer)
    }
}
