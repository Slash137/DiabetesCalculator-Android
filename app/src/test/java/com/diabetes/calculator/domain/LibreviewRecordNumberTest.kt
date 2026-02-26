package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibreviewRecordNumberTest {

    @Test
    fun `record number es determinista para el mismo seed`() {
        val value1 = LibreviewRecordNumber.from(
            registroId = 42,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = 1_700_000_000_000L
        )
        val value2 = LibreviewRecordNumber.from(
            registroId = 42,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = 1_700_000_000_000L
        )

        assertEquals(value1, value2)
    }

    @Test
    fun `cambiar canal o timestamp produce id diferente`() {
        val base = LibreviewRecordNumber.from(
            registroId = 9,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = 1_700_000_000_000L
        )
        val differentChannel = LibreviewRecordNumber.from(
            registroId = 9,
            channel = RegistroLibreviewSyncChannel.NFC_INSULIN,
            effectiveTimestamp = 1_700_000_000_000L
        )
        val differentTime = LibreviewRecordNumber.from(
            registroId = 9,
            channel = RegistroLibreviewSyncChannel.CARBS,
            effectiveTimestamp = 1_700_000_060_000L
        )

        assertNotEquals(base, differentChannel)
        assertNotEquals(base, differentTime)
    }
}
