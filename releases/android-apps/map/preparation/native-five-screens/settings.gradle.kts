pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "No5IsolatedScreenPreparation"
include(":ui", ":harness")
include(":ui-state")
project(":ui-state").projectDir = file("../../../ui-state")
