package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.BrowsePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowsePathsTest {

    @Test fun parentOfHomeAndRootIsNull() {
        assertNull(BrowsePaths.parentOf("~"))
        assertNull(BrowsePaths.parentOf("/"))
        assertNull(BrowsePaths.parentOf(""))
        assertNull(BrowsePaths.parentOf("~/"))
    }

    @Test fun parentOfNestedPaths() {
        assertEquals("~", BrowsePaths.parentOf("~/code"))
        assertEquals("~/code", BrowsePaths.parentOf("~/code/GradatiON"))
        assertEquals("/home", BrowsePaths.parentOf("/home/me"))
        assertEquals("/", BrowsePaths.parentOf("/home"))
    }

    @Test fun childJoinsWithoutDoubleSlash() {
        assertEquals("~/code/GradatiON", BrowsePaths.child("~/code", "GradatiON"))
        assertEquals("~/code/GradatiON", BrowsePaths.child("~/code/", "GradatiON"))
        assertEquals("/home/me", BrowsePaths.child("/home", "me"))
        assertEquals("/home", BrowsePaths.child("/", "home"))
    }

    @Test fun normalizeTrimsAndDefaults() {
        assertEquals("~", BrowsePaths.normalize(""))
        assertEquals("/", BrowsePaths.normalize("/"))
        assertEquals("~/code", BrowsePaths.normalize("~/code/"))
    }

    @Test fun breadcrumbTitleUsesTrailingSegments() {
        assertEquals("~", BrowsePaths.breadcrumbTitle("~"))
        assertEquals("code / GradatiON", BrowsePaths.breadcrumbTitle("~/code/GradatiON"))
        assertEquals("… / a / b / c", BrowsePaths.breadcrumbTitle("~/x/a/b/c", maxSegments = 3))
        assertEquals("a / b", BrowsePaths.breadcrumbTitle("~/a/b", maxSegments = 3))
    }
}
