package cn.yiweiqiu.homeworkboard.phone

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yiweiqiu.homework.core.DavSession
import cn.yiweiqiu.homeworkboard.phone.ui.EditorScreen
import cn.yiweiqiu.homeworkboard.phone.ui.HomeScreen
import cn.yiweiqiu.homeworkboard.phone.ui.PhoneTheme
import cn.yiweiqiu.homeworkboard.phone.ui.SettingsScreen
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PhoneActivity : ComponentActivity() {

    private val viewModel: PhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ① 系统栏全透明、内容画到下面（沉浸式）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        // ② 关掉系统给系统栏铺的「对比度蒙层」。
        // 不加这两行，手势导航时屏幕最底下永远垫着一条比背景白一档的横杠——
        // 这就是「没适配小白条」的观感来源，App 自己怎么调颜色都盖不掉。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        installImageLoader()
        setContent {
            PhoneTheme {
                PhoneApp(viewModel)
            }
        }
    }

    /**
     * 图片和作业数据走同一套 WebDAV 凭据。
     *
     * 手机端原来没装这个加载器，所以编辑老条目时「已有的图」根本渲染不出来，
     * 只能退化成一个「已有 N 张」的文字方块——用户看不到自己上次发的是哪几张。
     * 电视端一直有这段，这里对齐过来。
     */
    private fun installImageLoader() {
        val loader = ImageLoader.Builder(applicationContext)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val header = DavSession.authHeader
                        val request = if (header == null) {
                            chain.request()
                        } else {
                            chain.request().newBuilder().header("Authorization", header).build()
                        }
                        chain.proceed(request)
                    }
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            }
            .crossfade(true)
            .build()
        Coil.setImageLoader(loader)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onEnterForeground()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onLeaveForeground()
    }
}

private enum class Screen { Home, Editor, Settings }

@Composable
private fun PhoneApp(viewModel: PhoneViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 一次性提示：显示完立刻清掉，避免旋转后重复弹
    LaunchedEffect(state.toast) {
        val text = state.toast
        if (text != null) {
            // 草稿这条要给出路：只提示不给「丢弃」，用户就只能靠清 App 数据去掉它，
            // 而清数据会把坚果云账号一起清掉。用 Long 让提示停久一点，按钮才点得着。
            val result = snackbar.showSnackbar(
                message = text,
                actionLabel = if (state.draftRestored) "丢弃" else null,
                duration = if (state.draftRestored) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                },
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.discardDraft()
            viewModel.clearToast()
        }
    }

    fun backToHome() {
        viewModel.saveDraftIfNeeded()
        screen = Screen.Home
    }

    BackHandler(enabled = screen != Screen.Home) {
        when (screen) {
            Screen.Editor -> backToHome()
            Screen.Settings -> screen = Screen.Home
            else -> Unit
        }
    }

    Scaffold(
        // targetSdk 35 强制 edge-to-edge，窗口画到键盘/手势条底下。
        // imePadding 管键盘（否则「已发到电视 / 报错」提示被键盘整个盖住），
        // navigationBarsPadding 管手势条（不加的话提示条贴着小白条，还会压住底部的提醒输入框）
        snackbarHost = {
            SnackbarHost(
                snackbar,
                Modifier
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
            )
        },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    state = state,
                    onAdd = {
                        viewModel.startNew()
                        screen = Screen.Editor
                    },
                    onEdit = { item ->
                        viewModel.startEdit(item)
                        screen = Screen.Editor
                    },
                    onDelete = { viewModel.delete(it) },
                    onOpenSettings = { screen = Screen.Settings },
                    onRetryPending = { viewModel.retryPending() },
                    onRefresh = { viewModel.refresh() },
                    onSaveNotes = { viewModel.saveNotes(it) },
                    onSetValidUntil = { viewModel.setValidUntil(it) },
                )

                Screen.Editor -> EditorScreen(
                    state = editor,
                    sending = state.sending,
                    onBack = { backToHome() },
                    onSubject = viewModel::setSubject,
                    onTitle = viewModel::setTitle,
                    onDetail = viewModel::setDetail,
                    onAddImages = viewModel::addImages,
                    onRemoveKept = viewModel::removeKeptImage,
                    onRemoveNew = viewModel::removeNewImage,
                    onSubmit = { viewModel.send { screen = Screen.Home } },
                )

                Screen.Settings -> SettingsScreen(
                    state = state,
                    initial = viewModel.settingsSnapshot(),
                    test = test,
                    onBack = { screen = Screen.Home },
                    onSaveAccount = { url, user, pass ->
                        viewModel.saveAccount(url, user, pass)
                    },
                    onTest = { url, user, pass ->
                        viewModel.testConnection(url, user, pass)
                    },
                    onSaveChild = { viewModel.saveChild(it) },
                    onSyncNow = { viewModel.syncNow() },
                    configJson = { viewModel.configJson() },
                )
            }
        }
    }
}
