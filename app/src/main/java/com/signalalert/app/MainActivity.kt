package com.signalalert.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
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
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var lockPanel: View
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private var unlocked = false

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
        // اگر از تنظیمات برگشت و هنوز قفل است
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
        val executor: Executor = ContextCompat.getMainExecutor(this)
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
        val url = prefs.serverUrl.let {
            if (it.endsWith("/app") || it.contains("/login")) it else "$it/app"
        }
        progress.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " SignalAlertAndroid/1.0"
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // همه لینک‌های همان سرور داخل WebView
                val u = request?.url?.toString().orEmpty()
                return if (u.startsWith(prefs.serverUrl) || u.contains(prefs.serverUrl.removePrefix("http://").removePrefix("https://"))) {
                    false
                } else {
                    // لینک خارجی در همان webview هم باز شود
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (unlocked && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
