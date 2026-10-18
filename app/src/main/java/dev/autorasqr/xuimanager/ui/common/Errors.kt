package dev.autorasqr.xuimanager.ui.common

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException

/** Repositories throw AppException; anything else is a bug and is shown as unexpected. */
fun Throwable.asAppError(): AppError = (this as? AppException)?.error ?: AppError.Unexpected(message ?: javaClass.simpleName)
