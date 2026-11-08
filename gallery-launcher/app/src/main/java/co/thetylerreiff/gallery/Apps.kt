package co.thetylerreiff.gallery

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings

class DockApp(val key: String, val label: String, val icon: Drawable, val banner: Drawable?, val intent: Intent)

/** Finds launchable apps and remembers the user's dock order and hidden apps. */
class Apps(private val context: Context) {
    private val prefs = context.getSharedPreferences("dock", Context.MODE_PRIVATE)

    var order: List<String>
        get() = prefs.getString("order", "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("order", value.joinToString(",")).apply()

    var hidden: Set<String>
        get() = prefs.getStringSet("hidden", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("hidden", value).apply()

    /** Background scene name, or "auto[:pack]" to rotate (see [Themes]). */
    var scene: String
        get() = prefs.getString("scene", "auto")!!
        set(value) = prefs.edit().putString("scene", value).apply()

    /** Hours between backgrounds while rotating. */
    var rotateHours: Int
        get() = prefs.getInt("rotateHours", 3)
        set(value) = prefs.edit().putInt("rotateHours", value).apply()

    fun settings(): DockApp {
        val pm = context.packageManager
        val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ri = pm.resolveActivity(intent, 0)
        val icon = context.getDrawable(R.drawable.ic_settings)!!
        return DockApp(ri?.activityInfo?.packageName ?: "settings", "Settings", icon, null, intent)
    }

    /** All launchable apps except this one and Settings, TV (leanback) entry points preferred. */
    fun installed(): List<DockApp> {
        val pm = context.packageManager
        val skip = setOf(context.packageName, settings().key)
        val found = LinkedHashMap<String, DockApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg in skip || pkg in found) continue
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                found[pkg] = DockApp(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), ri.activityInfo.loadBanner(pm), intent)
            }
        }
        return found.values.toList()
    }

    /** Visible apps in the user's order; apps never placed yet go at the end, alphabetically. */
    fun dock(): List<DockApp> {
        val all = installed().associateBy { it.key }
        val hide = hidden
        val placed = order.mapNotNull { all[it] }
        val rest = all.values.filter { it.key !in order }.sortedBy { it.label.lowercase() }
        return (placed + rest).filter { it.key !in hide }
    }

    fun move(key: String, by: Int) {
        val keys = dock().map { it.key }.toMutableList()
        val from = keys.indexOf(key)
        val to = (from + by).coerceIn(0, keys.lastIndex)
        if (from < 0 || from == to) return
        keys.add(to, keys.removeAt(from))
        order = keys
    }

    fun label(key: String): String = installed().firstOrNull { it.key == key }?.label ?: key
}
