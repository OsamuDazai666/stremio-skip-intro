package io.github.osamudazai666.skipintro.patches

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    const val EXTENSION_CLASS = "Lio/github/osamudazai666/skipintro/SkipIntro;"

    val COMPATIBILITY_STREMIO = Compatibility(
        name = "Stremio",
        packageName = "com.stremio.one",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x8A5AAB,
        targets = listOf(
            // Developed and tested on the phone app 2.6.1.
            AppTarget(version = "2.6.1"),
            // Newer phone releases usually keep the same player code.
            AppTarget(version = null, isExperimental = true),
        ),
    )
}
