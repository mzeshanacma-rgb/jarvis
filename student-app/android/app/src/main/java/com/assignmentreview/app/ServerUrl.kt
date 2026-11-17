package com.assignmentreview.app

import java.net.URI

/** Rules for the school server address. Pure Kotlin so it is unit-tested on the JVM. */
object ServerUrl {

    sealed interface Result {
        data class Ok(val url: String) : Result
        data object Invalid : Result
        data object HttpsRequired : Result
    }

    /**
     * Turns what the user typed into "scheme://host[:port]" (no path, no trailing slash).
     * A missing scheme becomes https. Plain http is allowed only when [allowHttp] is true (debug builds).
     */
    fun normalize(input: String, allowHttp: Boolean): Result {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return Result.Invalid
        if (!s.contains("://")) s = "https://$s"
        val uri = try { URI(s) } catch (e: Exception) { return Result.Invalid }
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid
        val host = uri.host?.lowercase() ?: return Result.Invalid
        if (scheme != "https" && scheme != "http") return Result.Invalid
        if (!host.contains('.') && host != "localhost") return Result.Invalid
        if (scheme == "http" && !allowHttp) return Result.HttpsRequired
        if (uri.userInfo != null) return Result.Invalid
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return Result.Ok("$scheme://$host$port")
    }

    /** True when [url] is on the same scheme, host and port as [base]. Other pages open in the browser. */
    fun sameOrigin(base: String, url: String): Boolean {
        return try {
            val a = URI(base)
            val b = URI(url)
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                effectivePort(a) == effectivePort(b)
        } catch (e: Exception) {
            false
        }
    }

    private fun effectivePort(u: URI): Int = when {
        u.port != -1 -> u.port
        u.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    /** The server answers GET /api/health with {"ok":true,...}. */
    fun isHealthResponse(code: Int, body: String): Boolean =
        code == 200 && Regex("\"ok\"\\s*:\\s*true").containsMatchIn(body)
}
