package com.signalalert.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var lockPanel: View
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private var unlocked = false
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        webView = findViewById(R.id.webView)
        lockPanel = findViewById(R.id.lockPanel)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        findViewById<MaterialButton>(R.id.btnUnlock).setOnClickListener {
            if (prefs.biometricEnabled) startBiometric() else unlockAndLoad()
        }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupWebView()

        if (prefs.biometricEnabled && canUseBiometric()) {
            showLock("برای ورود اثرانگشت یا چهره را تأیید کنید")
            startBiometric()
        } else {
            unlockAndLoad()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked && !prefs.biometricEnabled) {
            unlockAndLoad()
        }
    }

    private fun canUseBiometric(): Boolean {
        val m = BiometricManager.from(this)
        return m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun startBiometric() {
        if (!canUseBiometric()) {
            statusText.text = getString(R.string.biometric_error) + " — ورود مستقیم"
            unlockAndLoad()
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockAndLoad()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        showLock("ورود لغو شد — دوباره تلاش کنید")
                    } else {
                        showLock(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    showLock("تأیید نشد — دوباره تلاش کنید")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setDescription(getString(R.string.biometric_description))
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()
        prompt.authenticate(info)
    }

    private fun showLock(msg: String) {
        unlocked = false
        lockPanel.visibility = View.VISIBLE
        webView.visibility = View.GONE
        progress.visibility = View.GONE
        statusText.text = msg
    }

    private fun unlockAndLoad() {
        unlocked = true
        lockPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        val token = prefs.deviceToken
        if (token.isNotBlank()) {
            // ورود نشست وب با توکن دستگاه (بعد از اثرانگشت)
            io.execute {
                val ok = loginWithDeviceToken(token)
                mainHandler.post {
                    if (ok) {
                        webView.loadUrl(prefs.serverUrl + "/app")
                    } else {
                        // توکن باطل شده — یک‌بار لاگین رمز
                        prefs.deviceToken = ""
                        webView.loadUrl(prefs.serverUrl + "/login")
                    }
                }
            }
        } else {
            // اولین بار: لاگین با رمز، بعد توکن دستگاه ذخیره می‌شود
            webView.loadUrl(prefs.serverUrl + "/login")
        }
    }

    private fun loginWithDeviceToken(token: String): Boolean {
        return try {
            val url = URL(prefs.serverUrl + "/api/auth/device")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = false
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                w.write(JSONObject().put("token", token).put("bio", true).toString())
            }
            val code = conn.responseCode
            // کوکی نشست را به WebView منتقل کن
            val cookieHeaders = conn.headerFields["Set-Cookie"] ?: conn.headerFields["set-cookie"]
            if (cookieHeaders != null) {
                val cm = CookieManager.getInstance()
                for (c in cookieHeaders) {
                    cm.setCookie(prefs.serverUrl, c)
                }
                cm.flush()
            }
            // بعضی سرورها چند Set-Cookie می‌فرستند
            var i = 0
            while (true) {
                val key = conn.getHeaderFieldKey(i) ?: break
                val value = conn.getHeaderField(i)
                if (key.equals("Set-Cookie", ignoreCase = true) && value != null) {
                    CookieManager.getInstance().setCookie(prefs.serverUrl, value)
                }
                i++
            }
            CookieManager.getInstance().flush()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " SignalAlertAndroid/1.1"
        }
        webView.addJavascriptInterface(JsBridge(), "SignalAndroid")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                // بعد از ورود موفق به /app توکن دستگاه را ثبت کن
                if (url != null && (url.contains("/app") || url.endsWith("/"))) {
                    injectRegisterDeviceToken()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                progress.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "خطا در اتصال به سرور: ${description ?: ""}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun injectRegisterDeviceToken() {
        // اگر قبلاً توکن داریم لازم نیست؛ ولی تازه‌سازی هم اشکالی ندارد
        val js = """
            (function(){
              try {
                fetch('/api/auth/device/register', {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: {'Content-Type': 'application/json'}
                }).then(function(r){ return r.json(); })
                  .then(function(j){
                    if (j && j.ok && j.token && window.SignalAndroid) {
                      window.SignalAndroid.saveDeviceToken(j.token);
                    }
                  }).catch(function(){});
              } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class JsBridge {
        @JavascriptInterface
        fun saveDeviceToken(token: String?) {
            if (!token.isNullOrBlank()) {
                prefs.deviceToken = token
                mainHandler.post {
                    Toast.makeText(
                        this@MainActivity,
                        "ورود دستگاه ذخیره شد — دفعه بعد با اثرانگشت مستقیم وارد می‌شوید",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (unlocked && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
