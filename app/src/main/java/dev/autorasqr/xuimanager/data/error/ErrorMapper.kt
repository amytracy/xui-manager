package dev.autorasqr.xuimanager.data.error

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import net.schmizz.sshj.userauth.UserAuthException
import retrofit2.HttpException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/** Maps any failure to an AppError by walking the cause chain. Never includes secrets in messages. */
fun Throwable.toAppError(): AppError {
    causeChain().firstOrNull { it is AppException }?.let { return (it as AppException).error }
    // OkHttp tries every address of a host; a certificate failure on one route can end up suppressed under a later
    // "connection refused". It must never be reported as an ordinary network error.
    if (related().any { it is SSLPeerUnverifiedException || it is CertificateException || it is CertPathValidatorException }) {
        return AppError.PanelCertificateInvalid
    }
    for (cause in causeChain()) {
        when (cause) {
            is UserAuthException -> return AppError.SshAuthFailed
            is UnknownHostException -> return AppError.UnknownHost(cause.message.orEmpty())
            is SocketTimeoutException -> return AppError.Timeout
            is NoRouteToHostException -> return AppError.NetworkUnreachable
            is ConnectException -> return if (cause.message.orEmpty().contains("unreachable", ignoreCase = true)) {
                AppError.NetworkUnreachable
            } else {
                AppError.ConnectionRefused
            }
            is HttpException -> return when (cause.code()) {
                401, 403 -> AppError.PanelUnauthorized
                404 -> AppError.PanelNotFound
                else -> AppError.PanelError("HTTP ${cause.code()}")
            }
        }
        if (cause.javaClass.simpleName == "KeyDecryptionFailedException") return AppError.InvalidPrivateKey
    }
    val message = message.orEmpty()
    if (message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true)) {
        return AppError.Timeout
    }
    return AppError.Unexpected(message.ifEmpty { javaClass.simpleName })
}

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.take(16)

/** This throwable, its causes and everything suppressed along the way, breadth-first. */
private fun Throwable.related(): Sequence<Throwable> = sequence {
    val seen = HashSet<Throwable>()
    val queue = ArrayDeque(listOf(this@related))
    while (queue.isNotEmpty() && seen.size < 64) {
        val next = queue.removeFirst()
        if (!seen.add(next)) continue
        yield(next)
        next.cause?.let(queue::addLast)
        next.suppressed.forEach(queue::addLast)
    }
}
