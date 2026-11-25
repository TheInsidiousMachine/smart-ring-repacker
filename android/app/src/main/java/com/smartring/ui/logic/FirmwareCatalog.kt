package com.smartring.ui.logic

import com.smartring.core.FirmwareImage

/**
 * How the firmware picker presents the images the controller already allows.
 * Custom builds are newest first. Stock is always last, under one restore name.
 */
object FirmwareCatalog {
    private val buildNumber = Regex("""build\s+(\d+)""", RegexOption.IGNORE_CASE)

    fun isStock(image: FirmwareImage): Boolean =
        image.label.contains("stock", ignoreCase = true) ||
            image.fileName.contains("stock", ignoreCase = true)

    fun buildNumber(image: FirmwareImage): Int? =
        buildNumber.find(image.label)?.groupValues?.get(1)?.toIntOrNull()

    fun displayName(image: FirmwareImage): String =
        if (isStock(image)) "Stock V2.1.4 (restore)" else image.label

    /** Dropdown row: the name the user sees, plus the first 8 hash characters. */
    fun menuLabel(image: FirmwareImage): String =
        "${displayName(image)} · ${image.sha256.take(8)}"

    fun ordered(images: List<FirmwareImage>): List<FirmwareImage> =
        images.sortedWith(
            compareBy<FirmwareImage> { isStock(it) }
                .thenByDescending { buildNumber(it) ?: Int.MIN_VALUE }
                .thenByDescending { it.label },
        )
}
