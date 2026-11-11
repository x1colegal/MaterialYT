package com.x1colegal.materialyt.sabrmodern

/**
 * Pure detection of SABR limited playback, shared by [SabrSession] and [SabrVideoSession].
 * A session whose streaming PoToken no longer satisfies protection is served a
 * small free window, then the server delivers ONLY `STREAM_PROTECTION_STATUS >= 2` with no media
 * (proven live: `tests/probe-mweb-sabr.mjs`). Counting consecutive no-progress responses under active
 * protection lets a session bail fast with a clear reason instead of grinding to the dry cap, so the
 * roster/stall fallback moves on to a client that can attest. JVM-unit-tested ([SabrProtectionTest]).
 */
internal object SabrProtection {
    /** Names come from the bundled StreamProtectionStatus protobuf schema. */
    const val ATTESTATION_PENDING = 2L
    const val ATTESTATION_REQUIRED = 3L
    const val REFRESH_AFTER_STALLS = 2
    const val REFRESH_COOLDOWN_MS = 5_000L
    /** Consecutive no-media responses under active protection before it's an attestation cap. */
    const val STALL_LIMIT = 6

    /** Running count of consecutive no-progress responses under active attestation (0 = not capped). */
    fun nextStalls(protStatus: Long, madeProgress: Boolean, prev: Int): Int =
        if (protStatus >= ATTESTATION_PENDING && !madeProgress) prev + 1 else 0

    fun capped(stalls: Int): Boolean = stalls >= STALL_LIMIT
}
