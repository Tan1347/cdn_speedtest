package org.tan.cdntest

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClient {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
