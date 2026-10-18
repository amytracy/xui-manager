package dev.autorasqr.xuimanager.data.error

import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs [block] on IO (Keystore and network) and turns any failure into an AppException for the UI. */
suspend fun <T> ioMapped(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppException) {
        throw e
    } catch (e: Exception) {
        throw AppException(e.toAppError(), e)
    }
}
