package com.kaonixx.zeroclaw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val NOTIF_PERM_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
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
            webView.webChromeClient = WebChromeClient()
            setContentView(webView)
        } catch (e: Exception) {
            val tv = android.widget.TextView(this).apply {
                text = "Error: ${e.message}\n\nTry reinstalling or check device logs."
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#111"))
                textSize = 14f
                setPadding(40, 40, 40, 40)
            }
            setContentView(tv)
            return
        }

        requestNotificationPermission()
        startService(Intent(this, ZeroClawService::class.java))
        // Delay initial load slightly to let service spin up, then retry with back-off
        webView.postDelayed({ retryLoadGateway() }, 2000)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERM_CODE
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, if (LicenseValidator.isPro(this)) "Pro ✓" else "Get Pro")
        menu?.add(0, 2, 1, if (LicenseValidator.isPro(this)) "Deactivate" else "Enter License Key")
        menu?.add(0, 3, 2, "Reload")
        menu?.add(0, 4, 3, "About")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
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
            3 -> retryLoadGateway()
            4 -> showAbout()
        }
        return true
    }

    private fun showAbout() {
        webView.loadDataWithBaseURL(
            null,
            """
            <html><body style="background:#111;color:#eee;padding:40px;font-family:sans-serif">
            <h2 style="color:#00e5ff">ZeroClaw Android</h2>
            <p>Version 1.0.0-alpha</p>
            <p>License: ${if (LicenseValidator.isPro(this)) "Pro ✓" else "Free"}</p>
            <hr style="border-color:#333">
            <p><a href="https://github.com/KingKaonix/zeroclaw-android" style="color:#00e5ff">GitHub</a></p>
            <p>Built with ❤️ by KNET</p>
            </body></html>
            """.trimIndent(),
            "text/html", "UTF-8", null
        )
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

    /**
     * Attempt to load the ZeroClaw gateway. On connection error, retry with 1.5s delay.
     * After [retries] attempts gives up and shows an error page.
     */
    private fun retryLoadGateway(retries: Int = 20) {
        if (retries <= 0) {
            webView.webViewClient = WebViewClient()
            webView.loadData(
                "<html><body style='color:#eee;background:#111;padding:40px;font-family:sans-serif'>" +
                "<h2>ZeroClaw not responding</h2>" +
                "<p>The agent service did not start in time. Use the Reload option from the menu, " +
                "or restart the app.</p></body></html>",
                "text/html", "utf-8"
            )
            return
        }

        webView.webViewClient = object : WebViewClient() {
            private var loaded = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loaded = true
                if (url != null && url.startsWith("http://127.0.0.1") &&
                    !LicenseValidator.isPro(this@MainActivity)
                ) {
                    view?.evaluateJavascript(WATERMARK_JS, null)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (loaded) return // page loaded once — ignore subsequent errors
                // Gateway not ready yet; retry after delay
                view?.postDelayed({ retryLoadGateway(retries - 1) }, 1500)
            }
        }
        webView.loadUrl("http://127.0.0.1:18789")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private val WATERMARK_JS = """
            (function() {
                if (document.getElementById('zc-watermark')) return;
                var wm = document.createElement('div');
                wm.id = 'zc-watermark';
                wm.style.cssText = 'position:fixed;bottom:8px;right:12px;z-index:9999;' +
                    'color:rgba(255,255,255,0.15);font-size:11px;font-family:monospace;' +
                    'pointer-events:none;user-select:none;';
                wm.textContent = 'ZeroClaw \u00b7 UNLICENSED';
                document.body.appendChild(wm);
            })();
        """.trimIndent()
    }
}
