package org.example.minimalistbrowser

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.Toast
import com.example.minimalistbrowser.R
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

class MainActivity : Activity() {
    // Add bare domains here, in lowercase, without a scheme or path.
    private val blockedDomains = setOf("youtube.com", "reddit.com")

    private lateinit var address: EditText
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val root = findViewById<View>(R.id.browser_layout)
            root.fitsSystemWindows = false
            root.setOnApplyWindowInsetsListener { view, insets ->
                val padding = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                insets
            }
            root.requestApplyInsets()
        }

        address = findViewById(R.id.address)
        selectAddressOnTap()
        if (savedInstanceState == null) address.requestFocus()
        webView = findViewById(R.id.web_view)

        webView.setBackgroundColor(Color.rgb(18, 18, 18))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setAlgorithmicDarkeningAllowed(true)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                val reason = refusalReason(request.url.toString()) ?: return false
                showMessage(reason)
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!request.isForMainFrame) return null
                val reason = refusalReason(request.url.toString()) ?: return null
                return WebResourceResponse(
                    "text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                    ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8))
                )
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!address.hasFocus() && refusalReason(url) == null) {
                    address.setText(url)
                }
            }
        }

        address.setOnEditorActionListener { _, action, event ->
            val enterReleased = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP
            val submitted = action == EditorInfo.IME_ACTION_GO || enterReleased
            if (submitted) openAddress()
            submitted || event?.keyCode == KeyEvent.KEYCODE_ENTER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    // Android 12 and earlier deliver system Back here.
    @Suppress("DEPRECATION")
    @Deprecated("Used by Android 12 and earlier")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun selectAddressOnTap() {
        address.setSelectAllOnFocus(true)
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                // Let EditText finish its normal cursor handling first.
                address.post {
                    val length = address.length()
                    if (address.hasFocus() && length > 0) address.setSelection(0, length)
                }
                return true
            }
        })
        address.setOnTouchListener { _, event ->
            taps.onTouchEvent(event)
            false
        }
    }

    private fun openAddress() {
        val input = address.text.toString().trim()
        if (input.isEmpty()) return
        val url = addressOrSearchUrl(input)
        val reason = refusalReason(url)
        if (reason != null) {
            showMessage(reason)
            return
        }

        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(address.windowToken, 0)
        webView.requestFocus()
        address.setText(url)
        webView.loadUrl(url)
    }

    private fun addressOrSearchUrl(input: String): String {
        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
            return input
        }

        val candidate = "https://$input"
        val host = try {
            URI(candidate).host
        } catch (_: URISyntaxException) {
            null
        }
        if (host != null && ('.' in host || ':' in host || host.equals("localhost", true))) {
            return candidate
        }

        return Uri.Builder()
            .scheme("https")
            .authority("www.google.com")
            .path("/search")
            .appendQueryParameter("q", input)
            .build()
            .toString()
    }

    private fun refusalReason(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "Enter a valid web address"
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty() ||
            uri.rawUserInfo != null
        ) {
            return "Enter a valid web address"
        }

        return if (blockedDomains.any { host == it || host.endsWith(".$it") }) {
            "Blocked domain"
        } else {
            null
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
