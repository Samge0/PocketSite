package com.samge.pocketsite

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import kotlin.math.roundToInt

/** In-app offline-proof preview: WebView hits the loopback server directly,
 *  bypassing browser connectivity pre-checks that block 127.0.0.1 when offline. */
class SitePreviewActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port", 8808)
        val guide = intent.getBooleanExtra("guide", false)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.mediaPlaybackRequiresUserGesture = false // hero video autoplay
        web.setBackgroundColor(0xFF070D18.toInt())

        // toolbar: back arrow + title, above the webview (plain View stack, no extras)
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).roundToInt()
        // real status-bar height (targetSdk 35 edge-to-edge: window extends under it)
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarPx =
            if (statusId > 0) resources.getDimensionPixelSize(statusId) else dp(24)
        val toolbar =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF111C30.toInt())
                setPadding(dp(4), statusBarPx, dp(4), dp(6))
            }
        val back =
            TextView(this).apply {
                text = "←"
                textSize = 20f
                setTextColor(0xFF7DD3FC.toInt())
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            }
        toolbar.addView(back, android.widget.LinearLayout.LayoutParams(dp(40), dp(44)))
        val titleView =
            TextView(this).apply {
                text = intent.getStringExtra("title") ?: "预览"
                textSize = 15f
                setTextColor(0xFFEAF2FC.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
            }
        toolbar.addView(
            titleView,
            android.widget.LinearLayout.LayoutParams(0, dp(44), 1f),
        )
        toolbar.addView(View(this), android.widget.LinearLayout.LayoutParams(dp(40), dp(44)))

        val root =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(0xFF070D18.toInt())
            }
        root.addView(
            toolbar,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val u = request?.url ?: return false
                    // keep navigation inside the local site
                    return !(u.host == "127.0.0.1" || u.host == "localhost")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(
                            this@SitePreviewActivity,
                            "加载失败：${error?.description ?: "unknown"}（请确认服务器已启动）",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        root.addView(
            web,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)
        if (guide) {
            web.loadUrl("file:///android_asset/packaging-guide.html")
        } else {
            web.loadUrl("http://127.0.0.1:$port/")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (this::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
