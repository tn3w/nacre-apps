plugins {
    id("nacre.library")
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    api(libs.activity.compose)
    debugApi(libs.compose.ui.tooling)
}
