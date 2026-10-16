plugins {
    id("privatetracker.jvm.library")
}

// The TLS host on Netty that both the phone and the Linux server put in front of server:api.
dependencies {
    api(libs.ktor.server.netty) {
        // Only desktop binaries for HTTP/3; the QUIC classes stay in netty-codec-classes-quic.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
}
