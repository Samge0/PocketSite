package com.samge.pocketsite

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * In-app viewer with a floating close button.
 * - mode="guide": loads local asset (packaging guide) — no server needed
 * - mode="site":  loads http://127.0.0.1:<port>/ (offline-proof site preview)
 * The floating ✕ exits the whole viewer in one tap (better than back-through-history).
 */
class SitePreviewActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var closeBtn: View

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "site"
        val port = intent.getIntExtra("port", 8808)

        // root container: webview fills, close button floats top-right
        val frame = android.widget.FrameLayout(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(0xFF0C1526.toInt())
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean {
                    val u = request?.url ?: return false
                    if (mode == "guide") return false // local asset, allow
                    return !(u.host == "127.0.0.1" || u.host == "localhost")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(
                            this@SitePreviewActivity,
                            "加载失败：${error?.description ?: "unknown"}（请确认站点已启动）",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        frame.addView(
            web,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // floating close button
        val dens = resources.displayMetrics.density
        closeBtn =
            android.widget.TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(0xFF04211B.toInt())
                gravity = android.view.Gravity.CENTER
                setOnClickListener { finish() }
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xE65EEAD4.toInt())
                    }
            }
        val lp =
            android.widget.FrameLayout.LayoutParams(
                (46 * dens).toInt(),
                (46 * dens).toInt(),
            )
        lp.gravity = android.view.Gravity.END or android.view.Gravity.TOP
        lp.setMargins(0, (18 * dens).toInt(), (18 * dens).toInt(), 0)
        // soft shadow
        closeBtn.elevation = 6 * dens
        frame.addView(closeBtn, lp)

        setContentView(frame)

        if (mode == "guide") {
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
