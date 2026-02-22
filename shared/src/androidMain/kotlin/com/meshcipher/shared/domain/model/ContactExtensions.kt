package com.meshcipher.shared.domain.model

import org.signal.libsignal.protocol.SignalProtocolAddress

/**
 * Android-only extension: derive the SignalProtocolAddress from the shared Contact.
 * Device ID is always 1 for mesh contacts.
 */
val Contact.signalProtocolAddress: SignalProtocolAddress
    get() = SignalProtocolAddress(id, 1)
