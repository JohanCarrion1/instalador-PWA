package com.johancarrion.pwashell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * App independiente: abre una sola PWA a pantalla completa con un navegador interno.
 * Toda la navegación (incluido el inicio de sesión y las ventanas emergentes) ocurre dentro de la app.
 */
class PwaActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var startUrl: String
    private var barColor = Color.BLACK
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** Ventanas emergentes abiertas (login OAuth, target=_blank...), la última es la de arriba. */
    private val popups = ArrayList<FrameLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startUrl = getString(R.string.pwa_url)
        barColor = getColor(R.color.pwa_theme) or 0xFF000000.toInt()

        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        applyBarIcons()

        root = FrameLayout(this).apply { setBackgroundColor(barColor) }
        web = WebView(this)
        configure(web)
        web.webViewClient = PwaClient(isPopup = false)
        web.webChromeClient = MainChrome()
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(startUrl)
    }

    /** Ajustes comunes para la vista principal y las ventanas emergentes. */
    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Identidad de navegador: sin "wv" ni "Version/4.0", marcas de Chrome y sin X-Requested-With.
        // Así servicios como Google o Xiaomi no tratan la app como un WebView incrustado.
        val ua = s.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        s.userAgentString = ua
        val major = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: "126"
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            fun brand(name: String, v: String) = UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(v).setFullVersion("$v.0.0.0").build()
            val metadata = UserAgentMetadata.Builder()
                .setBrandVersionList(
                    listOf(brand("Chromium", major), brand("Google Chrome", major), brand("Not.A/Brand", "24"))
                )
                .setMobile(true)
                .build()
            WebSettingsCompat.setUserAgentMetadata(s, metadata)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setDownloadListener { downloadUrl, _, _, _, _ -> openExternal(Uri.parse(downloadUrl)) }
    }

    private inner class PwaClient(private val isPopup: Boolean) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            return when (u.scheme) {
                "http", "https" -> false // todo se queda dentro de la app, como en un navegador
                "intent" -> {
                    try {
                        val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            component = null
                            selector = null
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(i)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> {
                    openExternal(u) // tel:, mailto:, market:, etc.
                    true
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!isPopup && request.isForMainFrame) {
                val target = request.url.toString().replace("'", "%27")
                val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                    "<body style='font-family:sans-serif;text-align:center;padding:48px 24px;background:#fff;color:#222'>" +
                    "<h2>Sin conexi&oacute;n</h2><p>No se pudo cargar la p&aacute;gina.</p>" +
                    "<button style='font-size:18px;padding:12px 28px' onclick=\"location.href='$target'\">Reintentar</button>" +
                    "</body></html>"
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    }

    private inner class MainChrome : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val popup = WebView(this@PwaActivity)
            configure(popup)
            popup.webViewClient = PwaClient(isPopup = true)
            popup.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) = closePopup(window)
            }

            val frame = FrameLayout(this@PwaActivity).apply { setBackgroundColor(Color.WHITE) }
            frame.addView(popup, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val close = TextView(this@PwaActivity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(0xCC000000.toInt())
                setOnClickListener { closePopup(popup) }
            }
            frame.addView(close, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            })
            root.addView(frame, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            popups.add(frame)

            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            web.visibility = View.GONE
            root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            web.visibility = View.VISIBLE
            applyBarIcons()
        }
    }

    private fun popupWebView(frame: FrameLayout): WebView = frame.getChildAt(0) as WebView

    private fun closePopup(w: WebView) {
        val frame = popups.firstOrNull { popupWebView(it) === w } ?: return
        popups.remove(frame)
        root.removeView(frame)
        frame.removeAllViews()
        w.stopLoading()
        w.destroy()
        CookieManager.getInstance().flush()
    }

    private fun applyBarIcons() {
        val light = (0.299 * Color.red(barColor) + 0.587 * Color.green(barColor) + 0.114 * Color.blue(barColor)) / 255 > 0.6
        var flags = 0
        if (light) {
            @Suppress("DEPRECATION")
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val top = popups.lastOrNull()
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            top != null -> {
                val p = popupWebView(top)
                if (p.canGoBack()) p.goBack() else closePopup(p)
            }
            web.canGoBack() -> web.goBack()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        popups.toList().forEach { closePopup(popupWebView(it)) }
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_FILE = 1001
    }
}
