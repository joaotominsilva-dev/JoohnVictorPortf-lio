package com.joohn.baixavideos.util

import java.util.concurrent.atomic.AtomicInteger

/** Quantas telas do app estão visíveis (atualizado em onStart/onStop das activities). */
object AppVisibility {
    private val started = AtomicInteger(0)
    val isVisible: Boolean get() = started.get() > 0

    fun onStart() {
        started.incrementAndGet()
    }

    fun onStop() {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
