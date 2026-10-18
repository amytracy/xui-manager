package dev.autorasqr.xuimanager.data.error

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import net.schmizz.sshj.userauth.UserAuthException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ErrorMapperTest {

    @Test
    fun `network failures`() {
        assertEquals(AppError.Timeout, SocketTimeoutException("connect timed out").toAppError())
        assertEquals(AppError.ConnectionRefused, ConnectException("Connection refused").toAppError())
        assertEquals(AppError.NetworkUnreachable, ConnectException("ENETUNREACH (Network is unreachable)").toAppError())
        assertEquals(AppError.UnknownHost("vps.invalid"), UnknownHostException("vps.invalid").toAppError())
    }

    @Test
    fun `ssh auth failure inside a wrapper`() {
        assertEquals(AppError.SshAuthFailed, IOException("wrapped", UserAuthException("Exhausted available authentication methods")).toAppError())
    }

    @Test
    fun `certificate failure wins even when suppressed under another route failure`() {
        val handshake = SSLHandshakeException("handshake failed").apply { initCause(CertPathValidatorException("untrusted")) }
        val refused = ConnectException("Failed to connect to localhost/[::1]:443").apply { addSuppressed(handshake) }
        assertEquals(AppError.PanelCertificateInvalid, refused.toAppError())
        assertEquals(AppError.PanelCertificateInvalid, SSLPeerUnverifiedException("Hostname 1.2.3.4 not verified").toAppError())
    }

    @Test
    fun `http status codes`() {
        fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))
        assertEquals(AppError.PanelUnauthorized, http(401).toAppError())
        assertEquals(AppError.PanelNotFound, http(404).toAppError())
        assertEquals(AppError.PanelError("HTTP 502"), http(502).toAppError())
    }

    @Test
    fun `app exceptions pass through`() {
        assertEquals(AppError.NoInbound, IOException("x", AppException(AppError.NoInbound)).toAppError())
    }
}
