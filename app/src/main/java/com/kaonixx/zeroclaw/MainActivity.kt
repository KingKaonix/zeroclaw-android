package com.kaonixx.zeroclaw

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startService(Intent(this, ZeroClawService::class.java))

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
        retryLoadGateway()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, if (LicenseValidator.isPro(this)) "Pro ✓" else "Get Pro")
        menu?.add(0, 2, 1, if (LicenseValidator.isPro(this)) "Deactivate" else "Enter License Key")
        menu?.add(0, 3, 2, "About")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item?.itemId) {
            1 -> {
                if (!LicenseValidator.isPro(this)) {
                    webView.loadUrl("https://gumroad.com/l/zeroclaw-android")
                } else {
                    Toast.makeText(this, "Pro ✓", Toast.LENGTH_SHORT).show()
                }
            }
            2 -> {
                if (LicenseValidator.isPro(this)) {
                    LicenseValidator.deactivate(this)
                    Toast.makeText(this, "License deactivated", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    showLicenseInput()
                }
            }
            3 -> {
                webView.loadUrl("about:blank")
                webView.loadDataWithBaseURL(null, """
                    <html><body style="background:#111;color:#eee;padding:40px;font-family:sans-serif">
                    <h2 style="color:#00e5ff">ZeroClaw Android</h2>
                    <p>Version 1.0.0-alpha</p>
                    <p>License: ${if (LicenseValidator.isPro(this)) "Pro ✓" else "Free"}</p>
                    <hr style="border-color:#333">
                    <p><a href="https://github.com/KingKaonix/zeroclaw-android" style="color:#00e5ff">GitHub</a></p>
                    <p>Built with ❤️ by KNET</p>
                    </body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
        }
        return true
    }

    private fun showLicenseInput() {
        val input = android.widget.EditText(this).apply {
            hint = "Paste license key here"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val sigInput = android.widget.EditText(this).apply {
            hint = "Paste signature here"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(input)
            addView(sigInput)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Activate Pro")
            .setView(layout)
            .setPositiveButton("Activate") { _, _ ->
                val key = input.text.toString().trim()
                val sig = sigInput.text.toString().trim()
                if (key.isNotEmpty() && sig.isNotEmpty()) {
                    if (LicenseValidator.activate(this, key, sig)) {
                        Toast.makeText(this, "Pro activated ✓", Toast.LENGTH_SHORT).show()
                        recreate()
                    } else {
                        Toast.makeText(this, "Invalid license key", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun retryLoadGateway(retries: Int = 20) {
        if (retries <= 0) {
            webView.loadData("<html><body style='color:#eee;background:#111;padding:40px;font-family:sans-serif'><h2>ZeroClaw not running</h2><p>Restart the app or check service status.</p></body></html>", "text/html", "utf-8")
            return
        }
        try {
            webView.loadUrl("http://127.0.0.1:18789")
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
