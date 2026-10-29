package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.InboundSaveStep
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

class InboundSaveTest {

    /** Only what saving touches; any other call fails the test. */
    private class FakePanel(
        var stored: JsonObject,
        val updateError: String? = null,
        val updateLands: Boolean = true,
        val restartError: String? = null,
    ) : PanelApi by unexpected() {
        val calls = mutableListOf<String>()

        override suspend fun getInbound(id: Int): JsonObject = stored

        override suspend fun updateInbound(id: Int, inbound: JsonObject) {
            calls += "update"
            if (updateLands) stored = JsonObject(stored + inbound)
            updateError?.let { throw AppException(AppError.PanelError(it)) }
        }

        override suspend fun setFallbacks(masterId: Int, fallbacks: List<JsonObject>) {
            calls += "fallbacks"
        }

        override suspend fun restartXray() {
            calls += "restartXray"
            restartError?.let { throw AppException(AppError.PanelError(it)) }
        }
    }

    private fun inbound(remark: String, nodeId: Int? = null) = JsonObject(
        buildMap {
            put("remark", JsonPrimitive(remark))
            put("port", JsonPrimitive(443))
            put("protocol", JsonPrimitive("vless"))
            nodeId?.let { put("nodeId", JsonPrimitive(it)) }
        },
    )

    private suspend fun expectWarning(block: suspend () -> Unit): AppError.InboundSavedWithWarning {
        try {
            block()
        } catch (e: AppException) {
            return e.error as? AppError.InboundSavedWithWarning ?: throw AssertionError("not a warning: ${e.error}")
        }
        fail("no warning")
        throw IllegalStateException()
    }

    @Test
    fun `an inbound on a node does not restart the main panel's Xray`() = runTest {
        val panel = FakePanel(inbound("old", nodeId = 1))
        assertEquals(7, panel.saveInbound(7, inbound("new", nodeId = 1), fallbacks = null))
        assertEquals(listOf("update"), panel.calls)
    }

    @Test
    fun `a local inbound restarts Xray and sends changed fallbacks`() = runTest {
        val panel = FakePanel(inbound("old"))
        panel.saveInbound(7, inbound("new"), fallbacks = emptyList())
        assertEquals(listOf("update", "fallbacks", "restartXray"), panel.calls)
    }

    @Test
    fun `a failing Xray restart after the save is only a warning`() = runTest {
        val panel = FakePanel(inbound("old"), restartError = "xray is busy")
        val warning = expectWarning { panel.saveInbound(7, inbound("new"), null) }
        assertEquals(InboundSaveStep.XRAY_RESTART, warning.step)
        assertEquals(AppError.PanelError("xray is busy"), warning.cause)
    }

    @Test
    fun `an error for a row the panel did write is reported as saved`() = runTest {
        val panel = FakePanel(inbound("old", nodeId = 1), updateError = "node sync failed")
        val warning = expectWarning { panel.saveInbound(7, inbound("new", nodeId = 1), null) }
        assertEquals(InboundSaveStep.UPDATE, warning.step)
        assertEquals("new", (panel.stored["remark"] as JsonPrimitive).content)
    }

    @Test
    fun `an error for a row that did not change stays an error`() = runTest {
        val panel = FakePanel(inbound("old"), updateError = "port is taken", updateLands = false)
        try {
            panel.saveInbound(7, inbound("new"), null)
            fail("no error")
        } catch (e: AppException) {
            assertEquals(AppError.PanelError("port is taken"), e.error)
        }
        assertFalse("restartXray" in panel.calls)
    }

    @Test
    fun `landed needs a changed row that carries what was sent`() {
        val before = inbound("old")
        assertTrue(InboundEditorRepositoryImpl.landed(before, inbound("new"), inbound("new")))
        assertFalse(InboundEditorRepositoryImpl.landed(before, before, inbound("new")))
        assertFalse(InboundEditorRepositoryImpl.landed(before, inbound("other"), inbound("new")))
        assertFalse(InboundEditorRepositoryImpl.landed(before, null, inbound("new")))
    }

    private companion object {
        fun unexpected(): PanelApi = Proxy.newProxyInstance(PanelApi::class.java.classLoader, arrayOf(PanelApi::class.java)) { _, method, _ ->
            throw AssertionError("unexpected call: ${method.name}")
        } as PanelApi
    }
}
