package com.samge.pocketsite

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import android.webkit.WebView
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketSiteTheme {
                RootNav()
            }
        }
    }
}

/** Two screens: site list (home) and per-site detail. Also a settings screen. */
enum class Screen { HOME, DETAIL, SETTINGS }

class AppViewModel : ViewModel() {
    var screen by mutableStateOf(Screen.HOME)
    var detailSite by mutableStateOf("")

    // home state
    var sites by mutableStateOf<List<SiteInfo>>(emptyList())
    var rootPath by mutableStateOf("")
    var busy by mutableStateOf(false)
    var busyPhase by mutableStateOf("")
    var busyPercent by mutableStateOf(-1)
    var toast by mutableStateOf<String?>(null)
    var showImportSheet by mutableStateOf(false)
    var showUrlDialog by mutableStateOf(false)
    var confirmDelete by mutableStateOf<String?>(null)
    var showUpdateSheet by mutableStateOf(false)
    var showUpdateUrlDialog by mutableStateOf(false)
    var showGuide by mutableStateOf(false)

    // detail state
    var detailAutostart by mutableStateOf(false)
    var showUpdateDialog by mutableStateOf(false)

    fun toast(s: String) {
        toast = s
    }

    fun refresh(ctx: Context) {
        val running = SiteServerService.runningSites()
        val ports = running.associateWith { SiteServerService.portOf(it) ?: 0 }
        sites =
            SitesRepository.listSites(ctx).map {
                it.copy(running = it.name in running, port = ports[it.name] ?: it.port)
            }
        rootPath = SitesRepository.rootDir(ctx).absolutePath
        if (detailSite.isNotEmpty()) {
            detailAutostart = SitesRepository.autostart(ctx, detailSite)
        }
    }

    fun openDetail(ctx: Context, name: String) {
        detailSite = name
        detailAutostart = SitesRepository.autostart(ctx, name)
        screen = Screen.DETAIL
    }

    // ---- lifecycle of a single site server ----

    /** Sites with a start pending (intent fired, server not yet up). */
    var startingSites by mutableStateOf<Set<String>>(emptySet())
        private set

