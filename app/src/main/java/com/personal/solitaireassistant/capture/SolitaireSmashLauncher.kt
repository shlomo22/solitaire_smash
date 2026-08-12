package com.personal.solitaireassistant.capture

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Resolves the installed Solitaire Smash package so Start can open it automatically.
 */
object SolitaireSmashLauncher {
    private val knownPackages = listOf(
        "com.funquest.solitaire.smash",
        "com.solitairesmash.gp",
        "solitaire.smash.cash.clash.casino",
        "com.solitairegame.smashgame"
    )

    fun findPackageName(context: Context): String? {
        val pm = context.packageManager
        knownPackages.firstOrNull { installed(pm, it) }?.let { return it }

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
                mainIntent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        return launchers.firstOrNull { info ->
            val label = info.loadLabel(pm)?.toString().orEmpty()
            label.contains("solitaire", ignoreCase = true) &&
                label.contains("smash", ignoreCase = true)
        }?.activityInfo?.packageName
    }

    fun launch(context: Context): Boolean {
        val packageName = findPackageName(context) ?: return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        context.startActivity(launchIntent)
        return true
    }

    private fun installed(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
