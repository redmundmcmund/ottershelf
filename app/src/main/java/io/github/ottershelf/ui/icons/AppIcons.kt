package io.github.ottershelf.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The few Lucide icons (lucide-static 1.46.0, ISC licence) the navigation shell needs, built in so
 * they never wait for assets/lucide.txt. Any other Lucide icon, by the PascalCase name the server
 * stores: [Lucide.get] or the [LucideIcon] composable.
 */
object AppIcons {
    val Home: ImageVector by lazy {
        lucide(
            "House",
            "M 15 21 v -8 a 1 1 0 0 0 -1 -1 h -4 a 1 1 0 0 0 -1 1 v 8 M 3 10 a 2 2 0 0 1 .709 -1.528 l 7 -6 a 2 2 0 0 1 2.582 0 l 7 6 A 2 2 0 0 1 21 10 v 9 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z",
        )
    }
    val Library: ImageVector by lazy {
        lucide(
            "LibraryBig",
            "M 4 3 h 6 a 1 1 0 0 1 1 1 v 16 a 1 1 0 0 1 -1 1 h -6 a 1 1 0 0 1 -1 -1 v -16 a 1 1 0 0 1 1 -1 Z M 7 3 v 18 M 20.4 18.9 c .2 .5 -.1 1.1 -.6 1.3 l -1.9 .7 c -.5 .2 -1.1 -.1 -1.3 -.6 L 11.1 5.1 c -.2 -.5 .1 -1.1 .6 -1.3 l 1.9 -.7 c .5 -.2 1.1 .1 1.3 .6 Z",
        )
    }
    val Downloads: ImageVector by lazy {
        lucide(
            "HardDriveDownload",
            "M 12 2 v 8 M 16 6 l -4 4 -4 -4 M 4 14 h 16 a 2 2 0 0 1 2 2 v 4 a 2 2 0 0 1 -2 2 h -16 a 2 2 0 0 1 -2 -2 v -4 a 2 2 0 0 1 2 -2 Z M 6 18 h .01 M 10 18 h .01",
        )
    }
    val Requests: ImageVector by lazy {
        lucide(
            "BookPlus",
            "M 12 7 v 6 M 4 19.5 v -15 A 2.5 2.5 0 0 1 6.5 2 H 19 a 1 1 0 0 1 1 1 v 18 a 1 1 0 0 1 -1 1 H 6.5 a 1 1 0 0 1 0 -5 H 20 M 9 10 h 6",
        )
    }
    val Settings: ImageVector by lazy {
        lucide(
            "Settings",
            "M 9.671 4.136 a 2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1 -2.33 4.033 2.34 2.34 0 0 0 -3.319 1.915 2.34 2.34 0 0 1 -4.659 0 2.34 2.34 0 0 0 -3.32 -1.915 2.34 2.34 0 0 1 -2.33 -4.033 2.34 2.34 0 0 0 0 -3.831 A 2.34 2.34 0 0 1 6.35 6.051 a 2.34 2.34 0 0 0 3.319 -1.915 M 9 12 a 3 3 0 1 0 6 0 a 3 3 0 1 0 -6 0 Z",
        )
    }
    val Back: ImageVector by lazy { lucide("ArrowLeft", "M 12 19 l -7 -7 7 -7 M 19 12 H 5", autoMirror = true) }
    val Close: ImageVector by lazy { lucide("X", "M 18 6 6 18 M 6 6 l 12 12") }
    val Orbit: ImageVector by lazy {
        lucide(
            "Orbit",
            "M 20.341 6.484 A 10 10 0 0 1 10.266 21.85 M 3.659 17.516 A 10 10 0 0 1 13.74 2.152 M 9 12 a 3 3 0 1 0 6 0 a 3 3 0 1 0 -6 0 Z M 17 5 a 2 2 0 1 0 4 0 a 2 2 0 1 0 -4 0 Z M 3 19 a 2 2 0 1 0 4 0 a 2 2 0 1 0 -4 0 Z",
        )
    }

    /** The toolbar's scan action and the Currently Reading card's "Scan a book" (feature.scan). */
    val ScanBarcode: ImageVector by lazy {
        lucide(
            "ScanBarcode",
            "M 3 7 V 5 a 2 2 0 0 1 2 -2 h 2 M 17 3 h 2 a 2 2 0 0 1 2 2 v 2 M 21 17 v 2 a 2 2 0 0 1 -2 2 h -2 M 7 21 H 5 a 2 2 0 0 1 -2 -2 v -2 M 8 7 v 10 M 12 7 v 10 M 17 7 v 10",
        )
    }

    // Built into Lucide (LucideBuiltIn.kt), so these never wait for lucide.txt either.
    val Menu: ImageVector by lazy { Lucide.get("Menu")!! }
    val Search: ImageVector by lazy { Lucide.get("Search")!! }
    val SignOut: ImageVector by lazy { Lucide.get("LogOut")!! }
    val Dashboard: ImageVector by lazy { Lucide.get("LayoutDashboard")!! }
    val Retry: ImageVector by lazy { Lucide.get("RefreshCw")!! }

    /**
     * A Lucide icon from its path data (lucide.txt's format: 24x24, stroked 2 wide with round caps
     * and joins). Tinted by `Icon` like any ImageVector.
     */
    fun lucide(name: String, pathData: String, autoMirror: Boolean = false): ImageVector =
        Lucide.build(name, pathData, autoMirror)
}
