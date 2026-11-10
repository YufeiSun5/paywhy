package io.github.yufeisun5.paywhy

import android.app.Application
import io.github.yufeisun5.paywhy.data.OutboxMail
import io.github.yufeisun5.paywhy.mail.GraphMail
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GraphMailTest {
    private val mail = OutboxMail("id", "batch", "id", "TEST", "test@example.invalid", "client", "identity",
        "[PayWhy][TEST][v1][合成用户]", "test.txt", "{\"raw_text\":\"中文文本\"}\n", 1000)
    @Test fun `plain body and attachment preserve exactly the same UTF8 content`() {
        val json = JSONObject(GraphMail().payload(mail))
        val message = json.getJSONObject("message")
        assertEquals("Text", message.getJSONObject("body").getString("contentType"))
        assertEquals(mail.body, message.getJSONObject("body").getString("content"))
        val attachment = message.getJSONArray("attachments").getJSONObject(0)
        assertEquals(mail.body, String(Base64.getDecoder().decode(attachment.getString("contentBytes")), Charsets.UTF_8))
        assertEquals("test@example.invalid", message.getJSONArray("toRecipients").getJSONObject(0).getJSONObject("emailAddress").getString("address"))
        assertFalse(json.toString().contains("Bearer"))
    }
    @Test fun `provider statuses distinguish accepted retry failed and uncertain`() {
        for ((code, status) in mapOf(202 to "accepted", 429 to "retry", 401 to "failed", 403 to "failed", 503 to "uncertain")) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertEquals("https://graph.microsoft.com/v1.0/me/sendMail", chain.request().url.toString())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .header("Retry-After", "1800").body("".toResponseBody()).build()
            }.build()
            val result = GraphMail(client).send(mail, "SYNTHETIC_TEST_TOKEN")
            assertEquals(status, result.status)
            assertEquals(1, calls)
            if (code == 429) assertEquals(1_800_000, result.retryAfterMillis)
        }
    }
}
