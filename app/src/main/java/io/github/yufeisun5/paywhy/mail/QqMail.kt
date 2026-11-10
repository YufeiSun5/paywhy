package io.github.yufeisun5.paywhy.mail

import com.sun.mail.smtp.SMTPAddressFailedException
import com.sun.mail.smtp.SMTPSendFailedException
import io.github.yufeisun5.paywhy.auth.QqCredentials
import io.github.yufeisun5.paywhy.data.OutboxMail
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.*
import javax.mail.internet.*
import javax.mail.util.ByteArrayDataSource
import javax.net.ssl.SSLException

/** QQ only, implicit TLS with hostname verification. No account, body or server reply is logged. */
class QqMail {
    fun send(mail: OutboxMail, credential: QqCredentials.Credential): SendResult {
        require(mail.provider == "qq" && QqCredentials.normalize(mail.senderAddress) == credential.address &&
            mail.authGeneration == QqCredentials.identity(credential.address)) { "发件账户与原邮件不符" }
        val session = Session.getInstance(properties()).apply { debug = false }
        return submit(session, mail, credential)
    }

    internal fun submit(session: Session, mail: OutboxMail, credential: QqCredentials.Credential): SendResult {
        // Build before connecting; malformed local data is a configuration error, not uncertain delivery.
        val message = try { message(session, mail) }
            catch (_: Exception) { return SendResult("failed", "邮件格式无效，请检查发件与收件地址") }
        var transport: Transport? = null
        var submitting = false
        return try {
            transport = session.getTransport("smtp")
            transport.connect(session.getProperty("mail.smtp.host"),
                session.getProperty("mail.smtp.port").toInt(), credential.address, credential.code)
            submitting = true
            transport.sendMessage(message, message.allRecipients)
            SendResult("accepted")
        } catch (e: Exception) {
            classify(e, submitting)
        } finally {
            // QUIT failures must not change a successful DATA acknowledgement into a retry.
            runCatching { transport?.close() }
        }
    }

    internal fun message(session: Session, mail: OutboxMail): MimeMessage {
        fun address(value: String): InternetAddress {
            require(value.none { it.isISOControl() } && value.all { it.code < 128 })
            return InternetAddress(value, true).apply { validate(); require(!isGroup) }
        }
        require(mail.subject.none { it.isISOControl() })
        require(Regex("[A-Za-z0-9._-]+\\.txt").matches(mail.filename))
        require(Regex("[A-Za-z0-9_-]+").matches(mail.id) && Regex("[A-Za-z0-9_-]+").matches(mail.batchId))
        return object : MimeMessage(session) {
            override fun updateMessageID() { setHeader("Message-ID", "<${mail.id}@paywhy.invalid>") }
        }.apply {
            setFrom(address(mail.senderAddress))
            setRecipient(Message.RecipientType.TO, address(mail.recipient))
            setSubject(mail.subject, "UTF-8")
            sentDate = Date(mail.createdAt)
            setHeader("X-PayWhy-Batch-ID", mail.batchId)
            setHeader("X-PayWhy-Schema", "paywhy.daily.v1")
            val multipart = MimeMultipart("mixed")
            multipart.addBodyPart(MimeBodyPart().apply {
                setText(mail.body, "UTF-8")
                setHeader("Content-Transfer-Encoding", "base64")
            })
            multipart.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(mail.body.toByteArray(Charsets.UTF_8), "text/plain; charset=UTF-8"))
                fileName = mail.filename
                disposition = Part.ATTACHMENT
                setHeader("Content-Transfer-Encoding", "base64")
            })
            setContent(multipart)
            saveChanges()
        }
    }

    companion object {
        internal fun properties() = Properties().apply {
            setProperty("mail.smtp.host", "smtp.qq.com")
            setProperty("mail.smtp.port", "465")
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.auth.mechanisms", "LOGIN PLAIN")
            setProperty("mail.smtp.ssl.enable", "true")
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.ssl.protocols", "TLSv1.2")
            setProperty("mail.smtp.connectiontimeout", "20000")
            setProperty("mail.smtp.timeout", "30000")
            setProperty("mail.smtp.writetimeout", "30000")
            setProperty("mail.smtp.quitwait", "false")
            setProperty("mail.smtp.localhost", "[127.0.0.1]")
            setProperty("mail.debug", "false")
        }
        internal fun classify(error: Exception, submitting: Boolean): SendResult {
            // JavaMail chains SMTP response failures through nextException.
            val chain = mutableListOf<Throwable>()
            var item: Throwable? = error
            while (item != null && chain.size < 12 && chain.none { it === item }) {
                chain.add(item)
                item = if (item is MessagingException) item.nextException ?: item.cause else item.cause
            }
            if (chain.any { it is AuthenticationFailedException })
                return SendResult("failed", "QQ 邮箱认证失败，请确认已开启 SMTP 并使用邮箱授权码")
            if (chain.any { it is SSLException })
                return if (submitting) SendResult("uncertain", "加密连接中断，可能已投递；请先检查收件箱")
                else SendResult("failed", "无法建立可信的加密连接，请检查网络或系统时间")
            if (chain.any { it is SendFailedException && !it.validSentAddresses.isNullOrEmpty() })
                return SendResult("uncertain", "服务器可能已接受邮件，请先检查收件箱")
            val code = chain.firstNotNullOfOrNull {
                when (it) { is SMTPSendFailedException -> it.returnCode
                    is SMTPAddressFailedException -> it.returnCode; else -> null }
            }
            if (code != null && code in 400..499) return SendResult("retry", "邮件服务器暂时拒绝（$code），稍后重试", 15 * 60_000)
            if (code != null && code in 500..599) return SendResult("failed", "邮件服务器拒绝（$code），请检查账户、收件地址或发送限制")
            return if (submitting) SendResult("uncertain", "提交时连接中断，可能已投递；请先检查收件箱再重试")
            else SendResult("retry", "连接邮件服务器失败，稍后重试", 15 * 60_000)
        }
    }
}
