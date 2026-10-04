package com.aiia.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {
    @Test
    fun `a higher patch is newer`() {
        assertTrue(VersionCompare.isNewer("v0.0.6", "0.0.5"))
    }

    @Test
    fun `a higher minor is newer`() {
        assertTrue(VersionCompare.isNewer("0.1.0", "0.0.9"))
    }

    @Test
    fun `a higher major is newer`() {
        assertTrue(VersionCompare.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(VersionCompare.isNewer("0.0.5", "0.0.5"))
        assertFalse(VersionCompare.isNewer("v0.0.5", "0.0.5"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(VersionCompare.isNewer("0.0.4", "0.0.5"))
        assertFalse(VersionCompare.isNewer("v0.0.1", "0.0.5"))
    }

    @Test
    fun `missing components count as zero`() {
        assertTrue(VersionCompare.isNewer("0.0.6", "0.0.5.1"))
        assertFalse(VersionCompare.isNewer("0.0.5", "0.0.5.1"))
    }

    @Test
    fun `pre-release suffixes are ignored in comparison`() {
        assertTrue(VersionCompare.isNewer("v0.1.0", "v0.0.5-rc1"))
        assertFalse(VersionCompare.isNewer("v0.0.5-rc2", "v0.0.5-rc1"))
    }

    @Test
    fun `numeric comparison is not lexicographic`() {
        assertTrue(VersionCompare.isNewer("0.0.10", "0.0.9"))
        assertTrue(VersionCompare.isNewer("0.10.0", "0.9.0"))
    }

    @Test
    fun `garbage input does not claim an update`() {
        assertFalse(VersionCompare.isNewer("", "0.0.5"))
        assertFalse(VersionCompare.isNewer("nightly", "0.0.5"))
        assertFalse(VersionCompare.isNewer("0.0.5", ""))
    }
}
