package top.iwesley.lyn.music.core.model

interface EqualizerPlatformService {
    val isSupported: Boolean

    fun openEqualizer()
}

object UnsupportedEqualizerPlatformService : EqualizerPlatformService {
    override val isSupported: Boolean = false

    override fun openEqualizer() = Unit
}

fun formatEqualizerFrequencyLabel(centerFrequencyHz: Int): String {
    val frequency = centerFrequencyHz.coerceAtLeast(0)
    if (frequency < 1_000) return frequency.toString()
    val wholeKiloHertz = frequency / 1_000
    return if (frequency % 1_000 == 0) {
        "${wholeKiloHertz}k"
    } else {
        val firstDecimal = ((frequency % 1_000) / 100).coerceIn(0, 9)
        "${wholeKiloHertz}.${firstDecimal}k"
    }
}

fun equalizerMillibelsToDecibels(levelMb: Int): Float {
    return levelMb / 100f
}

fun clampEqualizerLevel(levelMb: Int, minLevelMb: Int, maxLevelMb: Int): Int {
    val min = minOf(minLevelMb, maxLevelMb)
    val max = maxOf(minLevelMb, maxLevelMb)
    return levelMb.coerceIn(min, max)
}
