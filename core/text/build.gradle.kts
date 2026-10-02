plugins {
    id("lectorbilingue.jvm.library")
}

tasks.withType<Test>().configureEach {
    systemProperty("bench.dir", rootProject.file("bench").absolutePath)
    inputs.dir(rootProject.file("bench"))
}
