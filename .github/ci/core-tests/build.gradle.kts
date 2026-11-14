plugins { kotlin("jvm") version "2.1.0" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
val appRoot = file("../../../src/android/app/src")
sourceSets {
    main {
        kotlin.srcDir(File(appRoot, "main/java"))
        kotlin.include("com/openminis/app/mikova/voice/PcmPlayback.kt")
        kotlin.include("com/openminis/app/mikova/selfhost/EndpointPolicy.kt")
    }
    test {
        kotlin.srcDir(File(appRoot, "test/java"))
        kotlin.include("com/openminis/app/mikova/voice/PcmPlaybackTest.kt")
        kotlin.include("com/openminis/app/mikova/selfhost/SelfHostProbeTest.kt")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    compileOnly("com.google.android:android:4.1.1.4")
    testRuntimeOnly("com.google.android:android:4.1.1.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test {
    useJUnit()
    maxHeapSize = "768m"
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
