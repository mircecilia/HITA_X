package com.stupidtree.hitax.ui.eas.login

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.http.SslError
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import com.stupidtree.hitax.R
import com.stupidtree.hitax.data.repository.EASRepository
import com.stupidtree.hitax.databinding.ActivityEasWebLoginBinding

class EASWebLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEasWebLoginBinding
    private var loginSubmitted = false
    private var credentialSubmitted = false
    private var username: String? = null
    private var password: String? = null
    private var blankReloaded = false
    private var autoSubmit = false
    private var startUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEasWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        username = intent.getStringExtra(EXTRA_USERNAME)
        password = intent.getStringExtra(EXTRA_PASSWORD)
        autoSubmit = intent.getBooleanExtra(EXTRA_AUTO_SUBMIT, false)
        startUrl = intent.getStringExtra(EXTRA_START_URL)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.eas_verify_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(binding.webview, true)

        binding.webview.settings.javaScriptEnabled = true
        binding.webview.settings.domStorageEnabled = true
        binding.webview.settings.useWideViewPort = true
        binding.webview.settings.loadWithOverviewMode = true
        binding.webview.settings.mixedContentMode =
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        binding.webview.settings.javaScriptCanOpenWindowsAutomatically = true
        binding.webview.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"

        binding.webview.webChromeClient = WebChromeClient()

        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                binding.progress.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.progress.visibility = android.view.View.GONE
                if (url == "about:blank") {
                    val target = startUrl ?: AUTH_LOGIN_URL
                    if (!blankReloaded) {
                        blankReloaded = true
                        view.loadUrl(target)
                    } else {
                        view.loadUrl(target)
                    }
                    Toast.makeText(
                        this@EASWebLoginActivity,
                        "页面空白，已尝试重新加载",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                if (!credentialSubmitted && !username.isNullOrBlank() && !password.isNullOrBlank()) {
                    tryAutoFillLogin(view, autoSubmit)
                }
                if (loginSubmitted) return
                if (isCasLoginCallback(url)) {
                    val cookieStr =
                        cm.getCookie("http://jw.hitsz.edu.cn")
                            ?: cm.getCookie("https://jw.hitsz.edu.cn")
                    if (!cookieStr.isNullOrBlank()) {
                        val cookies = parseCookies(cookieStr)
                        if (cookies.isNotEmpty()) {
                            loginSubmitted = true
                            doLoginWithCookies(cookies)
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@EASWebLoginActivity,
                        "网页加载失败: ${error.errorCode} ${error.description}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http")) {
                    view.loadUrl(url)
                    return true
                }
                return false
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@EASWebLoginActivity,
                        "网页返回错误: ${errorResponse.statusCode}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                Toast.makeText(
                    this@EASWebLoginActivity,
                    "SSL错误: ${error.primaryError}",
                    Toast.LENGTH_LONG
                ).show()
                handler.cancel()
            }
        }

        binding.webview.loadUrl(startUrl ?: AUTH_LOGIN_URL)
        binding.webview.postDelayed({
            val current = binding.webview.url ?: ""
            if (current.isEmpty() || current == "about:blank") {
                binding.webview.loadUrl(startUrl ?: AUTH_LOGIN_URL)
                Toast.makeText(this, "页面未加载，已强制刷新", Toast.LENGTH_SHORT).show()
            }
        }, 2500)
    }

    private fun isCasLoginCallback(url: String): Boolean {
        return url.contains("jw.hitsz.edu.cn") && url.contains("casLogin")
    }

    private fun parseCookies(cookieStr: String): HashMap<String, String> {
        val map = HashMap<String, String>()
        cookieStr.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                map[kv[0]] = kv[1]
            }
        }
        return map
    }

    private fun doLoginWithCookies(cookies: HashMap<String, String>) {
        val repo = EASRepository.getInstance(application)
        repo.loginWithCookies(cookies).observe(this) {
            if (it.state == com.stupidtree.component.data.DataState.STATE.SUCCESS && it.data == true) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                val msg = it.message ?: getString(R.string.login_failed)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tryAutoFillLogin(view: WebView, submit: Boolean) {
        val u = JSONObject.quote(username)
        val p = JSONObject.quote(password)
        val js = if (submit) """
            (function(){
              var u = document.querySelector('input[name=username],#username');
              var p = document.querySelector('input[type=password],input[name=password],#password');
              if(!u || !p) return 'noform';
              u.value = $u;
              p.value = $p;
              var form = p.form || u.form;
              if(form){ form.submit(); return 'submitted'; }
              var btn = document.querySelector('button[type=submit],input[type=submit]');
              if(btn){ btn.click(); return 'clicked'; }
              return 'filled';
            })()
        """.trimIndent() else """
            (function(){
              var u = document.querySelector('input[name=username],#username');
              var p = document.querySelector('input[type=password],input[name=password],#password');
              if(!u || !p) return 'noform';
              u.value = $u;
              p.value = $p;
              return 'filled';
            })()
        """.trimIndent()
        view.evaluateJavascript(js) { result ->
            if (result != null &&
                (result.contains("submitted") || result.contains("clicked") || result.contains("filled"))
            ) {
                credentialSubmitted = true
            }
        }
    }

    companion object {
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_AUTO_SUBMIT = "extra_auto_submit"
        const val EXTRA_START_URL = "extra_start_url"
        private const val AUTH_LOGIN_URL =
            "https://ids.hit.edu.cn/authserver/login?service=http%3A%2F%2Fjw.hitsz.edu.cn%2FcasLogin"
    }
}
