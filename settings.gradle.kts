pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (the quotes' page reading) is published only on JitPack. JitPack serves
        // that one group and nothing else, and that group comes from nowhere else: no other
        // dependency can resolve from it.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") { name = "JitPack" } }
            filter { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}
rootProject.name = "ottershelf"
include(":app")
