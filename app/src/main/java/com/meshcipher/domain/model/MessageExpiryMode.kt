package com.meshcipher.domain.model

enum class MessageExpiryMode(val displayName: String, val durationMs: Long) {
    NEVER("Never", 0),
    ON_APP_CLOSE("On App Close", -1),
    FIVE_MINUTES("5 Minutes", 5 * 60 * 1000L),
    ONE_HOUR("1 Hour", 60 * 60 * 1000L),
    TWENTY_FOUR_HOURS("24 Hours", 24 * 60 * 60 * 1000L),
    SEVEN_DAYS("7 Days", 7 * 24 * 60 * 60 * 1000L),
    THIRTY_DAYS("30 Days", 30L * 24 * 60 * 60 * 1000L);

    companion object {
        fun fromName(name: String?): MessageExpiryMode? {
            if (name == null) return null
            return entries.find { it.name == name }
        }

        fun fromNameOrDefault(name: String?): MessageExpiryMode {
            return fromName(name) ?: NEVER
        }
    }
}
