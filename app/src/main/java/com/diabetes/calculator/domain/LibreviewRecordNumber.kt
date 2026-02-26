package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.RegistroLibreviewSyncChannel
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object LibreviewRecordNumber {
    fun hash64(seed: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.wrap(digest).long
    }

    fun from(
        registroId: Int,
        channel: RegistroLibreviewSyncChannel,
        effectiveTimestamp: Long
    ): Long {
        return hash64("$registroId:${channel.value}:$effectiveTimestamp")
    }
}
