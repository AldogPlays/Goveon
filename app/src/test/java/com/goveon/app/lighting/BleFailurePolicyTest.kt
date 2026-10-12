package com.goveon.app.lighting

import org.junit.Assert.assertEquals
import org.junit.Test

class BleFailurePolicyTest {
    @Test fun `permission and unsupported status six have distinct code spaces`() {
        assertEquals(BleFailureReason.PERMISSION, bleSubmissionFailureReason(6))
        assertEquals(BleFailureReason.UNSUPPORTED, bleAttributeFailureReason(6))
    }
    @Test fun `attribute auth or invalid profile errors stop while congestion remains retryable`() {
        listOf(5, 8, 15).forEach { assertEquals(BleFailureReason.AUTHENTICATION, bleAttributeFailureReason(it)) }
        listOf(2, 3, 6, 7, 13).forEach { assertEquals(BleFailureReason.UNSUPPORTED, bleAttributeFailureReason(it)) }
        listOf(133, 143, 257).forEach { assertEquals(BleFailureReason.RETRYABLE, bleAttributeFailureReason(it)) }
    }
    @Test fun `submission radio permission bond and remote write restrictions stop automatic retries`() {
        assertEquals(BleFailureReason.RADIO_UNAVAILABLE, bleSubmissionFailureReason(1))
        assertEquals(BleFailureReason.PERMISSION, bleSubmissionFailureReason(2))
        assertEquals(BleFailureReason.AUTHENTICATION, bleSubmissionFailureReason(3))
        assertEquals(BleFailureReason.UNSUPPORTED, bleSubmissionFailureReason(200))
        assertEquals(BleFailureReason.RETRYABLE, bleSubmissionFailureReason(201))
    }
}
