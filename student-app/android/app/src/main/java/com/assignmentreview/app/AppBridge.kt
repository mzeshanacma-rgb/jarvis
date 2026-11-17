package com.assignmentreview.app

import android.webkit.JavascriptInterface

/**
 * Small bridge the web app can call as window.AndroidApp. Only pages from the school server are ever
 * loaded in the WebView (other links open in the browser), so only that server can call these.
 */
class AppBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun changeServer() {
        activity.runOnUiThread { activity.showSetup(clearSaved = true) }
    }

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME
}
