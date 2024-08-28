plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
  api(projects.leakcanary.leakcanaryCore)
  api(projects.shark.shark)
  api(libs.junit)

  testImplementation(libs.assertjCore)
}