    fun startSite(ctx: Context, name: String) {
        if (name in startingSites || SiteServerService.isRunning(name)) return
        val site = sites.firstOrNull { it.name == name } ?: return
        val port = SitesRepository.sitePort(ctx, name)
        startingSites = startingSites + name
        val intent =
            Intent(ctx, SiteServerService::class.java)
                .setAction(SiteServerService.ACTION_START_SITE)
                .putExtra(SiteServerService.EXTRA_SITE, name)
                .putExtra(SiteServerService.EXTRA_PORT, port)
        ctx.startForegroundService(intent)
        // poll until the socket is actually accepting, then clear pending state
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val deadline = System.currentTimeMillis() + 8000
            var up = false
            while (System.currentTimeMillis() < deadline) {
                if (SiteServerService.isRunning(name)) {
                    up = true
                    break
                }
                kotlinx.coroutines.delay(150)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                startingSites = startingSites - name
                refresh(ctx)
                if (up) toast("$name 已就绪 · 端口 $port")
                else toast("$name 启动超时，请重试")
            }
        }
    }

    fun stopSite(ctx: Context, name: String) {
        val intent =
            Intent(ctx, SiteServerService::class.java)
                .setAction(SiteServerService.ACTION_STOP_SITE)
                .putExtra(SiteServerService.EXTRA_SITE, name)
        ctx.startService(intent)
        startingSites = startingSites - name
        refresh(ctx)
    }

    // ---- import ----

    fun importZip(ctx: Context, picked: Uri, updateOf: String? = null) {
        busy = true
        busyPercent = -1
        busyPhase = if (updateOf != null) "读取新版本包" else "读取 ZIP"
        val tmp = SitesRepository.copyUriToCache(ctx, picked, "import.zip")
        if (tmp == null) {
            busy = false
            toast("无法读取所选文件")
            return
        }
        val cb: (Boolean, String) -> Unit = { ok, msg ->
            busy = false
            tmp.delete()
            toast(msg)
            refresh(ctx)
        }
        if (updateOf != null) {
            SitesRepository.updateSiteAsync(ctx, tmp, updateOf, cb)
        } else {
            val name = SitesRepository.sanitizeName(tmp.nameWithoutExtension.ifEmpty { "site" })
            SitesRepository.importZipAsync(ctx, tmp, name, cb)
        }
    }

    fun importUrl(ctx: Context, url: String, name: String) {
        showUrlDialog = false
        busy = true
        busyPhase = "下载站点包"
        busyPercent = 0
        SitesRepository.importUrlAsync(ctx, url, name, { p -> busyPercent = p }) { ok, msg ->
            busy = false
            busyPercent = -1
            toast(msg)
            refresh(ctx)
        }
    }

    /** Update existing site content from URL (keeps port/autostart prefs). */
    fun updateSiteFromUrl(ctx: Context, name: String, url: String) {
        busy = true
        busyPhase = "下载新版本包"
        busyPercent = 0
        SitesRepository.importUrlAsync(ctx, url, name, { p -> busyPercent = p }) { ok, msg ->
            busy = false
            busyPercent = -1
            toast(msg)
            refresh(ctx)
        }
    }

    fun deleteSite(ctx: Context, name: String) {
        confirmDelete = null
        if (SiteServerService.isRunning(name)) stopSite(ctx, name)
        busy = true
        busyPercent = -1
        busyPhase = "删除 $name"
        SitesRepository.deleteSiteAsync(ctx, name) { _, msg ->
            busy = false
            toast(msg)
            if (detailSite == name) screen = Screen.HOME
            refresh(ctx)
        }
    }

    fun changePort(ctx: Context, name: String, port: Int) {
        if (SiteServerService.isRunning(name)) {
            toast("请先停止站点再改端口")
            return
        }
        SitesRepository.setSitePort(ctx, name, port)
        refresh(ctx)
        toast("端口已改为 $port")
    }

    fun setAutostart(ctx: Context, name: String, v: Boolean) {
        SitesRepository.setAutostart(ctx, name, v)
        detailAutostart = v
    }

    fun changeRoot(ctx: Context, newRoot: File) {
        busy = true
        busyPercent = 0
        busyPhase = "准备迁移"
        val hadRunning = SiteServerService.runningSites().toList()
        hadRunning.forEach { stopSite(ctx, it) }
        SitesRepository.migrateAsync(
            ctx,
            newRoot,
            { phase, pct ->
                busyPhase = phase
                busyPercent = pct
            },
        ) { ok, msg ->
            busy = false
            busyPercent = -1
            toast(msg)
            refresh(ctx)
        }
    }
}

