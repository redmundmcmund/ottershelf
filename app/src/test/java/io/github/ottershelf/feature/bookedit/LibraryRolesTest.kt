package io.github.ottershelf.feature.bookedit

import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.Library
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** LibraryRoles: BookOrbit 3.5's library roles for metadata edits, only where the server checks them. */
class LibraryRolesTest {

    private class FakeServer(var version: String? = "v3.5.0") {
        var account = "a"
        var libraries = listOf(
            Library(id = 1, name = "Fiction", accessLevel = "editor"),
            Library(id = 2, name = "Kids", accessLevel = "viewer"),
            Library(id = 3, name = "Shared", accessLevel = "owner"),
        )
        var offline = false
        var versionCalls = 0
        var libraryCalls = 0
        val roles = LibraryRoles(
            accountKey = { account },
            serverVersion = { versionCalls++; if (offline) throw IOException("offline"); version },
            libraries = { libraryCalls++; if (offline) throw IOException("offline"); libraries },
        )
    }

    private val editor = AuthUser(id = 2, username = "editor", permissions = listOf(EDIT_METADATA_PERMISSION))
    private val reader = AuthUser(id = 3, username = "kid")
    private val admin = AuthUser(id = 1, username = "admin", isSuperuser = true)

    @Test
    fun theVersionDecidesWhetherRolesCount() {
        assertTrue(LibraryRoles.enforcesLibraryRoles("v3.5.0"))
        assertTrue(LibraryRoles.enforcesLibraryRoles("3.5.1"))
        assertTrue(LibraryRoles.enforcesLibraryRoles("v3.10.0"))
        assertTrue(LibraryRoles.enforcesLibraryRoles("v4.0.0"))
        assertTrue(LibraryRoles.enforcesLibraryRoles("Local build"))
        assertTrue(LibraryRoles.enforcesLibraryRoles(null))
        assertFalse(LibraryRoles.enforcesLibraryRoles("v3.4.2"))
        assertFalse(LibraryRoles.enforcesLibraryRoles("v3.3.0"))
        assertFalse(LibraryRoles.enforcesLibraryRoles("2.9.0"))
    }

    @Test
    fun superusersAndAccountsWithoutThePermissionNeedNoServer() = runTest {
        val s = FakeServer()
        assertEquals(true, s.roles.knownAnswer(admin))
        assertEquals(false, s.roles.knownAnswer(reader))
        assertEquals(false, s.roles.knownAnswer(null))
        assertNull(s.roles.knownAnswer(editor))
        assertTrue(s.roles.canEditMetadata(admin, 2))
        assertFalse(s.roles.canEditMetadata(reader, 1))
        assertEquals(0, s.versionCalls + s.libraryCalls)
    }

    @Test
    fun onANewServerTheLibrarysRoleCounts() = runTest {
        val s = FakeServer()
        assertTrue(s.roles.canEditMetadata(editor, 1)) // editor
        assertFalse(s.roles.canEditMetadata(editor, 2)) // viewer
        assertTrue(s.roles.canEditMetadata(editor, 3)) // owner
        assertFalse(s.roles.canEditMetadata(editor, 9)) // not one of the account's
        assertFalse(s.roles.canEditMetadata(editor, null))
        assertEquals(1, s.versionCalls)
        assertEquals(1, s.libraryCalls)
    }

    @Test
    fun onAnOlderServerThePermissionIsEnough() = runTest {
        val s = FakeServer(version = "v3.3.0")
        assertTrue(s.roles.canEditMetadata(editor, 2))
        assertTrue(s.roles.canEditMetadata(editor, null))
        assertEquals(0, s.libraryCalls)
    }

    @Test
    fun theDrawersLibrariesAreUsedAndRolesFollowTheAccount() = runTest {
        val s = FakeServer()
        s.roles.noteLibraries(s.libraries)
        assertFalse(s.roles.canEditMetadata(editor, 2))
        assertEquals(0, s.libraryCalls)

        s.roles.noteLibraries(listOf(Library(id = 2, name = "Kids", accessLevel = "editor"))) // the admin changed it
        assertTrue(s.roles.canEditMetadata(editor, 2))

        s.account = "b" // another account (or server): asks again
        s.version = "v3.4.0"
        assertTrue(s.roles.canEditMetadata(editor, 9))
        assertEquals(2, s.versionCalls)
    }

    @Test
    fun aFailedLookupSaysNoAndIsNotKept() = runTest {
        val s = FakeServer()
        s.offline = true
        assertFalse(s.roles.canEditMetadata(editor, 1))
        s.offline = false
        assertTrue(s.roles.canEditMetadata(editor, 1))
        assertEquals(2, s.versionCalls)
    }
}
