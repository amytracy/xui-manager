package dev.autorasqr.xuimanager.data.deploy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's decoy page for a deploy job, kept on disk until run.sh is launched so a relaunch after process death
 * still uploads it.
 */
@Singleton
class SitePageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun put(jobId: Long, page: ByteArray) {
        val file = file(jobId)
        file.parentFile?.mkdirs()
        file.writeBytes(page)
    }

    fun get(jobId: Long): ByteArray? = file(jobId).takeIf { it.isFile }?.readBytes()

    fun remove(jobId: Long) {
        file(jobId).delete()
    }

    private fun file(jobId: Long) = File(context.noBackupFilesDir, "site-pages/job-$jobId.html")
}
