package dev.cascam.camera

enum class ThermalPressure { NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL }

/** Mantém a escolha do usuário como base e só reduz a frequência de fotos sob aquecimento. */
object ThermalPhotoPolicy {
    fun minimumIntervalMillis(pressure: ThermalPressure): Long = when (pressure) {
        ThermalPressure.NORMAL, ThermalPressure.LIGHT -> 0L
        ThermalPressure.MODERATE -> 2_000L
        ThermalPressure.SEVERE -> 5_000L
        ThermalPressure.CRITICAL -> 10_000L
    }

    fun effectiveIntervalMillis(baseIntervalMillis: Long, pressure: ThermalPressure): Long =
        maxOf(baseIntervalMillis, minimumIntervalMillis(pressure))
}
