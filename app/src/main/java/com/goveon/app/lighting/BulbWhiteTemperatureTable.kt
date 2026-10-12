package com.goveon.app.lighting

/** Exact effective channel values from the shipped bulb-v2 white-mode lookup.
 * The original insertion-ordered map overwrites three duplicate RGB keys.
 * These are its first matching effective values, not a generic Kelvin/RGB formula.
 * Evidence: ev_24e2b49a81023322cfbe45b63000eca5304c9fc596c5032f7e990f2b770924cc. */
internal object BulbWhiteTemperatureTable {
    val values: List<Int> = (1800..9000 step 100).toList()
    private val rgbByStep = intArrayOf(
        0xFF8200, 0xFF8700, 0xFF8D0B, 0xFF921D, 0xFF9829, 0xFF9D33,
        0xFFA23C, 0xFFA645, 0xFFAA4D, 0xFFAE54, 0xFFB25B, 0xFFB662,
        0xFFB969, 0xFFBD6F, 0xFFC076, 0xFFC37C, 0xFFC682, 0xFFC987,
        0xFFCB8D, 0xFFCE92, 0xFFD097, 0xFFD39C, 0xFFD5A1, 0xFFD7A6,
        0xFFD9AB, 0xFFDBAF, 0xFFDDB4, 0xFFDFB8, 0xFFE1BC, 0xFFE2C0,
        0xFFE4C4, 0xFFE5C8, 0xFFE7CC, 0xFFE8D0, 0xFFEAD3, 0xFFEBD7,
        0xFFEDDA, 0xFFEEDE, 0xFFEFE1, 0xFFF0E4, 0xFFF1E7, 0xFFF3EA,
        0xFFF4ED, 0xFFF5F0, 0xFFF6F3, 0xFFF7F7, 0xFFF8F8, 0xFFF9FB,
        0xFFF9FD, 0xFEFAFF, 0xFCF8FF, 0xFAF7FF, 0xF7F5FF, 0xF5F4FF,
        0xF3F3FF, 0xF1F1FF, 0xEFF0FF, 0xEEEFFF, 0xECEEFF, 0xEAEDFF,
        0xE9ECFF, 0xE7EAFF, 0xE5E9FF, 0xE4E9FF, 0xE3E8FF, 0xE1E7FF,
        0xE0E6FF, 0xDFE5FF, 0xDDE4FF, 0xDCE3FF, 0xDBE2FF, 0xDAE2FF,
        0xD9E1FF,
    )
    fun rgb(kelvin: Int): Int? = if (kelvin in 1800..9000 && (kelvin - 1800) % 100 == 0)
        rgbByStep[(kelvin - 1800) / 100] else null
}
