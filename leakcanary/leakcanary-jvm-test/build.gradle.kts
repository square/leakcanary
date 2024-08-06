plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
  api(projects.leakcanary.leakcanaryTestCore)
  api(projects.shark.shark)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(projects.shark.sharkHprofTest)
}
