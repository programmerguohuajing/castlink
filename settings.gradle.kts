// CastLink MVP v0.1
// 沙箱离线构建：gradle -PcastlinkLocalRepo=/home/hatch/toolchains/m2repo --offline
// 正常开发机：不传该参数，直接走 google()/mavenCentral()
pluginManagement {
    repositories {
        val lr = providers.gradleProperty("castlinkLocalRepo").getOrElse("")
        if (lr.isNotEmpty()) {
            maven { url = uri(lr) }
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val lr = providers.gradleProperty("castlinkLocalRepo").getOrElse("")
        if (lr.isNotEmpty()) {
            maven { url = uri(lr) }
        } else {
            google()
            mavenCentral()
        }
    }
}
rootProject.name = "CastLink"
include(":app")
