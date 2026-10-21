plugins {
    id("privatetracker.jvm.library")
}

// Keys and files of a server process on a host: the Linux server and the relay. An identity key with
// its self-signed certificate in a PKCS#12 file, files written atomically and owner-only, and the lock
// that keeps a second process off the same directory.
dependencies {
    api(project(":core:domain"))
    implementation(project(":core:protocol"))
}
