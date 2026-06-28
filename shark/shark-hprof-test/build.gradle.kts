plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.junit)
    implementation(libs.okio2)

    implementation(projects.shark.sharkHprof)
}
