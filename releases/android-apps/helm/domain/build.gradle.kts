plugins { id("org.jetbrains.kotlin.jvm"); `java-test-fixtures` }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}

kotlin.sourceSets.getByName("main").kotlin.srcDir("../../header-contract/src/main/kotlin")
kotlin.sourceSets.getByName("test").kotlin.srcDir("../../header-contract/src/test/kotlin")
