package io.github.judegibatron.phoneagent.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.root.RootShell
import kotlin.math.max

/** Installed-app lookup, label resolution and launching (with a root fallback). */
object Apps {

    data class Entry(val label: String, val packageName: String)

    private var cache: Pair<Long, List<Entry>>? = null

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    @Synchronized
    fun launchable(context: Context): List<Entry> {
        cache?.let { (stamp, list) -> if (SystemClock.elapsedRealtime() - stamp < 60_000) return list }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { Entry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        cache = SystemClock.elapsedRealtime() to list
        return list
    }

    /** Candidate apps for a spoken name, best first, with scores in 0..1. */
    fun match(context: Context, query: String): List<Pair<Entry, Double>> =
        launchable(context)
            .map { entry ->
                val byLabel = Fuzzy.score(query, entry.label)
                val byPackage = Fuzzy.score(query, entry.packageName.substringAfterLast('.')) * 0.9
                entry to max(byLabel, byPackage)
            }
            .filter { it.second >= 0.55 }
            .sortedByDescending { it.second }

    fun launch(context: Context, root: RootShell, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return rootLaunch(root, packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AgentLog.w("Apps", "startActivity($packageName) failed: ${e.message}")
            rootLaunch(root, packageName)
        }
    }

    private fun rootLaunch(root: RootShell, packageName: String): Boolean =
        root.isAvailable() && root.run("monkey -p $packageName -c android.intent.category.LAUNCHER 1", 10_000).ok
}
