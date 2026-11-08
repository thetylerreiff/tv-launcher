package co.thetylerreiff.gallery

import co.thetylerreiff.gallery.SceneView.Scene
import java.util.Calendar
import kotlin.random.Random

/**
 * A set of backgrounds shown as one row in the theme picker; [rotation] is what Rotate cycles through,
 * in order, or in a fresh random order each round when [shuffle] is set.
 */
class ThemePack(val id: String, val title: String, val scenes: List<Scene>, val rotation: List<Scene>, val shuffle: Boolean = false)

private val gallery = listOf(Scene.RIDGE, Scene.DUNES, Scene.SEA, Scene.PLANET, Scene.SKY)

private val metal = listOf(Scene.CHROME, Scene.GOLD, Scene.ALLOY)
private val signal = listOf(Scene.SIGNAL, Scene.BEACON, Scene.ARRAY, Scene.STRIPE)
private val survey = listOf(Scene.SURVEY, Scene.RADAR, Scene.GLOBE)
private val neon = listOf(Scene.NEON, Scene.HIGHWAY, Scene.SIGN, Scene.WINDOW)

object Themes {
    val packs = listOf(
        ThemePack("gallery", "Gallery", gallery, gallery - Scene.PLANET),
        ThemePack("signal", "Signal", signal, signal),
        ThemePack("survey", "Survey", survey, survey),
        ThemePack("neon", "Neon", neon, neon),
        ThemePack("metal", "Metal", metal, metal),
    )

    /** Shuffle: every pack's rotation together, in random order. Shown as its own row above the packs. */
    val all = ThemePack("all", "All", emptyList(), packs.flatMap { it.rotation }, shuffle = true)

    /** Rotation intervals offered in the picker, in hours. */
    val intervals = listOf(1, 3, 6, 24)

    fun intervalLabel(hours: Int) = when (hours) {
        1 -> "Every hour"
        24 -> "Once a day"
        else -> "Every $hours hours"
    }

    fun description(scene: Scene) = when (scene) {
        Scene.RIDGE -> "Layered mountains under a moving sun and moon"
        Scene.DUNES -> "Soft dunes rolling to the horizon"
        Scene.SEA -> "A calm horizon with light on the water"
        Scene.PLANET -> "The edge of a world catching the light"
        Scene.SKY -> "Nothing but sky and a band of mist"
        Scene.SIGNAL -> "A halftone planet in acid green"
        Scene.BEACON -> "A mast sending rings out into the dark"
        Scene.ARRAY -> "A dot-matrix panel rippling with two waves"
        Scene.STRIPE -> "A band of marching stripes across a thin ring"
        Scene.SURVEY -> "Contour lines over invented terrain, swept slowly"
        Scene.RADAR -> "A radar scope; contacts flare as the sweep passes"
        Scene.GLOBE -> "A turning wireframe globe with a satellite in orbit"
        Scene.NEON -> "A hazy skyline with lit windows and fine rain"
        Scene.HIGHWAY -> "An overpass streaming with traffic above a wet street"
        Scene.SIGN -> "A neon sign on a wet wall, mirrored in the street"
        Scene.WINDOW -> "City lights out of focus behind rain on the glass"
        Scene.CHROME -> "Liquid chrome turning in a studio light"
        Scene.GOLD -> "Molten gold in a dark room"
        Scene.ALLOY -> "Drops of chrome and gold drifting together"
    }

    /** The saved choice is a scene name, or "auto:<pack>" to rotate that pack ("auto" alone means the first pack, "auto:all" shuffles everything). */
    fun rotatingPack(choice: String): ThemePack? =
        if (choice == "auto" || choice.startsWith("auto:")) (packs + all).firstOrNull { "auto:${it.id}" == choice } ?: packs.first() else null

    fun current(choice: String, rotateHours: Int, now: Calendar): Scene =
        Scene.entries.firstOrNull { it.name == choice } ?: rotating(rotatingPack(choice) ?: packs.first(), rotateHours, now)

    /** Which scene of [pack] a rotation every [hours] shows at [now]. */
    fun rotating(pack: ThemePack, hours: Int, now: Calendar): Scene {
        val slot = (now.get(Calendar.DAY_OF_YEAR) * 24 + now.get(Calendar.HOUR_OF_DAY)) / hours.coerceAtLeast(1)
        val n = pack.rotation.size
        if (!pack.shuffle) return pack.rotation[slot % n]
        // Each round shows every scene once, in an order seeded by the round, so it's random but stable.
        return pack.rotation.shuffled(Random(slot / n))[slot % n]
    }
}
