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

rootProject.name = "No5Helm"
include(":app", ":domain")
include(":ui-state")
project(":ui-state").projectDir = file("../ui-state")

include(":control-ring")
project(":control-ring").projectDir = file("../control-ring")

if(providers.gradleProperty("no5SonarBench").orNull == "true")include(":sonar-macrobenchmark")
