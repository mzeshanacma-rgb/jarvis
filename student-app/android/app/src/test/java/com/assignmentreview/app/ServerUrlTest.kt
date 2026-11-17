package com.assignmentreview.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    private fun ok(input: String, allowHttp: Boolean = false) =
        (ServerUrl.normalize(input, allowHttp) as ServerUrl.Result.Ok).url

    @Test fun addsHttpsAndStripsPath() {
        assertEquals("https://app.school.org", ok("app.school.org"))
        assertEquals("https://app.school.org", ok("  https://App.School.org/login/ "))
        assertEquals("https://app.school.org:8443", ok("https://app.school.org:8443/#/"))
    }

    @Test fun httpOnlyInDebug() {
        assertEquals(ServerUrl.Result.HttpsRequired, ServerUrl.normalize("http://10.0.2.2:3000", allowHttp = false))
        assertEquals("http://10.0.2.2:3000", ok("http://10.0.2.2:3000", allowHttp = true))
    }

    @Test fun rejectsBadInput() {
        for (bad in listOf("", "not a url", "ftp://x.org", "https://", "intranet", "https://user:pw@x.org")) {
            assertEquals(bad, ServerUrl.Result.Invalid, ServerUrl.normalize(bad, allowHttp = true))
        }
    }

    @Test fun sameOrigin() {
        assertTrue(ServerUrl.sameOrigin("https://a.org", "https://a.org/api/files/1?exp=1"))
        assertTrue(ServerUrl.sameOrigin("https://a.org", "https://A.org:443/#/task/2"))
        assertFalse(ServerUrl.sameOrigin("https://a.org", "http://a.org/"))
        assertFalse(ServerUrl.sameOrigin("https://a.org", "https://evil.org/"))
        assertFalse(ServerUrl.sameOrigin("https://a.org", "https://a.org.evil.org/"))
        assertFalse(ServerUrl.sameOrigin("https://a.org", "javascript:alert(1)"))
    }

    @Test fun health() {
        assertTrue(ServerUrl.isHealthResponse(200, "{\"ok\":true,\"ai\":\"demo\"}"))
        assertFalse(ServerUrl.isHealthResponse(200, "<html>ok</html>"))
        assertFalse(ServerUrl.isHealthResponse(500, "{\"ok\":true}"))
    }
}
