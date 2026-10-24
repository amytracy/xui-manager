package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.domain.model.XrayUpdateOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayUpdateOfferTest {

    @Test
    fun `offers the newest tag even when the list is not sorted`() {
        assertEquals(
            XrayUpdateOffer("v26.6.27", "v26.10.3"),
            XrayUpdateRepositoryImpl.offerFrom("26.6.27", listOf("v26.7.1", "v26.10.3", "v26.9.0")),
        )
    }

    @Test
    fun `nothing to offer when xray is current or newer`() {
        assertNull(XrayUpdateRepositoryImpl.offerFrom("26.10.3", listOf("v26.10.3", "v26.9.0")))
        assertNull(XrayUpdateRepositoryImpl.offerFrom("v26.11.0", listOf("v26.10.3")))
    }

    @Test
    fun `an empty or unparsable list offers nothing`() {
        assertNull(XrayUpdateRepositoryImpl.offerFrom("26.6.27", emptyList()))
        assertNull(XrayUpdateRepositoryImpl.offerFrom("26.6.27", listOf("nightly", "")))
        assertEquals(
            XrayUpdateOffer("v26.6.27", "v26.7.1"),
            XrayUpdateRepositoryImpl.offerFrom("26.6.27", listOf("nightly", " v26.7.1 ")),
        )
    }

    @Test
    fun `an unknown running version still gets the newest tag`() {
        assertEquals(XrayUpdateOffer("", "v26.7.1"), XrayUpdateRepositoryImpl.offerFrom("", listOf("v26.7.1")))
    }

    @Test
    fun `done once xray runs the target or newer`() {
        assertTrue(XrayUpdateRepositoryImpl.isDone("running", "26.7.1", "v26.7.1"))
        assertTrue(XrayUpdateRepositoryImpl.isDone("Running", "26.8.0", "v26.7.1"))
        assertFalse(XrayUpdateRepositoryImpl.isDone("stop", "26.7.1", "v26.7.1"))
        assertFalse(XrayUpdateRepositoryImpl.isDone("running", "26.6.27", "v26.7.1"))
        assertFalse(XrayUpdateRepositoryImpl.isDone("running", "", "v26.7.1"))
    }
}
