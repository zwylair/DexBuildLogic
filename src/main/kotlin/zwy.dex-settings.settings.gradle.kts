val host = HostSource(providers) { settingsDir.toPath().resolve(it).toFile() }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // the hook library is published only to the maven mirror inside the host checkout
        maven {
            url = host.mavenDir.toURI()
            content { host.libGroups.forEach(::includeGroupAndSubgroups) }
        }
    }
}