plugins { kotlin("jvm") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-cio:3.3.2")
    implementation("io.ktor:ktor-server-auth:3.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("io.ktor:ktor-server-test-host:3.3.2")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped") } }
