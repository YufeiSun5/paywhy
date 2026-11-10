package io.github.yufeisun5.paywhy.mail

import io.github.yufeisun5.paywhy.data.OutboxMail
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

data class SendResult(val status: String, val reason: String? = null, val retryAfterMillis: Long = 0)

/** No automatic HTTP retry: a connection loss after submission is an uncertain delivery. */
class GraphMail(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(75, TimeUnit.SECONDS).retryOnConnectionFailure(false)
    .followRedirects(false).followSslRedirects(false).build()) {
    fun send(mail: OutboxMail, token: String): SendResult {
        val request = Request.Builder().url("https://graph.microsoft.com/v1.0/me/sendMail")
            .header("Authorization", "Bearer $token")
            .post(payload(mail).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            return when (response.code) {
                202 -> SendResult("accepted")
                429 -> SendResult("retry", "微软暂时限流，将稍后重试。",
                    (response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(900) ?: 900) * 1000)
                401 -> SendResult("failed", "微软授权已失效，请重新登录后重试。")
                403 -> SendResult("failed", "微软拒绝发送，请检查 Mail.Send 授权及邮箱状态。")
                in 500..599 -> SendResult("uncertain", "微软服务异常，投递结果不确定；请先检查邮箱。")
                else -> SendResult("failed", "微软返回 HTTP ${response.code}；请检查邮箱及应用配置。")
            }
        }
    }

    fun payload(mail: OutboxMail): String = JSONObject().put("saveToSentItems", true).put("message",
        JSONObject().put("subject", mail.subject)
            .put("body", JSONObject().put("contentType", "Text").put("content", mail.body))
            .put("toRecipients", JSONArray().put(JSONObject().put("emailAddress",
                JSONObject().put("address", mail.recipient))))
            .put("internetMessageHeaders", JSONArray()
                .put(JSONObject().put("name", "x-paywhy-batch-id").put("value", mail.batchId))
                .put(JSONObject().put("name", "x-paywhy-schema").put("value", "paywhy.daily.v1")))
            .put("attachments", JSONArray().put(JSONObject()
                .put("@odata.type", "#microsoft.graph.fileAttachment")
                .put("name", mail.filename).put("contentType", "text/plain; charset=utf-8")
                .put("contentBytes", Base64.getEncoder().encodeToString(mail.body.toByteArray(Charsets.UTF_8))))))
        .toString()
}