@Composable
fun RootNav(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current

    // system back / gesture: pop to home instead of exiting the app
    androidx.activity.compose.BackHandler(enabled = vm.screen != Screen.HOME) {
        vm.screen = Screen.HOME
    }

    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm)
        Screen.DETAIL -> DetailScreen(vm)
        Screen.SETTINGS -> SettingsScreen(vm)
    }
    // global toast
    LaunchedEffect(vm.toast) {
        if (vm.toast != null) {
            kotlinx.coroutines.delay(2800)
            vm.toast = null
        }
    }
    // first launch: autostart sites marked 随应用启动
    LaunchedEffect(Unit) {
        vm.refresh(ctx)
        SitesRepository.autostartSites(ctx).forEach { name ->
            vm.startSite(ctx, name)
        }
    }
    // periodic refresh of running states
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            vm.refresh(ctx)
        }
    }

    // delete confirm — global so it opens over whichever screen triggered it
    vm.confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { vm.confirmDelete = null },
            containerColor = Card1,
            titleContentColor = TextHi,
            textContentColor = TextMid,
            title = { Text("删除站点？") },
            text = { Text("「$name」及其端口设置将被删除，此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = { vm.deleteSite(ctx, name) },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color(0xFF2B0B12)),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { vm.confirmDelete = null }) { Text("取消", color = TextMid) } },
        )
    }

    // packaging guide in in-app webview (local asset, works offline)
    if (vm.showGuide) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        setBackgroundColor(0xFF0C1526.toInt())
                        loadUrl("file:///android_asset/packaging-guide.html")
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // floating close
            Button(
                onClick = { vm.showGuide = false },
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp)
                        .size(46.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xE65EEAD4), contentColor = Color(0xFF04211B)),
                contentPadding = PaddingValues(0.dp),
            ) { Text("✕", fontSize = 16.sp) }
        }
    }

    // global busy overlay
    if (vm.busy) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xAA04070D)),
            contentAlignment = Alignment.Center,
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(24.dp)) {
                Column(
                    Modifier.padding(28.dp).width(250.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (vm.busyPercent >= 0) {
                        val p = vm.busyPercent / 100f
                        CircularProgressIndicator(progress = { p }, color = Mint, strokeWidth = 5.dp, modifier = Modifier.size(54.dp))
                    } else {
                        CircularProgressIndicator(color = Mint, strokeWidth = 5.dp, modifier = Modifier.size(54.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(vm.busyPhase, color = TextHi, fontSize = 14.sp)
                    if (vm.busyPercent >= 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("${vm.busyPercent}%", color = TextMid, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    vm.toast?.let { t ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Card(
                modifier = Modifier.padding(24.dp),
                shape = RoundedCornerShape(999.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE6162338)),
            ) {
                Text(
                    t,
                    color = TextHi,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

// ================= HOME =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current

    LaunchedEffect(Unit) { vm.refresh(ctx) }

    val zipPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) vm.importZip(ctx, uri)
        }

    Scaffold(containerColor = Bg0) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize(),
        ) {
            // ---- header ----
            Row(
                Modifier.fillMaxWidth().padding(20.dp, 14.dp, 12.dp, 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("PocketSite", style = MaterialTheme.typography.headlineMedium, color = TextHi)
                    Text("静态站点 · 离线演示 · 多站并行", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
                IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                    Text("⚙", fontSize = 22.sp, color = TextMid)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp, 6.dp, 20.dp, 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.sites.isEmpty()) {
                    item {
                        // empty-state guide
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Card0),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.padding(28.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(MintDeep, Sky))),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("＋", fontSize = 30.sp, color = Color(0xFF04211B))
                                }
                                Spacer(Modifier.height(14.dp))
                                Text("还没有静态站点", color = TextHi, fontSize = 16.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "导入一个站点 ZIP 包，或输入 URL 在线拉取。\n每个站点会自动分配独立端口，互不冲突。",
                                    color = TextMid,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                )
                                Spacer(Modifier.height(18.dp))
                                GradientButton("导入第一个站点", Modifier.fillMaxWidth(0.7f).height(46.dp)) {
                                    vm.showImportSheet = true
                                }
                            }
                        }
                    }
                }
                items(vm.sites, key = { it.name }) { site ->
                    SiteRow(
                        site = site,
                        starting = site.name in vm.startingSites,
                        onClick = { vm.openDetail(ctx, site.name) },
                        onPreview = {
                            val intent =
                                android.content.Intent(ctx, SitePreviewActivity::class.java)
                                    .putExtra("port", SiteServerService.portOf(site.name) ?: site.port)
                            ctx.startActivity(intent)
                        },
                        onBrowser = {
                            val port = SiteServerService.portOf(site.name) ?: site.port
                            try {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        Uri.parse("http://127.0.0.1:$port/"),
                                    ),
                                )
                            } catch (e: Exception) {
                                vm.toast("未找到浏览器")
                            }
                        },
                        onCopy = {
                            val port = SiteServerService.portOf(site.name) ?: site.port
                            clip.setText(AnnotatedString("http://127.0.0.1:$port/"))
                            vm.toast("已复制 http://127.0.0.1:$port/")
                        },
                        onToggle = { on ->
                            if (on) vm.startSite(ctx, site.name) else vm.stopSite(ctx, site.name)
                        },
                    )
                }
            }
        }

        // ---- FAB import ----
        if (vm.sites.isNotEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Button(
                    onClick = { vm.showImportSheet = true },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp)
                            .size(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF04211B)),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MintDeep, Mint)), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋", fontSize = 26.sp) }
                }
            }
        }

        // ---- import chooser sheet ----
        if (vm.showImportSheet) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x8804070D))
                    .clickable { vm.showImportSheet = false },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Card1),
                    shape = RoundedCornerShape(24.dp, 24.dp, 0.dp, 0.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = false) {},
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("导入站点", color = TextHi, fontSize = 17.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("选择站点包来源", color = TextLow, fontSize = 12.sp)
                            }
                            // guide button: what is a valid site zip?
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Card0)
                                    .clickable {
                                        vm.showImportSheet = false
                                        vm.showGuide = true
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("?", fontSize = 16.sp, color = Sky)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        ImportOption(
                            title = "从本地 ZIP 导入",
                            desc = "选择手机中的 .zip 站点包",
                            onClick = {
                                vm.showImportSheet = false
                                zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                        )
                        HorizontalDivider(color = Stroke)
                        ImportOption(
                            title = "从 URL 拉取",
                            desc = "输入链接在线下载站点包",
                            onClick = {
                                vm.showImportSheet = false
                                vm.showUrlDialog = true
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ---- url dialog ----
        if (vm.showUrlDialog) {
            var urlText by remember { mutableStateOf("") }
            var nameText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { vm.showUrlDialog = false },
                containerColor = Card1,
                titleContentColor = TextHi,
                textContentColor = TextMid,
                title = { Text("从 URL 拉取站点包") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = { Text("ZIP 链接") },
                            placeholder = { Text("https://example.com/site.zip", color = TextLow) },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = nameText,
                            onValueChange = { nameText = it },
                            label = { Text("站点名（留空自动取文件名）") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val u = urlText.trim()
                            if (u.startsWith("http")) {
                                val n =
                                    SitesRepository.sanitizeName(
                                        nameText.ifEmpty {
                                            u.substringAfterLast('/').substringBefore(".zip").ifEmpty { "site" }
                                        },
                                    )
                                vm.importUrl(ctx, u, n)
                            } else {
                                vm.toast("请输入 http(s) 链接")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MintDeep, contentColor = Color(0xFF04211B)),
                    ) { Text("拉取") }
                },
                dismissButton = { TextButton(onClick = { vm.showUrlDialog = false }) { Text("取消", color = TextMid) } },
            )
        }
    }
}

@Composable
fun ImportOption(title: String, desc: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp, 14.dp),
    ) {
        Text(title, color = TextHi, fontSize = 15.sp)
        Spacer(Modifier.height(2.dp))
        Text(desc, color = TextLow, fontSize = 12.sp)
    }
}

