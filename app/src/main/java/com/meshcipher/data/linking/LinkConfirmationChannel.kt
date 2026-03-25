package com.meshcipher.data.linking

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcast channel for device-linking confirmation events (GAP-06 / R-06).
 *
 * [ReceiveMessageUseCase] emits events here when content_type=19 (confirmed) or
 * content_type=20 (denied) arrive from the desktop. [DeviceLinkApprovalViewModel]
 * collects from this channel while awaiting the desktop's decision.
 */
@Singleton
class LinkConfirmationChannel @Inject constructor() {

    sealed class Event {
        /** Desktop user confirmed the link for [deviceId]. */
        data class Confirmed(val deviceId: String) : Event()
        /** Desktop user denied the link for [deviceId]. */
        data class Denied(val deviceId: String) : Event()
    }

    // replay = 1 ensures the event is not lost if DeviceLinkApprovalViewModel hasn't yet
    // started collecting when ReceiveMessageUseCase emits the confirmation. The deviceId
    // filter in the ViewModel ensures replayed events from prior sessions are ignored.
    private val _events = MutableSharedFlow<Event>(replay = 1, extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emit(event: Event) {
        _events.tryEmit(event)
    }
}
