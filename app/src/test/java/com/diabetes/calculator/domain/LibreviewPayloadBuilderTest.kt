package com.diabetes.calculator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreviewPayloadBuilderTest {

    @Test
    fun `buildCarbsPayload crea entrada food y hash estable`() {
        val result = LibreviewPayloadBuilder.buildCarbsPayload(
            userToken = "token",
            recordNumber = 1234L,
            eventTimestampMillis = 1_700_000_000_000L,
            carbsGrams = 42f,
            operation = LibreviewPayloadOperation.UPSERT
        )

        val foodEntries = result.request.deviceData.measurementLog.foodEntries
        assertEquals(1, foodEntries.size)
        assertEquals(42f, foodEntries.first().gramsCarbs, 0.0001f)
        assertEquals(1234L, foodEntries.first().recordNumber)
        assertEquals("token", result.request.userToken)
        assertNotNull(result.payloadHash)
        assertTrue(result.payloadHash.isNotBlank())
    }

    @Test
    fun `buildInsulinPayload delete marca action deleted`() {
        val result = LibreviewPayloadBuilder.buildInsulinPayload(
            userToken = "token",
            recordNumber = 777L,
            eventTimestampMillis = 1_700_000_000_000L,
            units = 3.5f,
            operation = LibreviewPayloadOperation.DELETE
        )

        val insulinEntries = result.request.deviceData.measurementLog.insulinEntries
        assertEquals(1, insulinEntries.size)
        assertEquals(0f, insulinEntries.first().units, 0.0001f)
        assertEquals("deleted", insulinEntries.first().extendedProperties.action)
    }

    @Test
    fun `hashPayload cambia con operation o amount`() {
        val base = LibreviewPayloadBuilder.hashPayload(
            channel = "CARBS",
            operation = LibreviewPayloadOperation.UPSERT,
            recordNumber = 10L,
            eventTimestampMillis = 20L,
            amountValue = 30f
        )
        val changedOperation = LibreviewPayloadBuilder.hashPayload(
            channel = "CARBS",
            operation = LibreviewPayloadOperation.DELETE,
            recordNumber = 10L,
            eventTimestampMillis = 20L,
            amountValue = 30f
        )
        val changedAmount = LibreviewPayloadBuilder.hashPayload(
            channel = "CARBS",
            operation = LibreviewPayloadOperation.UPSERT,
            recordNumber = 10L,
            eventTimestampMillis = 20L,
            amountValue = 31f
        )

        assertTrue(base.isNotBlank())
        assertTrue(changedOperation.isNotBlank())
        assertTrue(changedAmount.isNotBlank())
        assertTrue(base != changedOperation)
        assertTrue(base != changedAmount)
    }

    @Test
    fun `buildCarbsPayload incluye serie de app en connectedDevices`() {
        val result = LibreviewPayloadBuilder.buildCarbsPayload(
            userToken = "token",
            recordNumber = 888L,
            eventTimestampMillis = 1_700_000_100_000L,
            carbsGrams = 30f,
            operation = LibreviewPayloadOperation.UPSERT,
            connectedInsulinDevices = listOf("APP-abc123", "APP-abc123", " ")
        )

        val insulinDevices = result.request.deviceData.connectedDevices.insulinDevices
        assertEquals(1, insulinDevices.size)
        assertEquals("APP-abc123", insulinDevices.first())
    }
}
