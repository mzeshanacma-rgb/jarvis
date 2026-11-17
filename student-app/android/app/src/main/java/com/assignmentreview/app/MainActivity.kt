package com.assignmentreview.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The Android app is a thin, secure shell around the school's Assignment Review web app.
 * It remembers the school server, opens it in a WebView, and adds what the browser alone lacks:
 * camera and file picking, an offline/error screen, and the Android back button.
 */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("app", Context.MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var server: String? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        val photo = cameraUri
        cameraUri = null
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            result.data?.clipData != null -> result.data!!.clipData!!.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } }
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            photo != null && photoHasData(photo) -> arrayOf(photo)
            else -> null
        }
        cb.onReceiveValue(uris)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val wv = webView
            if (wv != null && wv.parent != null && wv.canGoBack()) wv.goBack()
            else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.bg)) }
        setContentView(root)
        // Keep content clear of the status bar, navigation bar and keyboard (edge-to-edge on Android 15).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Debug builds accept a server address from the launcher intent (used by the CI device test).
        if (BuildConfig.DEBUG) intent?.getStringExtra("server_url")?.let { prefs.edit().putString("server", it).apply() }

        server = prefs.getString("server", null) ?: BuildConfig.SERVER_URL.ifBlank { null }
        if (server == null) showSetup(clearSaved = false) else showWeb(server!!, savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) intent.getStringExtra("server_url")?.let {
            prefs.edit().putString("server", it).apply()
            server = it
            showWeb(it, null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    // ---------------- Setup screen ----------------

    fun showSetup(clearSaved: Boolean) {
        if (clearSaved) {
            prefs.edit().remove("server").apply()
            server = null
        }
        webView?.let { root.removeView(it) }
        val input = EditText(this).apply {
            hint = getString(R.string.setup_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
            setText(BuildConfig.SERVER_URL)
            contentDescription = getString(R.string.setup_title)
        }
        val status = TextView(this).apply { setTextColor(ContextCompat.getColor(context, R.color.muted)) }
        val connect = primaryButton(getString(R.string.setup_connect))

        fun submit() {
            when (val r = ServerUrl.normalize(input.text.toString(), allowHttp = BuildConfig.DEBUG)) {
                ServerUrl.Result.Invalid -> showStatus(status, getString(R.string.setup_invalid), bad = true)
                ServerUrl.Result.HttpsRequired -> showStatus(status, getString(R.string.setup_https_only), bad = true)
                is ServerUrl.Result.Ok -> {
                    connect.isEnabled = false
                    showStatus(status, getString(R.string.setup_checking), bad = false)
                    thread {
                        val ok = checkHealth(r.url)
                        runOnUiThread {
                            connect.isEnabled = true
                            if (ok) {
                                prefs.edit().putString("server", r.url).apply()
                                server = r.url
                                showWeb(r.url, null)
                            } else showStatus(status, getString(R.string.setup_unreachable), bad = true)
                        }
                    }
                }
            }
        }
        connect.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { submit(); true } else false }

        showPanel(
            title = getString(R.string.setup_title),
            body = getString(R.string.setup_intro),
            extra = listOf(input, connect, status),
        )
        input.requestFocus()
    }

    private fun checkHealth(base: String): Boolean = try {
        val conn = URL("$base/api/health").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText().take(2000) } else ""
        conn.disconnect()
        ServerUrl.isHealthResponse(code, body)
    } catch (e: Exception) {
        false
    }

    // ---------------- Error screen ----------------

    private fun showError() {
        webView?.let { root.removeView(it) }
        val retry = primaryButton(getString(R.string.retry)).apply {
            setOnClickListener { server?.let { s -> showWeb(s, null) } }
        }
        val change = secondaryButton(getString(R.string.change_server)).apply {
            setOnClickListener { showSetup(clearSaved = true) }
        }
        showPanel(getString(R.string.error_title), getString(R.string.error_body), listOf(retry, change))
    }

    // ---------------- Web app ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(base: String, state: Bundle?) {
        root.removeAllViews()
        val wv = webView ?: WebView(this).also { webView = it; configure(it) }
        (wv.parent as? ViewGroup)?.removeView(wv)
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { gravity = Gravity.TOP })
        backCallback.isEnabled = true
        if (state == null || wv.restoreState(state) == null) wv.loadUrl(base)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configure(wv: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString AssignmentReviewAndroid/${BuildConfig.VERSION_NAME}"
        }
        wv.addJavascriptInterface(AppBridge(this), "AndroidApp")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val base = server ?: return true
                if (ServerUrl.sameOrigin(base, url)) return false
                openExternal(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress?.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress?.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Only a failed page load matters; failed images or API calls are handled by the web app.
                if (request.isForMainFrame) showError()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress?.progress = newProgress
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val wantsImage = params.acceptTypes.any { it.startsWith("image") }
                val camera = if (wantsImage) cameraIntent() else null
                val intent = if (params.isCaptureEnabled && camera != null) {
                    camera
                } else {
                    Intent.createChooser(params.createIntent(), getString(R.string.choose_file)).apply {
                        if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
                    }
                }
                return try {
                    pickFile.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    cameraUri = null
                    Toast.makeText(this@MainActivity, R.string.no_camera, Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
    }

    /** Camera intent that writes the photo to a private cache file shared through FileProvider. */
    private fun cameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000) it.delete() }
        val file = File.createTempFile("copy-", ".jpg", dir)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        cameraUri = uri
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    private fun photoHasData(uri: Uri): Boolean = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length > 0 } ?: false
    } catch (e: Exception) {
        false
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // nothing can open it; stay in the app
        }
    }

    // ---------------- Small native UI helpers ----------------

    private fun showPanel(title: String, body: String, extra: List<View>) {
        root.removeAllViews()
        backCallback.isEnabled = false
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(ContextCompat.getColor(context, R.color.brand))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        col.addView(TextView(this).apply {
            text = title
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, dp(12), 0, dp(8))
        })
        col.addView(TextView(this).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(16))
        })
        extra.forEach { v ->
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        root.addView(ScrollView(this).apply { addView(col) })
    }

    private fun showStatus(view: TextView, text: String, bad: Boolean) {
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, if (bad) R.color.bad else R.color.muted))
        view.announceForAccessibility(text)
    }

    private fun primaryButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(ContextCompat.getColor(context, R.color.brand_ink))
        backgroundTintList = ContextCompat.getColorStateList(context, R.color.brand)
        minHeight = dp(48)
    }

    private fun secondaryButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
