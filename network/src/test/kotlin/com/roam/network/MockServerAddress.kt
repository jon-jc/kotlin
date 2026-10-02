package com.roam.network

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer

// Windows can reverse-resolve a loopback address to the machine's non-loopback hostname.
internal fun MockWebServer.loopbackUrl(path: String): HttpUrl =
    url(path).newBuilder().host("127.0.0.1").build()
