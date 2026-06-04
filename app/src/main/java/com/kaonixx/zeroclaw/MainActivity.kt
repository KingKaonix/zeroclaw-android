package com.kaonixx.zeroclaw

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the background service
        startService(Intent(this, ZeroClawService::class.java))

        // WebView = full-screen dashboard to ZeroClaw's gateway
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "ZeroClaw-Android/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject watermark for free tier
                if (!LicenseValidator.isPro(this@MainActivity)) {
                    view?.evaluateJavascript("""
                        (function() {
                            var wm = document.createElement('div');
                            wm.id = 'zc-watermark';
                            wm.style.cssText = 'position:fixed;bottom:8px;right:12px;z-index:9999;color:rgba(255,255,255,0.15);font-size:11px;font-family:monospace;pointer-events:none;';
                            wm.textContent = 'ZeroClaw · UNLICENSED';
                            document.body.appendChild(wm);
                        })();
                    """.trimIndent(), null)
                }
            }
        }

        webView.webChromeClient = WebChromeClient()
        setContentView(webView)

        // Retry connecting to gateway until it's up
        retryLoadGateway()
    }

    private fun retryLoadGateway(retries: Int = 20) {
        if (retries <= 0) {
            webView.loadData("<html><body style='color:#333;padding:40px;font-family:sans-serif'><h2>ZeroClaw not running</h2><p>Restart the app or check service status.</p></body></html>", "text/html", "utf-8")
            return
        }
        try {
            webView.loadUrl("http://127.0.0.1:18789")
            // Check if page loaded successfully after a delay
            webView.postDelayed({
                webView.evaluateJavascript("document.title;") { title ->
                    if (title.isNullOrEmpty() || title == "null") {
                        retryLoadGateway(retries - 1)
                    }
                }
            }, 1500)
        } catch (e: Exception) {
            retryLoadGateway(retries - 1)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
