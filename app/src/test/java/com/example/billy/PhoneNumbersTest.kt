package com.example.billy

import com.example.billy.data.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test fun usNumberNeedsTenDigits() {
        assertTrue(PhoneNumbers.isValid("+1", "(415) 555-0123"))
        assertFalse(PhoneNumbers.isValid("+1", "555-0123"))
    }

    @Test fun e164StripsFormattingAndTrunkZero() {
        assertEquals("+14155550123", PhoneNumbers.toE164("+1", "415-555-0123"))
        assertEquals("+2348012345678", PhoneNumbers.toE164("+234", "0801 234 5678"))
    }

    @Test fun internationalLengthBounds() {
        assertFalse(PhoneNumbers.isValid("+44", "123"))
        assertFalse(PhoneNumbers.isValid("+234", "1".repeat(13)))
    }
}
