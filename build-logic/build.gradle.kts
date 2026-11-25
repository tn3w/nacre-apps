plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.android.gradle)
    implementation(libs.compose.gradle)
}

gradlePlugin {
    plugins {
        register("app") {
            id = "nacre.app"
            implementationClass = "NacreAppPlugin"
        }
        register("library") {
            id = "nacre.library"
            implementationClass = "NacreLibraryPlugin"
        }
    }
}
