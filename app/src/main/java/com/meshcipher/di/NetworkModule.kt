package com.meshcipher.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.meshcipher.BuildConfig
import com.meshcipher.data.auth.AuthInterceptor
import com.meshcipher.data.auth.DynamicBaseUrlInterceptor
import com.meshcipher.data.remote.api.AuthApiService
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.auth.CertificatePins
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Placeholder base URL; the real URL is injected at request time by DynamicBaseUrlInterceptor
    private const val BASE_URL = "http://localhost/"

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder().create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor
    ): OkHttpClient {
        // GAP-03 / R-04: Pin to the relay leaf certificate SPKI hash to prevent MITM via
        // rogue CAs or corporate proxies. Both pins must be updated whenever the relay TLS
        // certificate is rotated, before shipping the new build. See CertificatePins.kt.
        val certificatePinner = CertificatePinner.Builder()
            .add(CertificatePins.RELAY_HOST, CertificatePins.RELAY_CERT_PIN_PRIMARY)
            .add(CertificatePins.RELAY_HOST, CertificatePins.RELAY_CERT_PIN_BACKUP)
            .build()

        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .certificatePinner(certificatePinner)
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(authInterceptor)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideRelayApiService(retrofit: Retrofit): RelayApiService {
        return retrofit.create(RelayApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideAuthApiService(retrofit: Retrofit): AuthApiService {
        return retrofit.create(AuthApiService::class.java)
    }
}
