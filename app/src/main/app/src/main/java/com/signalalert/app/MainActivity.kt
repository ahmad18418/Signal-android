package com.signalalert.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
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
import java.io.File
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

    private val appVersionLabel: String
        get() = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        webView = findViewById(R.id.webView)
        lockPanel = findViewById(R.id.lockPanel)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        findViewById<TextView>(R.id.versionText)?.text = appVersionLabel

        findViewById<MaterialButton>(R.id.btnUnlock).setOnClickListener {
            if (prefs.biometricEnabled) startBiometric() else unlockAndLoad()
        }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupWebView()

        if (prefs.biometricEnabled && canUseBiometric()) {
            showLock("برای ورود اثرانگشت یا چهره را تأیید کنید\n$appVersionLabel")
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
            statusText.text = getString(R.string.biometric_error) + " — ورود مستقیم\n$appVersionLabel"
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
                        showLock("ورود لغو شد — دوباره تلاش کنید\n$appVersionLabel")
                    } else {
                        showLock("$errString\n$appVersionLabel")
                    }
                }

                override fun onAuthenticationFailed() {
                    showLock("تأیید نشد — دوباره تلاش کنید\n$appVersionLabel")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setDescription(getString(R.string.biometric_description) + " · $appVersionLabel")
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
            io.execute {
                val ok = loginWithDeviceToken(token)
                mainHandler.post {
                    if (ok) {
                        webView.loadUrl(prefs.serverUrl.trimEnd('/') + "/app")
                    } else {
                        prefs.deviceToken = ""
                        webView.loadUrl(prefs.serverUrl.trimEnd('/') + "/login")
                    }
                }
            }
        } else {
            webView.loadUrl(prefs.serverUrl.trimEnd('/') + "/login")
        }
    }

    private fun loginWithDeviceToken(token: String): Boolean {
        return try {
            val url = URL(prefs.serverUrl.trimEnd('/') + "/api/auth/device")
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
            userAgentString =
                userAgentString + " SignalAlertAndroid/${BuildConfig.VERSION_NAME}"
        }
        webView.addJavascriptInterface(JsBridge(), "SignalAndroid")
        webView.webChromeClient = WebChromeClient()

        // بک‌آپ / اکسل: DownloadListener + رهگیری URL
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startFileDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val u = request?.url?.toString() ?: return false
                if (isDownloadUrl(u)) {
                    startFileDownload(
                        u,
                        webView.settings.userAgentString,
                        null,
                        null
                    )
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                if (url != null && (url.contains("/app") || url.contains("/settings") || url.endsWith("/"))) {
                    injectRegisterDeviceToken()
                    injectDownloadHook()
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

    private fun isDownloadUrl(u: String): Boolean {
        val low = u.lowercase()
        return low.contains("/settings/backup-download/") ||
            low.contains("/dl/backup/") ||
            low.contains("/api/table_xlsx") ||
            low.contains("/api/backup") ||
            low.endsWith(".db") ||
            low.endsWith(".xlsx") ||
            low.endsWith(".csv") ||
            low.endsWith(".zip")
    }

    /**
     * دانلود مطمئن با کوکی نشست (نه فقط DownloadManager).
     * روی بعضی گوشی‌ها DownloadManager کوکی لاگین را درست نمی‌فرستد.
     */
    private fun startFileDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "آدرس دانلود خالی است", Toast.LENGTH_SHORT).show()
            return
        }
        val absoluteUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> prefs.serverUrl.trimEnd('/') + url
            else -> prefs.serverUrl.trimEnd('/') + "/" + url
        }
        Toast.makeText(this, "در حال دانلود…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val conn = (URL(absoluteUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 60000
                    readTimeout = 180000
                    requestMethod = "GET"
                    setRequestProperty(
                        "User-Agent",
                        userAgent ?: webView.settings.userAgentString
                    )
                    setRequestProperty("Accept", "*/*")
                }
                val cm = CookieManager.getInstance()
                val cookie = listOf(
                    cm.getCookie(absoluteUrl),
                    cm.getCookie(prefs.serverUrl),
                    cm.getCookie(prefs.serverUrl.trimEnd('/') + "/"),
                    cm.getCookie("http://130.185.75.146"),
                    cm.getCookie("http://130.185.75.146/")
                ).firstOrNull { !it.isNullOrBlank() }
                if (!cookie.isNullOrBlank()) {
                    conn.setRequestProperty("Cookie", cookie)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    mainHandler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "خطای دانلود HTTP $code — دوباره وارد اپ شوید",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@execute
                }
                val disp = conn.getHeaderField("Content-Disposition") ?: contentDisposition
                var mt = conn.contentType ?: mimeType ?: "application/octet-stream"
                if (mt.contains(";")) mt = mt.substringBefore(";").trim()
                var fileName = URLUtil.guessFileName(absoluteUrl, disp, mt)
                if (!fileName.contains('.')) {
                    fileName += when {
                        absoluteUrl.contains(".db") || mt.contains("sqlite") -> ".db"
                        absoluteUrl.contains("xlsx") || mt.contains("spreadsheet") -> ".xlsx"
                        absoluteUrl.contains("csv") -> ".csv"
                        absoluteUrl.contains("zip") -> ".zip"
                        else -> ".bin"
                    }
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                val head = bytes.take(64).toByteArray().toString(Charsets.ISO_8859_1)
                if (mt.contains("text/html") || head.contains("<html", ignoreCase = true) ||
                    head.contains("<!DOCTYPE", ignoreCase = true)
                ) {
                    mainHandler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "به‌جای فایل، صفحه لاگین برگشت — دوباره وارد شوید",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@execute
                }
                saveToDownloads(fileName, mt, bytes)
                mainHandler.post {
                    Toast.makeText(
                        this@MainActivity,
                        "ذخیره شد: Downloads/$fileName (${bytes.size / 1024} KB)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(
                        this@MainActivity,
                        "خطا در دانلود: ${e.message ?: e.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToDownloads(fileName: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("نمی‌توان فایل در Downloads ساخت")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("خروجی فایل باز نشد")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
        }
    }

    private fun injectRegisterDeviceToken() {
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

    /** کلیک روی لینک دانلود بک‌آپ را به native بفرست */
    private fun injectDownloadHook() {
        val js = """
            (function(){
              try {
                window.saDownload = function(url, fileName) {
                  var name = fileName || '';
                  if (!name && url) {
                    try { name = decodeURIComponent(String(url).split('/').pop() || ''); }
                    catch(e) { name = String(url).split('/').pop() || 'backup.db'; }
                  }
                  if (!name) { alert('نام فایل مشخص نیست'); return false; }
                  var path = '/settings/backup-download/' + encodeURIComponent(name);
                  var abs = location.origin + path;
                  if (window.SignalAndroid && window.SignalAndroid.downloadUrl) {
                    window.SignalAndroid.downloadUrl(abs);
                    return false;
                  }
                  location.href = path;
                  return false;
                };
              } catch(e) {}
              try {
                document.querySelectorAll('a[href*="backup-download"], a[href*="/dl/backup/"]').forEach(function(a){
                  if (a.__saHooked) return;
                  a.__saHooked = true;
                  a.addEventListener('click', function(ev){
                    var h = a.getAttribute('href') || '';
                    if (h.indexOf('backup-download') >= 0 || h.indexOf('/dl/backup/') >= 0) {
                      ev.preventDefault();
                      if (window.SignalAndroid && window.SignalAndroid.downloadUrl) {
                        var abs = h.indexOf('http') === 0 ? h : (location.origin + (h.charAt(0)==='/'?h:'/'+h));
                        window.SignalAndroid.downloadUrl(abs);
                      } else {
                        location.href = h;
                      }
                    }
                  }, true);
                });
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
            }
        }

        @JavascriptInterface
        fun downloadUrl(url: String?) {
            mainHandler.post {
                startFileDownload(
                    url,
                    webView.settings.userAgentString,
                    null,
                    null
                )
            }
        }

        @JavascriptInterface
        fun getAppVersion(): String = appVersionLabel
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
