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
    }
}

rootProject.name = "AiosPhonePreview"
include(":app")
include(":prodcheck")
include(":telecomsmoke")
include(":messagingcheck")
include(":emulatorcontrol")
include(":callcontextcheck")
include(":callservicecheck")
include(":callassistantsmoke")
include(":mediascancheck")
include(":modelservicecheck")
include(":modelbenchmarkcheck")
include(":runtimecommoncheck")
include(":runtimeprovidercheck")
include(":whisperpolicycheck")
