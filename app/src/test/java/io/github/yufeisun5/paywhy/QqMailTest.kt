package io.github.yufeisun5.paywhy

import io.github.yufeisun5.paywhy.auth.QqCredentials
import io.github.yufeisun5.paywhy.data.OutboxMail
import io.github.yufeisun5.paywhy.mail.QqMail
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.MimeMessage

class QqMailTest {
    private val credential = QqCredentials.Credential("sender@qq.com", "abcdefghijklmnop")
    private fun mail() = OutboxMail("test-1", "batch", "test-1", "TEST", "family@live.com", "",
        QqCredentials.identity(credential.address), "[PayWhy][TEST][妈妈] member=mother", "paywhy-test.txt",
        "{\"member_name\":\"妈妈\",\"raw\":\"已支付￥12.30\"}\n", 1000,
        provider = "qq", senderAddress = credential.address)

    @Test fun mimePreservesUnicodePlainTextAndStableMessageId() {
        val session = Session.getInstance(QqMail.properties())
        val message = QqMail().message(session, mail())
        val bytes = ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()
        val parsed = MimeMessage(session, ByteArrayInputStream(bytes))
        assertEquals(mail().subject, parsed.subject)
        assertEquals("<test-1@paywhy.invalid>", parsed.messageID)
        val content = parsed.content as Multipart
        assertEquals(2, content.count)
        assertTrue(content.getBodyPart(0).isMimeType("text/plain"))
        assertEquals(mail().body, String(content.getBodyPart(0).inputStream.readBytes(), Charsets.UTF_8))
        assertEquals(mail().body, String(content.getBodyPart(1).inputStream.readBytes(), Charsets.UTF_8))
        assertEquals("paywhy-test.txt", content.getBodyPart(1).fileName)
        assertFalse(String(bytes).contains(credential.code))
    }

    @Test fun smtpTlsAuthenticatesAndReceivesAcknowledgement() {
        SmtpServer().use { server ->
            assertEquals("accepted", QqMail().submit(server.session(), mail(), credential).status)
            server.await()
            assertEquals(credential.address, server.username)
            assertEquals(credential.code, server.password)
            assertTrue(server.data.contains("X-PayWhy-Batch-ID: batch"))
        }
    }
    @Test fun temporaryRejectionAfterDataIsRetryable() {
        SmtpServer(finalReply = "451 retry later").use { server ->
            val result = QqMail().submit(server.session(), mail(), credential)
            assertEquals("retry", result.status)
            assertEquals(900000, result.retryAfterMillis)
            server.await()
        }
    }
    @Test fun rejectedRecipientIsFailed() {
        SmtpServer(recipientReply = "550 unknown recipient").use { server ->
            assertEquals("failed", QqMail().submit(server.session(), mail(), credential).status)
            server.await()
            assertEquals("", server.data)
        }
    }
    @Test fun disconnectAfterDataIsUncertainAndDoesNotReconnect() {
        SmtpServer(finalReply = null).use { server ->
            assertEquals("uncertain", QqMail().submit(server.session(), mail(), credential).status)
            server.await()
            assertTrue(server.data.isNotEmpty())
        }
    }
    @Test fun wrongAuthorizationCodeFailsWithoutExposingServerReply() {
        SmtpServer(authReply = "535 secret-server-detail").use { server ->
            val result = QqMail().submit(server.session(), mail(), credential)
            assertEquals("failed", result.status)
            assertFalse(result.reason!!.contains("secret-server-detail"))
            server.await()
        }
    }
    @Test fun untrustedCertificateCannotReceiveCredentials() {
        SmtpServer().use { server ->
            val result = QqMail().submit(server.session(trust = false), mail(), credential)
            assertEquals("failed", result.status)
            assertEquals("", server.username)
        }
    }
    @Test fun hostnameMismatchCannotReceiveCredentials() {
        SmtpServer(certificateName = "wrong.invalid").use { server ->
            val result = QqMail().submit(server.session(), mail(), credential)
            assertNotEquals("accepted", result.status)
            assertEquals("", server.username)
        }
    }
    @Test fun headerInjectionAndAccountMismatchAreRejectedBeforeNetwork() {
        val sender = QqMail()
        val session = Session.getInstance(QqMail.properties())
        assertTrue(runCatching { sender.message(session, mail().copy(recipient = "a@example.com\r\nBcc: b@example.com")) }.isFailure)
        assertTrue(runCatching { sender.message(session, mail().copy(subject = "test\r\nBcc: b@example.com")) }.isFailure)
        assertTrue(runCatching { sender.send(mail().copy(senderAddress = "other@qq.com"), credential) }.isFailure)
    }

    private class SmtpServer(val finalReply: String? = "250 accepted", val authReply: String = "235 authenticated",
                             val recipientReply: String = "250 recipient ok", certificateName: String = "localhost") : AutoCloseable {
        private val cert = HeldCertificate.Builder().commonName(certificateName).addSubjectAlternativeName(certificateName).build()
        private val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        private val listener: ServerSocket = tls.sslContext().serverSocketFactory.createServerSocket(0)
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile var username = ""
        @Volatile var password = ""
        @Volatile var data = ""
        private val job = executor.submit {
            listener.accept().use { socket ->
                socket.soTimeout = 4000
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                fun reply(text: String) { output.write(text + "\r\n"); output.flush() }
                reply("220 localhost test SMTP")
                require(input.readLine().startsWith("EHLO"))
                reply("250-localhost\r\n250 AUTH LOGIN PLAIN")
                require(input.readLine() == "AUTH LOGIN")
                reply("334 VXNlcm5hbWU6")
                username = String(Base64.getDecoder().decode(input.readLine()))
                reply("334 UGFzc3dvcmQ6")
                password = String(Base64.getDecoder().decode(input.readLine()))
                reply(authReply)
                if (!authReply.startsWith("235")) return@use
                require(input.readLine().startsWith("MAIL FROM:")); reply("250 sender ok")
                require(input.readLine().startsWith("RCPT TO:")); reply(recipientReply)
                if (!recipientReply.startsWith("250")) {
                    // JavaMail resets the transaction after a rejected recipient.
                    if (input.readLine() == "RSET") reply("250 reset")
                    return@use
                }
                require(input.readLine() == "DATA"); reply("354 send message")
                val lines = mutableListOf<String>()
                while (true) {
                    val line = input.readLine() ?: error("unexpected disconnect")
                    if (line == ".") break
                    lines.add(line)
                }
                data = lines.joinToString("\r\n")
                if (finalReply != null) {
                    reply(finalReply)
                    if (!finalReply.startsWith("250") && input.readLine() == "RSET") reply("250 reset")
                }
            }
        }
        fun session(trust: Boolean = true): Session {
            val props = QqMail.properties()
            props.setProperty("mail.smtp.host", "localhost")
            props.setProperty("mail.smtp.port", listener.localPort.toString())
            props.setProperty("mail.smtp.timeout", "4000")
            if (trust) props["mail.smtp.ssl.socketFactory"] = HandshakeCertificates.Builder()
                .addTrustedCertificate(cert.certificate).build().sslSocketFactory()
            return Session.getInstance(props)
        }
        fun await() { job.get(8, TimeUnit.SECONDS) }
        override fun close() { listener.close(); executor.shutdownNow() }
    }
}
