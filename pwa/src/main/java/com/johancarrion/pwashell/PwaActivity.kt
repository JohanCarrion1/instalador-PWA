package com.johancarrion.pwashell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

/** App independiente: abre una sola PWA a pantalla completa. URL, nombre e ícono vienen de los recursos. */
class PwaActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var startUrl: String
    private var barColor = Color.BLACK
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startUrl = getString(R.string.pwa_url)
        barColor = getColor(R.color.pwa_theme) or 0xFF000000.toInt()

        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        applyBarIcons()

        root = FrameLayout(this).apply { setBackgroundColor(barColor) }
        web = WebView(this)
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        setupWebView()
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(startUrl)
    }

    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setDownloadListener { downloadUrl, _, _, _, _ -> openExternal(Uri.parse(downloadUrl)) }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") {
                    // Enlaces tocados hacia otro sitio van al navegador; las redirecciones (OAuth) se quedan aquí
                    if (request.hasGesture() && !request.isRedirect && !sameSite(u.host)) {
                        openExternal(u)
                        return true
                    }
                    return false
                }
                openExternal(u)
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
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

        web.webChromeClient = object : WebChromeClient() {
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

    private fun sameSite(host: String?): Boolean {
        val a = baseDomain(host)
        return a != null && a == baseDomain(Uri.parse(startUrl).host)
    }

    private fun baseDomain(host: String?): String? = host?.split('.')?.takeLast(2)?.joinToString(".")

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

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
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_FILE = 1001
    }
}
