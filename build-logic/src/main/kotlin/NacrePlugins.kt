import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

private const val ANDROID_SDK = 37
private const val DOMAIN = "org.nacre"

class NacreAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.application")
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        project.extensions.configure<ApplicationExtension> {
            namespace = "$DOMAIN.${project.name}"
            compileSdk { version = release(ANDROID_SDK) { minorApiLevel = 1 } }
            defaultConfig {
                applicationId = namespace
                minSdk = ANDROID_SDK
                targetSdk = ANDROID_SDK
                versionCode = 1
                versionName = "1.0"
            }
            buildFeatures.compose = true
        }

        project.dependencies { "implementation"(project(":core")) }
    }
}

class NacreLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.library")
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        project.extensions.configure<LibraryExtension> {
            namespace = "$DOMAIN.${project.name}"
            compileSdk { version = release(ANDROID_SDK) { minorApiLevel = 1 } }
            defaultConfig.minSdk = ANDROID_SDK
            buildFeatures.compose = true
        }
    }
}
