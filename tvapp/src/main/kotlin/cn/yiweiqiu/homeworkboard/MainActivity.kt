package cn.yiweiqiu.homeworkboard

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import cn.yiweiqiu.homework.core.DavSession
import cn.yiweiqiu.homeworkboard.ui.HomeworkBoardTheme
import cn.yiweiqiu.homeworkboard.ui.TvHomeRoute
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 电视端前台常亮；不改系统休眠设置
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        installImageLoader()
        // 6 小时兜底同步：App 没被打开也能把新作业拉回来
        runCatching { TvSyncWorker.ensure(applicationContext) }

        setContent {
            HomeworkBoardTheme {
                TvHomeRoute(viewModel)
            }
        }
    }

    /**
     * 图片和作业数据走同一套 WebDAV 凭据：拦截器在请求时补 Authorization，
     * 这样 Coil 只拿到一个普通的 URL，不用关心鉴权。
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

    /** 菜单键：手动同步（部分遥控器把菜单键送到 Activity 层） */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            viewModel.refresh(manual = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
