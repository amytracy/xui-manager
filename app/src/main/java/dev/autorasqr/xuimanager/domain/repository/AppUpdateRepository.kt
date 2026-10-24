package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.AppUpdateOffer
import dev.autorasqr.xuimanager.domain.model.UpdateCheck

interface AppUpdateRepository {
    /** Compares this build with the latest GitHub release. Remembered until the app restarts; [refresh] checks again. */
    suspend fun check(refresh: Boolean = false): UpdateCheck<AppUpdateOffer>

    /** True once per app run: whether the offer dialog should still be shown. */
    fun shouldPrompt(): Boolean
    fun markPrompted()
}
