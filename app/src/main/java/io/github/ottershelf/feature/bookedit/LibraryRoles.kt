package io.github.ottershelf.feature.bookedit

import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.Library
import kotlinx.coroutines.CancellationException

/**
 * Whether the account may edit a book's details, as BookOrbit decides it. Up to 3.4 the
 * `library_edit_metadata` permission was enough; since 3.5 every metadata write also needs the
 * editor or owner role on the book's library (the server's `verifyBookAccess(..., 'editor')`, the
 * web's `hasLibraryPermission(..., 'editor')`), and roles granted before the upgrade are viewer.
 * So the rule follows the server's version (`app-info`), and the pencil appears exactly when a save
 * would be accepted, on either side of an upgrade.
 *
 * Superusers and accounts without the permission are answered at once ([knownAnswer]); only the
 * others ask the server, once per account and process: the version, and the libraries' roles
 * (which the drawer's load already brings, [noteLibraries]). A failed lookup isn't kept, so the
 * next book asks again.
 */
class LibraryRoles(
    private val accountKey: () -> String,
    private val serverVersion: suspend () -> String?,
    private val libraries: suspend () -> List<Library>,
) {
    private data class Known(val account: String, val enforced: Boolean? = null, val levels: Map<Long, String?>? = null)

    @Volatile
    private var known = Known("")

    private fun current(): Known {
        val account = accountKey()
        return known.takeIf { it.account == account } ?: Known(account).also { known = it }
    }

    /** The roles in [list], fresh from the server (the drawer's load). */
    fun noteLibraries(list: List<Library>) {
        known = current().copy(levels = list.associate { it.id to it.accessLevel })
    }

    /** The answer when it needs no server: false without the permission, true for superusers; else null. */
    fun knownAnswer(user: AuthUser?): Boolean? = when {
        user?.can(EDIT_METADATA_PERMISSION) != true -> false
        user.isSuperuser -> true
        else -> null
    }

    /** Whether [user] may edit the details of a book in library [libraryId]; false if the server can't be asked. */
    suspend fun canEditMetadata(user: AuthUser?, libraryId: Long?): Boolean {
        knownAnswer(user)?.let { return it }
        return try {
            val enforced = current().enforced ?: enforcesLibraryRoles(serverVersion()).also { v -> known = current().copy(enforced = v) }
            if (!enforced) return true
            val levels = current().levels ?: libraries().associate { it.id to it.accessLevel }.also { l -> known = current().copy(levels = l) }
            libraryId != null && levels[libraryId] in EDITING_ROLES
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val EDITING_ROLES = setOf("editor", "owner")
        private val RELEASE = Regex("""^v?(\d+)\.(\d+)""")

        /**
         * Whether a server reporting [version] checks library roles for metadata writes: 3.5 and
         * later. A version that isn't a release (`Local build`) is taken as the newest.
         */
        internal fun enforcesLibraryRoles(version: String?): Boolean {
            val (major, minor) = RELEASE.find(version?.trim().orEmpty())?.destructured ?: return true
            return major.toInt() > 3 || (major.toInt() == 3 && minor.toInt() >= 5)
        }
    }
}
