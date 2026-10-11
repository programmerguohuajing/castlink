/*
 * Ported from PhairPlay (https://github.com/mazer666/PhairPlay, Apache-2.0);
 * package renamed to com.castlink.receiver.airplay.*. See THIRD-PARTY-NOTICES.
 */
package com.castlink.receiver.airplay

/**
 * ProtocolState — Represents the state of a single receiver protocol (AirPlay / Miracast / Cast).
 *
 * WHY: Each protocol has its own independent lifecycle. This enum lets the UI
 * show a fine-grained status per protocol card without conflating them.
 *
 * HOW: Each receiver component (AirPlayReceiver, MiracastReceiver, CastReceiver)
 * emits [ProtocolState] changes that are aggregated by [PhairPlayService].
 */
enum class ProtocolState {
    /** Protocol is disabled in Settings. */
    DISABLED,

    /** Protocol is enabled and advertising — waiting for a sender. */
    ADVERTISING,

    /** A sender is actively connected and streaming. */
    CONNECTED,

    /** The protocol encountered an error (e.g., port already in use). */
    ERROR
}