/** Home list item: name, port, running dot + switch, 3 icon actions. Tap -> detail. */
@Composable
fun SiteRow(
    site: SiteInfo,
    starting: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    onBrowser: () -> Unit,
    onCopy: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val glow by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "glow",
    )
    val busy = starting
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = when {
                    site.running -> Card1
                    busy -> Color(0xFF182740)
                    else -> Card0
                },
            ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp, 14.dp, 12.dp, 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // letter avatar
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            when {
                                site.running -> Brush.linearGradient(listOf(MintDeep, Mint))
                                busy -> Brush.linearGradient(listOf(Color(0xFF1D6A5E), Color(0xFF2AA795)))
                                else -> Brush.linearGradient(listOf(Card1, Stroke))
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        site.name.take(1).uppercase(),
                        fontSize = 17.sp,
                        color = if (site.running || busy) Color(0xFF04211B) else TextMid,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        site.name,
                        fontSize = 15.sp,
                        color = TextHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            site.running -> {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .scale(glow)
                                        .background(Mint, CircleShape),
                                )
                            }
                            busy -> {
                                CircularProgressIndicator(
                                    color = Mint,
                                    strokeWidth = 1.8.dp,
                                    modifier = Modifier.size(9.dp),
                                )
                            }
                            else -> {
                                Box(Modifier.size(7.dp).background(TextLow, CircleShape))
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                site.running -> "运行中 · :${site.port}"
                                busy -> "启动中 · :${site.port}"
                                else -> "已停止 · :${site.port}"
                            },
                            fontSize = 12.sp,
                            color = when {
                                site.running -> Mint
                                busy -> Sky
                                else -> TextMid
                            },
                        )
                        if (!site.hasIndex) {
                            Spacer(Modifier.width(8.dp))
                            Text("无 index", fontSize = 10.sp, color = Amber)
                        }
                    }
                }
                // start/stop switch
                Switch(
                    checked = site.running || busy,
                    onCheckedChange = { onToggle(it) },
                    enabled = !busy,
                    colors =
                        SwitchDefaults.colors(
                            checkedTrackColor = if (busy) Color(0xFF1D6A5E) else MintDeep,
                            checkedThumbColor = if (busy) Color(0xFF9CCFC5) else Color(0xFF04211B),
                            uncheckedTrackColor = Stroke,
                            uncheckedThumbColor = TextLow,
                            disabledCheckedTrackColor = Color(0xFF1D6A5E),
                            disabledCheckedThumbColor = Color(0xFF9CCFC5),
                        ),
                    modifier = Modifier.size(42.dp, 26.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconAction("🌐", "WebView 预览", enabled = site.running, onClick = onPreview)
                IconAction("↗", "系统浏览器打开", enabled = site.running, onClick = onBrowser)
                IconAction("⧉", "复制链接", enabled = site.running, onClick = onCopy)
            }
        }
    }
}

