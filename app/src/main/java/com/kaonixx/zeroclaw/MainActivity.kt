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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
                userAgentString = "SimonAI-Android/1.0"
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
        menu?.add(0, 2, 1, if (LicenseValidator.isPro(this)) "Deactivate" else "Activate License")
        menu?.add(0, 3, 2, "Reload")
        menu?.add(0, 4, 3, "About")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                if (!LicenseValidator.isPro(this)) {
                    // Open Gumroad product page
                    webView.loadUrl(GUMROAD_URL)
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
                    showActivationDialog()
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
            <h2 style="color:#00e5ff">SimonAI</h2>
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

    /**
     * Activation dialog with two tabs:
     *  1. Auto — enter email + Gumroad sale ID, fetch key from license server
     *  2. Manual — paste license key + signature directly
     */
    private fun showActivationDialog() {
        val tabs = android.widget.TabHost(this)
        tabs.setup()

        // --- Tab 1: Auto (Gumroad sale ID lookup) ---
        val emailInput = android.widget.EditText(this).apply {
            hint = "Email used on Gumroad"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or
                        android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val saleIdInput = android.widget.EditText(this).apply {
            hint = "Gumroad sale ID (from receipt email)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        // --- Tab 2: Manual key paste ---
        val keyInput = android.widget.EditText(this).apply {
            hint = "License key  (ZCLAW-1:...)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val sigInput = android.widget.EditText(this).apply {
            hint = "Signature"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        // Build layout — simple vertical stack with a divider between sections
        val autoLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(labelView("Email"))
            addView(emailInput)
            addView(labelView("Sale ID"))
            addView(saleIdInput)
        }

        val manualLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(labelView("License Key"))
            addView(keyInput)
            addView(labelView("Signature"))
            addView(sigInput)
        }

        val divider = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 24, 0, 16) }
        }

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(autoLayout)
            addView(divider)
            addView(labelView("— or paste key manually —").apply {
                setPadding(48, 0, 48, 0)
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            })
            addView(manualLayout)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(rootLayout)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Activate Pro")
            .setView(scrollView)
            .setPositiveButton("Activate") { _, _ ->
                val email  = emailInput.text.toString().trim()
                val saleId = saleIdInput.text.toString().trim()
                val key    = keyInput.text.toString().trim()
                val sig    = sigInput.text.toString().trim()

                when {
                    // Auto path — fetch from server
                    email.isNotEmpty() && saleId.isNotEmpty() -> {
                        activateViaServer(email, saleId)
                    }
                    // Manual path
                    key.isNotEmpty() && sig.isNotEmpty() -> {
                        if (LicenseValidator.activate(this, key, sig)) {
                            Toast.makeText(this, "Pro activated ✓", Toast.LENGTH_SHORT).show()
                            recreate()
                        } else {
                            Toast.makeText(this, "Invalid license key", Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> Toast.makeText(
                        this,
                        "Enter your email + sale ID, or paste a license key",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNeutralButton("Buy Pro") { _, _ -> webView.loadUrl(GUMROAD_URL) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun labelView(text: String) = android.widget.TextView(this).apply {
        this.text = text
        setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        textSize = 11f
        setPadding(0, 16, 0, 4)
    }

    /**
     * Call the license server to fetch a key by Gumroad sale ID.
     * Runs on a background thread; updates UI on main thread.
     */
    private fun activateViaServer(email: String, saleId: String) {
        val progressToast = Toast.makeText(this, "Verifying purchase…", Toast.LENGTH_LONG)
        progressToast.show()

        Thread {
            try {
                val serverUrl = URL("$LICENSE_SERVER_URL/activate")
                val conn = serverUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000

                val body = """{"email":"$email","saleId":"$saleId"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                val responseCode = conn.responseCode
                val responseBody = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(responseBody)

                runOnUiThread {
                    progressToast.cancel()
                    if (responseCode == 200 && json.optBoolean("success")) {
                        val licenseKey = json.getString("licenseKey")
                        val signature  = json.getString("signature")
                        if (LicenseValidator.activate(this, licenseKey, signature)) {
                            Toast.makeText(this, "Pro activated ✓", Toast.LENGTH_SHORT).show()
                            recreate()
                        } else {
                            Toast.makeText(this, "Key received but failed local verification", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val error = json.optString("error", "Unknown error")
                        Toast.makeText(this, "Activation failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressToast.cancel()
                    Toast.makeText(
                        this,
                        "Network error. Try the manual key method.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
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
                "<h2>SimonAI not responding</h2>" +
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
        // Update these after deploying the Cloudflare Worker and creating your Gumroad product
        private const val GUMROAD_URL        = "https://mulikjo.gumroad.com/l/zeroclaw-android"
        private const val LICENSE_SERVER_URL = "https://zeroclaw-license.joemulik.workers.dev"

        private val WATERMARK_JS = """
            (function() {
                if (document.getElementById('zc-watermark')) return;
                var wm = document.createElement('div');
                wm.id = 'zc-watermark';
                wm.style.cssText = 'position:fixed;bottom:8px;right:12px;z-index:9999;' +
                    'color:rgba(255,255,255,0.15);font-size:11px;font-family:monospace;' +
                    'pointer-events:none;user-select:none;';
                wm.textContent = 'SimonAI \u00b7 UNLICENSED';
                document.body.appendChild(wm);
            })();
        """.trimIndent()
    }
}
