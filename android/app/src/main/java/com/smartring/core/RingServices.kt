package com.smartring.core

/**
 * Where the UI gets its RingController / GestureStudio. RingApplication wires the real
 * implementations in at app start (RealRingController over RingLink, the real GestureStudio);
 * until then — and in previews/tests — the fakes are used.
 */
object RingServices {
    @Volatile var ring: RingController = FakeRingController()
    @Volatile var studio: GestureStudio = FakeGestureStudio()
}