@Composable
fun IconAction(glyph: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Card1 else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp, 7.dp),
    ) {
        Text(
            glyph,
            fontSize = 15.sp,
            color = if (enabled) Sky else TextLow,
        )
    }
}

// ================= DETAIL =================

@Composable
fun DetailScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val site = vm.sites.firstOrNull { it.name == vm.detailSite }
    val clip = LocalClipboardManager.current

    LaunchedEffect(Unit) { vm.refresh(ctx) }

    val updatePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) vm.importZip(ctx, uri, updateOf = vm.detailSite)
        }

    Scaffold(containerColor = Bg0) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("←", fontSize = 22.sp, color = Sky, modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.screen = Screen.HOME }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("站点详情", style = MaterialTheme.typography.titleLarge, color = TextHi)
                }
            }
            if (site == null) {
                item {
                    Text("站点不存在或已被删除", color = TextMid)
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Card0), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(
                                            if (site.running) Brush.linearGradient(listOf(MintDeep, Mint)) else Brush.linearGradient(listOf(Card1, Stroke)),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        site.name.take(1).uppercase(),
                                        fontSize = 21.sp,
                                        color = if (site.running) Color(0xFF04211B) else TextMid,
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(site.name, fontSize = 18.sp, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "${SitesRepository.humanSize(site.sizeBytes)} · ${site.fileCount} 个文件" +
                                            if (site.hasIndex) "" else " · 缺少 index.html",
                                        fontSize = 12.sp,
                                        color = if (site.hasIndex) TextMid else Amber,
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            val detailBusy = site.name in vm.startingSites
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("启动站点", fontSize = 15.sp, color = TextHi)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (detailBusy) {
                                            CircularProgressIndicator(
                                                color = Mint,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(12.dp),
                                            )
                                            Spacer(Modifier.width(7.dp))
                                        }
                                        Text(
                                            when {
                                                site.running -> "http://127.0.0.1:${site.port}"
                                                detailBusy -> "正在启动，请稍候…"
                                                else -> "停止状态"
                                            },
                                            fontSize = 12.sp,
                                            color = when {
                                                site.running -> Mint
                                                detailBusy -> Sky
                                                else -> TextLow
                                            },
                                        )
                                    }
                                }
                                Switch(
                                    checked = site.running || detailBusy,
                                    onCheckedChange = { on ->
                                        if (on) vm.startSite(ctx, site.name) else vm.stopSite(ctx, site.name)
                                    },
                                    enabled = !detailBusy,
                                    colors =
                                        SwitchDefaults.colors(
                                            checkedTrackColor = if (detailBusy) Color(0xFF1D6A5E) else MintDeep,
                                            checkedThumbColor = if (detailBusy) Color(0xFF9CCFC5) else Color(0xFF04211B),
                                            disabledCheckedTrackColor = Color(0xFF1D6A5E),
                                            disabledCheckedThumbColor = Color(0xFF9CCFC5),
                                        ),
                                )
                            }
                            if (site.running) {
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GhostButton("预览", Modifier.weight(1f).height(42.dp)) {
                                        ctx.startActivity(
                                            android.content.Intent(ctx, SitePreviewActivity::class.java)
                                                .putExtra("port", SiteServerService.portOf(site.name) ?: site.port),
                                        )
                                    }
                                    GhostButton("浏览器", Modifier.weight(1f).height(42.dp)) {
                                        try {
                                            ctx.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    Uri.parse("http://127.0.0.1:${SiteServerService.portOf(site.name) ?: site.port}/"),
                                                ),
                                            )
                                        } catch (e: Exception) {
                                            vm.toast("未找到浏览器")
                                        }
                                    }
                                    GhostButton("复制", Modifier.weight(0.7f).height(42.dp)) {
                                        clip.setText(AnnotatedString("http://127.0.0.1:${SiteServerService.portOf(site.name) ?: site.port}/"))
                                        vm.toast("已复制")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    DetailCard("设置") {
                        // autostart
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("随应用启动", fontSize = 14.sp, color = TextHi)
                                Text("打开 App 时自动启动此站点", fontSize = 11.sp, color = TextLow)
                            }
                            Switch(
                                checked = vm.detailAutostart,
                                onCheckedChange = { vm.setAutostart(ctx, site.name, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = MintDeep, checkedThumbColor = Color(0xFF04211B)),
                            )
                        }
                        HorizontalDivider(color = Stroke, modifier = Modifier.padding(vertical = 8.dp))
                        // port
                        var portText by remember(site.port) { mutableStateOf(site.port.toString()) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("端口", fontSize = 14.sp, color = TextHi)
                                Text("1024-65535，需先停止站点", fontSize = 11.sp, color = TextLow)
                            }
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                                modifier = Modifier.width(100.dp).height(50.dp),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                val p = portText.toIntOrNull()
                                if (p != null && p in 1024..65535) vm.changePort(ctx, site.name, p)
                                else vm.toast("端口需在 1024-65535")
                            }) { Text("应用", color = Mint) }
                        }
                        HorizontalDivider(color = Stroke, modifier = Modifier.padding(vertical = 8.dp))
                        // package management
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("站点包管理", fontSize = 14.sp, color = TextHi)
                                Text("用新的 ZIP 覆盖更新（保留端口与设置）", fontSize = 11.sp, color = TextLow)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton("更新包", Modifier.weight(1f).height(42.dp)) {
                                vm.showUpdateSheet = true
                            }
                            Button(
                                onClick = { vm.confirmDelete = site.name },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1B22), contentColor = Rose),
                            ) { Text("删除站点", maxLines = 1) }
                        }
                    }
                }
            }
        }
    }

    // update-source chooser sheet (same pattern as home import)
    if (vm.showUpdateSheet) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x8804070D))
                .clickable { vm.showUpdateSheet = false },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Card1),
                shape = RoundedCornerShape(24.dp, 24.dp, 0.dp, 0.dp),
                modifier = Modifier.fillMaxWidth().clickable(enabled = false) {},
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("更新站点包", color = TextHi, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("「${vm.detailSite}」的内容将被覆盖，端口与设置保留", color = TextLow, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    ImportOption(
                        title = "从本地 ZIP 更新",
                        desc = "选择手机中的 .zip 站点包",
                        onClick = {
                            vm.showUpdateSheet = false
                            updatePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                    )
                    HorizontalDivider(color = Stroke)
                    ImportOption(
                        title = "从 URL 拉取更新",
                        desc = "输入链接在线下载新版本包",
                        onClick = {
                            vm.showUpdateSheet = false
                            vm.showUpdateUrlDialog = true
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // update-from-url dialog
    if (vm.showUpdateUrlDialog) {
        var urlText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.showUpdateUrlDialog = false },
            containerColor = Card1,
            titleContentColor = TextHi,
            textContentColor = TextMid,
            title = { Text("从 URL 拉取新版本包") },
            text = {
                Column {
                    Text("更新目标：${vm.detailSite}", fontSize = 12.sp, color = Mint)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("ZIP 链接") },
                        placeholder = { Text("https://example.com/site.zip", color = TextLow) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val u = urlText.trim()
                        if (u.startsWith("http")) {
                            vm.showUpdateUrlDialog = false
                            vm.updateSiteFromUrl(ctx, vm.detailSite, u)
                        } else {
                            vm.toast("请输入 http(s) 链接")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MintDeep, contentColor = Color(0xFF04211B)),
                ) { Text("拉取") }
            },
            dismissButton = { TextButton(onClick = { vm.showUpdateUrlDialog = false }) { Text("取消", color = TextMid) } },
        )
    }
}

@Composable
fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card0), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontSize = 13.sp, color = TextLow)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

