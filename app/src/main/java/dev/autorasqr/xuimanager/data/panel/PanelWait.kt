package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Waits for a restarting panel to answer again: gives it [initialDelayMs] to go down, then polls its status every
 * 2 s for up to [timeoutMs]. Returns the first status it gets; rethrows the last failure on timeout.
 */
suspend fun PanelApi.awaitOnline(initialDelayMs: Long = 3_000, timeoutMs: Long = 60_000): PanelStatus {
    delay(initialDelayMs)
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Exception? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            return status()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
        }
        delay(2_000)
    }
    throw last ?: AppException(AppError.PanelStartFailed)
}
