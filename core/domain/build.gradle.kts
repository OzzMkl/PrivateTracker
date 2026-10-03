plugins {
    id("privatetracker.jvm.library")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:common"))

    // Fakes and builders shared with the tests of other modules (server:api, data layer).
    testFixturesApi(project(":core:common"))
}
