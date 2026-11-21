package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitLowercaseTest {
    @Test fun `picked lowercase letter skips auto capital once`() {
        ExplicitLowercase.mark("a", now = 100)
        assertTrue(ExplicitLowercase.consume("a", now = 150))
        assertFalse(ExplicitLowercase.consume("a", now = 160))
    }

    @Test fun `other letter or stale mark does not count`() {
        ExplicitLowercase.mark("a", now = 100)
        assertFalse(ExplicitLowercase.consume("b", now = 150))
        assertFalse(ExplicitLowercase.consume("a", now = 160))
        ExplicitLowercase.mark("a", now = 100)
        assertFalse(ExplicitLowercase.consume("a", now = 1200))
    }

    @Test fun `uppercase pick is not marked`() {
        ExplicitLowercase.mark("A", now = 100)
        assertFalse(ExplicitLowercase.consume("A", now = 150))
    }
}
