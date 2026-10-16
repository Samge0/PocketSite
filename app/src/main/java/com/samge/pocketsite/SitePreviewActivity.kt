package com.samge.pocketsite

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/** In-app offline-proof preview: WebView hits the loopback server directly,
 *  bypassing browser connectivity pre-checks that block 127.0.0.1 when offline. */
class SitePreviewActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port", 8808)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.mediaPlaybackRequiresUserGesture = false // hero video autoplay
        web.setBackgroundColor(0xFF070D18.toInt())
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
        setContentView(web)
        web.loadUrl("http://127.0.0.1:$port/")
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
