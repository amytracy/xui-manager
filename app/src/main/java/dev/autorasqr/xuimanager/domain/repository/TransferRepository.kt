package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.data.transfer.TransferBundle

/** What the old phone shows: QR codes to scan, or a link plus a code passed on separately. */
class TransferExport(val qrCodes: List<String>, val link: String, val code: String, val serverCount: Int) {
    override fun toString() = "TransferExport(qrCodes=${qrCodes.size}, servers=$serverCount)"
}

interface TransferRepository {
    /** Every server of the app with its secrets; throws if there is nothing to transfer. */
    suspend fun export(): TransferExport

    /** Decodes a link with its code. Throws TransferException. */
    suspend fun openLink(link: String, code: String): TransferBundle

    /**
     * Saves the bundle's servers: a server already in the app (same host and SSH port) is updated, the rest are added.
     * Returns the id of the home server of the bundle.
     */
    suspend fun import(bundle: TransferBundle): Long
}
