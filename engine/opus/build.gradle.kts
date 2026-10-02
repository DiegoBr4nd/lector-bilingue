plugins {
    id("lectorbilingue.android.library")
}

android {
    namespace = "io.github.diegobr4nd.lectorbilingue.engine.opus"
}

dependencies {
    api(project(":engine:api"))
}