// ================= SETTINGS =================

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { vm.refresh(ctx) }

    val safTree =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val f = SitesRepository.takeTreeUri(ctx, uri)
            if (f == null) {
                vm.toast("无法解析所选目录")
            } else {
                vm.changeRoot(ctx, File(f, "PocketSite-sites"))
            }
        }

    val allFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Environment.isExternalStorageManager()) {
                safTree.launch(null)
            } else {
                vm.toast("未授予「所有文件访问」，自定义目录不可用")
            }
        }

    fun requestDirChange() {
        if (Environment.isExternalStorageManager()) {
            safTree.launch(null)
        } else if (Build.VERSION.SDK_INT >= 30) {
            try {
                allFiles.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    ),
                )
            } catch (e: Exception) {
                allFiles.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            vm.toast("需要 Android 11+ 才能迁移到自定义目录")
        }
    }

    val inPrivate = vm.rootPath.contains(ctx.filesDir.path)
    val runningCount = SiteServerService.runningCount()

    Scaffold(containerColor = Bg0) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("←", fontSize = 22.sp, color = Sky, modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.screen = Screen.HOME }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("设置", style = MaterialTheme.typography.titleLarge, color = TextHi)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Card0), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("站点根目录", fontSize = 15.sp, color = TextHi, modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (inPrivate) Color(0x1A7DD3FC) else Color(0x1A5EEAD4))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(if (inPrivate) "应用私有" else "自定义", fontSize = 10.sp, color = if (inPrivate) Sky else Mint)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            vm.rootPath,
                            fontSize = 12.sp,
                            color = TextMid,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GradientButton("更改并迁移目录", Modifier.weight(1f).height(44.dp), enabled = runningCount == 0) {
                                requestDirChange()
                            }
                            if (!inPrivate) {
                                GhostButton("迁回应用目录", Modifier.weight(1f).height(44.dp), enabled = runningCount == 0) {
                                    vm.changeRoot(ctx, SitesRepository.defaultRoot(ctx))
                                }
                            }
                        }
                        if (runningCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("有 $runningCount 个站点正在运行，迁移前请先停止", fontSize = 11.sp, color = Amber)
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text("迁移流程：复制 → 字节校验 → 通过后才删除旧目录，失败自动保留原数据", fontSize = 11.sp, color = TextLow)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Card0), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("关于", fontSize = 13.sp, color = TextLow)
                        Spacer(Modifier.height(8.dp))
                        Text("PocketSite v1.1 · 多站点独立端口", fontSize = 13.sp, color = TextHi)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "每个站点分配独立端口，可同时运行多个站点；" +
                                "离线演示请用站点卡片的 WebView 预览（部分浏览器断网时会拦截 127.0.0.1）。",
                            fontSize = 11.sp,
                            color = TextMid,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

// ================= shared components =================

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF04211B)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (enabled) Brush.horizontalGradient(listOf(MintDeep, Mint)) else Brush.horizontalGradient(listOf(Stroke, Stroke)),
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 14.sp, maxLines = 1) }
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Card1,
                contentColor = TextHi,
                disabledContainerColor = Card0,
                disabledContentColor = TextLow,
            ),
    ) { Text(text, fontSize = 14.sp, maxLines = 1) }
}
