pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "No5Helm"
include(":ui-state")
project(":ui-state").projectDir = file("../ui-state")
include(":app", ":ui-shell", ":chart-api", ":chart-engine", ":helm-model", ":helm-client")
include(":native-screens")
project(":native-screens").projectDir = file("preparation/native-five-screens/ui")
include(":chart-library")

include(":control-ring")
project(":control-ring").projectDir = file("../control-ring")
