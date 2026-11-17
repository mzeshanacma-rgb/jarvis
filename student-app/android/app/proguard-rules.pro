# Keep the JavaScript bridge methods called from the web app.
-keepclassmembers class com.assignmentreview.app.AppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
