package com.meshcipher.data.auth

import com.meshcipher.data.auth.CertificatePins
import com.meshcipher.data.local.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val appPreferences: AppPreferences
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val currentBaseUrl = runBlocking { appPreferences.relayServerUrl.first() }

        val newBaseUrl = currentBaseUrl.toHttpUrlOrNull() ?: return chain.proceed(original)

        // GAP-03 / R-04: Reject requests to hosts that are not in the certificate pin list.
        // OkHttp's CertificatePinner only applies pins for the configured host; routing to
        // a different host would bypass pinning entirely.
        if (newBaseUrl.host != CertificatePins.RELAY_HOST) {
            throw IOException(
                "Relay host '${newBaseUrl.host}' is not pinned. " +
                "Only '${CertificatePins.RELAY_HOST}' is allowed. " +
                "Update relay.host in local.properties if the relay hostname has changed."
            )
        }
        val originalUrl = original.url

        val newUrl = originalUrl.newBuilder()
            .scheme(newBaseUrl.scheme)
            .host(newBaseUrl.host)
            .port(newBaseUrl.port)
            .build()

        val newRequest = original.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}
