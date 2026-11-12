package app.pkuautobus

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class PortalActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var label: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var verifying = false
    private var sawLogin = false
    private var loginMode = false
    private val model get() = (application as BusApplication).model

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        loginMode = intent.getBooleanExtra("login", false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this)
        toolbar.addView(Button(this).apply { text = "返回应用"; setOnClickListener { setResult(RESULT_OK); finish() } })
        label = TextView(this).apply { text = "北大官方预约系统"; gravity = android.view.Gravity.CENTER_VERTICAL }
        toolbar.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(toolbar)
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (trusted(request.url)) return false
                label.text = "已阻止站外跳转"
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                val uri = Uri.parse(url)
                if (!trusted(uri)) { view.stopLoading(); label.text = "已阻止非北大页面"; return }
                label.text = uri.host
                if (uri.host == "iaaa.pku.edu.cn" && !sawLogin) {
                    sawLogin = true; loginMode = true
                    model.clearForLogin()
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                val uri = Uri.parse(url)
                if (loginMode && uri.host == "wproc.pku.edu.cn" && uri.path?.startsWith("/v2/") == true && !verifying) {
                    verifying = true
                    worker.execute {
                        val success = runCatching { model.client.verifyRoutes() }.isSuccess
                        runOnUiThread {
                            verifying = false
                            if (success && !isFinishing && !isDestroyed) { setResult(RESULT_OK); finish() }
                        }
                    }
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel(); label.text = "证书验证失败，已停止连接"
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) label.text = "页面加载失败，请返回应用后重试"
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { setResult(RESULT_OK); finish() }
            }
        })
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(if (loginMode) PORTAL_HALL else PORTAL_TICKETS)
    }
    override fun onSaveInstanceState(outState: Bundle) { web.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy(); worker.shutdownNow(); super.onDestroy() }
    private fun trusted(uri: Uri) = uri.scheme == "https" && uri.host in setOf("wproc.pku.edu.cn", "iaaa.pku.edu.cn") && uri.port in setOf(-1, 443)
}
