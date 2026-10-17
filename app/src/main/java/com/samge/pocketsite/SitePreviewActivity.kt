package com.samge.pocketsite

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * In-app viewer with a toolbar (back arrow + title) above the webview.
 * Hosted in Compose so dialogs match the rest of the app (same AlertDialog style).
 *
 * External links: confirm-before-leave dialog identical to the home/detail ↗ flow —
 * same colors, same 不再提示 checkbox, honors the app-wide 外部浏览器确认提示 setting.
 *
 * mode="guide": loads local asset (packaging guide), title 站点包格式说明
 * mode="site":  loads http://127.0.0.1:<port>/, title = site name
 */
class SitePreviewActivity : ComponentActivity() {
    private var webRef by mutableStateOf<WebView?>(null)
    private var pendingExternalUrl by mutableStateOf<String?>(null)
    private var extWarnNoMore by mutableStateOf(false)
    private var mode = "site"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "site"
        val port = intent.getIntExtra("port", 8808)
        val title = intent.getStringExtra("title") ?: "预览"

        setContent {
            PocketSiteTheme {
                ViewerScaffold(
                    mode = mode,
                    title = title,
                    port = port,
                    onWebViewReady = { webRef = it },
                    onExternalLink = { url -> pendingExternalUrl = url },
                    onClose = { finish() },
                )
                // confirm dialog — identical style to RootNav's
                pendingExternalUrl?.let { url ->
                    val ctx = LocalContext.current
                    AlertDialog(
                        onDismissRequest = { pendingExternalUrl = null },
                        containerColor = Card1,
                        titleContentColor = TextHi,
                        textContentColor = TextMid,
                        title = { Text("离开应用？") },
                        text = {
                            Column {
                                Text("即将在外部浏览器中打开：")
                                Spacer(Modifier.height(6.dp))
                                Text(url, fontSize = 12.sp, color = Sky)
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = extWarnNoMore,
                                        onCheckedChange = { extWarnNoMore = it },
                                        colors =
                                            CheckboxDefaults.colors(
                                                checkedColor = MintDeep,
                                                checkmarkColor = Color(0xFF04211B),
                                            ),
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("不再提示（可在设置中重新开启）", fontSize = 12.5.sp, color = TextMid)
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val c = applicationContext
                                    if (extWarnNoMore) SitesRepository.setExtWarningEnabled(c, false)
                                    pendingExternalUrl = null
                                    launchExternal(c, url)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MintDeep, contentColor = Color(0xFF04211B)),
                            ) { Text("打开") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingExternalUrl = null }) { Text("取消", color = TextMid) }
                        },
                    )
                }
            }
        }

        // back gesture/button: page history first, then exit viewer
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val w = webRef
                    if (w != null && w.canGoBack()) w.goBack() else finish()
                }
            },
        )
    }

    private fun launchExternal(c: android.content.Context, url: String) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "未找到浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ViewerScaffold(
    mode: String,
    title: String,
    port: Int,
    onWebViewReady: (WebView) -> Unit,
    onExternalLink: (String) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val dens = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * dens).roundToInt()

    // toolbar (View world) + webview via AndroidView, wrapped in a compose Column
    val toolbar =
        remember {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF111C30.toInt())
                setPadding(dp(6), 0, dp(6), 0)
            }
        }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val back =
            TextView(ctx).apply {
                text = "←"
                textSize = 22f
                setTextColor(0xFF7DD3FC.toInt())
                gravity = Gravity.CENTER
                setOnClickListener { onClose() }
            }
        toolbar.addView(
            back,
            LinearLayout.LayoutParams(dp(44), dp(46)),
        )
        val titleView =
            TextView(ctx).apply {
                text = title
                textSize = 16f
                setTextColor(0xFFEAF2FC.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
            }
        toolbar.addView(
            titleView,
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        toolbar.addView(
            View(ctx),
            LinearLayout.LayoutParams(dp(44), dp(46)),
        )
        // edge-to-edge: shift toolbar below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(dp(6), bars.top, dp(6), 0)
            WindowInsetsCompat.CONSUMED
        }
        onDispose {}
    }

    Column(Modifier.fillMaxSize().background(Bg0)) {
        AndroidView(
            factory = { toolbar },
            modifier = Modifier.fillMaxWidth(),
        )
        // divider
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Stroke),
        )
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    setBackgroundColor(0xFF0C1526.toInt())
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val u = request?.url ?: return false
                                if (u.host == "127.0.0.1" || u.host == "localhost") return false
                                onExternalLink(u.toString())
                                return true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    Toast.makeText(
                                        ctx,
                                        "加载失败：${error?.description ?: "unknown"}（请确认站点已启动）",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    onWebViewReady(this)
                    if (mode == "guide") {
                        loadUrl("file:///android_asset/packaging-guide.html")
                    } else {
                        loadUrl("http://127.0.0.1:$port/")
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
