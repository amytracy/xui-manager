package dev.autorasqr.xuimanager.data.db

import androidx.room.withTransaction
import javax.inject.Inject

/** Runs a block in one database transaction; a seam so repositories can be tested without Room. */
interface DbTransactions {
    suspend fun <R> run(block: suspend () -> R): R
}

class RoomDbTransactions @Inject constructor(private val db: AppDatabase) : DbTransactions {
    override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction { block() }
}
