package com.radio.app

import android.content.Intent
import android.content.pm.PackageManager

/**
 * Android gives normal apps NO public API to the phone's FM chip (the tuner is a
 * system/vendor-only service). The best a normal APK can do is detect and open the
 * phone's own built-in FM app, if the manufacturer installed one.
 */
object HardwareFm {
    private val candidates = listOf(
        "com.mediatek.fmradio", "com.android.fmradio", "com.caf.fmradio",
        "com.sprd.fmradio", "com.transsion.fmradio", "com.sec.android.app.fm",
        "com.motorola.fmplayer", "com.android.fm"
    )

    fun launchIntent(pm: PackageManager): Intent? =
        candidates.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
}
