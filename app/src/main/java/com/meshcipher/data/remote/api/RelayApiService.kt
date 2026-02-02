package com.meshcipher.data.remote.api

import com.meshcipher.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface RelayApiService {

    @POST("api/v1/relay/message")
    suspend fun sendMessage(
        @Body request: RelayMessageRequest
    ): Response<RelayMessageResponse>

    @GET("api/v1/relay/messages/{recipientId}")
    suspend fun getMessages(
        @Path("recipientId") recipientId: String
    ): Response<QueuedMessagesResponse>

    @POST("api/v1/relay/messages/{recipientId}/ack")
    suspend fun acknowledgeMessages(
        @Path("recipientId") recipientId: String,
        @Body request: AcknowledgeRequest
    ): Response<AcknowledgeResponse>

    @POST("api/v1/register")
    suspend fun registerDevice(
        @Body request: RegisterDeviceRequest
    ): Response<RegisterDeviceResponse>

    @GET("api/v1/health")
    suspend fun healthCheck(): Response<HealthResponse>
}
