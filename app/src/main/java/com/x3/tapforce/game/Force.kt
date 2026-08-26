package com.x3.tapforce.game

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Field is normalised: x -1..1 across, y 0 at the ship and 1 at the top. */

const val POD_FRONT = 0
const val POD_REAR = 1
const val POD_AWAY = 2

const val E_DRONE = 0
const val E_WEAVER = 1
const val E_TURRET = 2
const val E_BOSS = 3

class Foe(
    @JvmField var kind: Int,
    @JvmField var x: Float, @JvmField var y: Float,
    @JvmField var hp: Int,
    @JvmField var t: Float = 0f,
    @JvmField var alive: Boolean = true,
    @JvmField var vx: Float = 0f, @JvmField var vy: Float = -0.16f,
    /** Boss only: the weak point rides here, offset from the hull. */
    @JvmField var coreX: Float = 0f
)

class Bolt(
    @JvmField var x: Float, @JvmField var y: Float,
    @JvmField var vx: Float, @JvmField var vy: Float,
    @JvmField var mine: Boolean,
    @JvmField var big: Boolean = false,
    @JvmField var live: Boolean = true
)

sealed class Ev {
    class Hit(val x: Float, val y: Float, val kind: Int, val killed: Boolean, val points: Int) : Ev()
    class Absorbed(val x: Float, val y: Float) : Ev()
    object PodMoved : Ev()
    object Beam : Ev()
    object Hurt : Ev()
    object BossDown : Ev()
    object StageDone : Ev()
    object Over : Ev()
}

/**
 * ============================================================================
 *  A vertical R-Type.
 * ============================================================================
 *
 * R-Type (1987) is remembered for three things, and none of them is shooting.
 *
 *  1. THE FORCE. An indestructible pod that docks to the front or the back of
 *     your ship, eats anything that hits it, and fires on its own. It turns the
 *     game from "dodge everything" into "decide which side needs a shield" —
 *     the pod is a movable piece of armour that also happens to be a gun, and
 *     choosing where it goes IS the game.
 *  2. THE CHARGE BEAM. Hold fire, release a wave that punches through a line of
 *     enemies. It makes waiting an active choice: you give up rate of fire for
 *     a moment in exchange for something that clears a lane.
 *  3. BOSSES WITH A WEAK POINT. Not a hit-point sponge — a specific place you
 *     have to get the shot into, usually while the safe ground keeps moving.
 *
 * All three survive the move to a vertical field and a one-axis controller,
 * which is why this is the shooter worth copying rather than another Invaders.
 *
 * No Android, no GL: rules only.
 */
class Force(seed: Long = 0x464F5243L) {

    private val rnd = Random(seed)

    @JvmField val foes = ArrayList<Foe>(48)
    @JvmField val bolts = ArrayList<Bolt>(64)

    var shipX = 0f; private set
    var podMode = POD_FRONT; private set
    var podX = 0f; private set
    var podY = 0f; private set

    var score = 0; private set
    var lives = 3; private set
    var stage = 1; private set
    var over = false; private set
    var stageDone = false; private set

    /** 0..1 while the player holds; a full charge fires the wave beam. */
    var charge = 0f; private set
    var bossHp = 0; private set
    var bossMax = 0; private set

    private var spawnTimer = 0f
    private var spawnEvery = 1.2f
    private var autoTimer = 0f
    private var podFireTimer = 0f
    private var mercy = 0f
    private var spawned = 0
    private var quota = 0
    private var bossOut = false
    private var scroll = 0f

    private val evs = ArrayList<Ev>(16)

    fun scrollPhase() = scroll

    fun startStage(s: Stage, fresh: Boolean) {
        if (fresh) { score = 0; lives = 3; over = false }
        stage = s.n
        spawnEvery = s.spawnEvery
        quota = s.quota
        spawned = 0
        bossOut = false
        bossHp = 0; bossMax = 0
        stageDone = false
        foes.clear(); bolts.clear()
        podMode = POD_FRONT
        charge = 0f
        mercy = 0f
        spawnTimer = 0f
    }

    fun moveTo(x: Float) { shipX = x.coerceIn(-0.9f, 0.9f) }

    /**
     * THE ONE BUTTON THAT MATTERS. Front for a shield against what is coming,
     * rear to cover the drift behind you, away to send it up the field on its
     * own. Cycling rather than toggling keeps it to a single gesture.
     */
    fun cyclePod() {
        podMode = (podMode + 1) % 3
        evs.add(Ev.PodMoved)
    }

    fun update(dt: Float): List<Ev> {
        evs.clear()
        if (over || stageDone) return evs
        val h = if (dt.isNaN()) 0f else dt.coerceIn(0f, 0.05f)
        scroll += h * 0.35f
        if (mercy > 0f) mercy -= h

        stepCharge(h)
        stepPod(h)
        stepAutoFire(h)
        stepSpawn(h)
        stepFoes(h)
        stepBolts(h)
        checkStage()
        return evs
    }

    /**
     * THE BEAM CHARGES WHILE THE POD IS LAUNCHED, and fires itself when full.
     *
     * It was going to charge on a held finger, the way R-Type does it. That is
     * impossible here: on this hardware the LAUNCHER OWNS LONG PRESS — holding
     * still opens the system control panel over the game, and the app never
     * sees the gesture at all. Verified on device, not assumed.
     *
     * So the charge is tied to the pod instead, which turns out better than the
     * original plan. Sending the pod away costs you your shield AND your
     * forward gun; what you get for it is a wave beam building. That is a real
     * decision made with the one button the game already has, rather than a
     * second control fighting the first for the same finger.
     */
    private fun stepCharge(h: Float) {
        if (podMode == POD_AWAY) {
            charge = (charge + h / 1.6f).coerceAtMost(1f)
            if (charge >= 1f) {
                bolts.add(Bolt(shipX, 0.10f, 0f, 1.55f, mine = true, big = true))
                evs.add(Ev.Beam)
                charge = 0f
            }
        } else if (charge > 0f) {
            // Recalling the pod banks nothing: the risk has to be taken for the
            // whole build or the trade is free.
            charge = (charge - h * 1.4f).coerceAtLeast(0f)
        }
    }

    private fun stepPod(h: Float) {
        val targetY = when (podMode) {
            POD_FRONT -> 0.20f
            // Behind the ship but still ON the field: at -0.02 it sat under
            // the floor line and was clipped, which made the rear position look
            // like the pod had been lost rather than repositioned.
            POD_REAR -> 0.012f
            else -> podY + 0.9f * h
        }
        val targetX = if (podMode == POD_AWAY) podX else shipX
        podX += (targetX - podX) * (1f - Math.pow(0.0006, h.toDouble()).toFloat())
        podY += (targetY - podY) * (1f - Math.pow(0.0006, h.toDouble()).toFloat())
        if (podMode == POD_AWAY && podY > 1.15f) {
            // It always comes back; losing it permanently would punish an
            // experiment the game wants the player to make.
            podMode = POD_REAR
        }
        podFireTimer += h
        if (podFireTimer >= 0.32f && podMode != POD_REAR) {
            podFireTimer = 0f
            bolts.add(Bolt(podX, podY + 0.04f, 0f, 1.25f, mine = true))
        }
    }

    private fun stepAutoFire(h: Float) {
        // AUTO-FIRE, so a tap is never wasted on shooting. The tap is the pod,
        // and a shooter where the player must also mash to fire leaves no
        // attention for the decision the game is actually about.
        // The forward gun goes with the pod: away means no shield and no
        // cannon until the beam lands.
        if (podMode == POD_AWAY) return
        autoTimer += h
        if (autoTimer >= 0.22f) {
            autoTimer = 0f
            bolts.add(Bolt(shipX, 0.10f, 0f, 1.35f, mine = true))
        }
    }

    private fun stepSpawn(h: Float) {
        if (bossOut) return
        if (spawned >= quota) {
            if (foes.none { it.alive }) spawnBoss()
            return
        }
        spawnTimer += h
        if (spawnTimer < spawnEvery) return
        spawnTimer = 0f
        spawned++
        val kind = when (rnd.nextInt(10)) {
            in 0..5 -> E_DRONE
            in 6..8 -> E_WEAVER
            else -> E_TURRET
        }
        val x = -0.8f + rnd.nextFloat() * 1.6f
        val f = Foe(kind, x, 1.12f, hp = if (kind == E_TURRET) 3 else 1)
        f.vy = when (kind) { E_WEAVER -> -0.26f; E_TURRET -> -0.10f; else -> -0.32f }
        foes.add(f)
    }

    private fun spawnBoss() {
        bossOut = true
        bossMax = 26 + stage * 8
        bossHp = bossMax
        val b = Foe(E_BOSS, 0f, 1.20f, hp = bossHp)
        b.vy = -0.10f
        foes.add(b)
    }

    private fun stepFoes(h: Float) {
        for (f in foes) {
            if (!f.alive) continue
            f.t += h
            when (f.kind) {
                E_WEAVER -> { f.y += f.vy * h; f.x += sin(f.t * 2.6f) * 0.55f * h }
                E_TURRET -> {
                    f.y += f.vy * h
                    if (f.y < 0.72f) f.y = 0.72f            // parks and shoots
                    if (f.t % 1.8f < h && f.y <= 0.73f) {
                        val dx = (shipX - f.x)
                        val n = kotlin.math.sqrt(dx * dx + 0.6f * 0.6f)
                        bolts.add(Bolt(f.x, f.y - 0.03f, dx / n * 0.5f, -0.6f, mine = false))
                    }
                }
                E_BOSS -> {
                    f.y += f.vy * h
                    if (f.y < 0.70f) { f.y = 0.70f; f.vy = 0f }
                    f.x = sin(f.t * 0.6f) * 0.45f
                    // The weak point tracks along the hull, so the safe place
                    // to shoot from keeps moving.
                    f.coreX = f.x + sin(f.t * 1.7f) * 0.12f
                    if (f.t % 0.9f < h) {
                        for (d in -1..1) bolts.add(
                            Bolt(f.x + d * 0.10f, f.y - 0.06f, d * 0.22f, -0.62f, mine = false))
                    }
                }
                else -> { f.y += f.vy * h }
            }
            if (f.y < -0.12f) f.alive = false
            // Ramming: the ship is a target too.
            if (mercy <= 0f && abs(f.x - shipX) < 0.07f && f.y in -0.02f..0.10f) hurt()
        }
        foes.removeAll { !it.alive && it.kind != E_BOSS }
    }

    private fun stepBolts(h: Float) {
        for (b in bolts) {
            if (!b.live) continue
            b.x += b.vx * h
            b.y += b.vy * h
            if (b.y > 1.25f || b.y < -0.15f || abs(b.x) > 1.15f) { b.live = false; continue }
            if (b.mine) myBolt(b) else theirBolt(b)
        }
        bolts.removeAll { !it.live }
    }

    private fun myBolt(b: Bolt) {
        for (f in foes) {
            if (!f.alive) continue
            val hitX: Float
            val w: Float
            val hgt: Float
            if (f.kind == E_BOSS) {
                // ONLY THE CORE COUNTS. Hitting the hull does nothing, which is
                // what makes a boss a puzzle rather than a wall of hit points.
                hitX = f.coreX; w = 0.075f; hgt = 0.05f
            } else { hitX = f.x; w = 0.055f; hgt = 0.045f }
            if (abs(b.x - hitX) > w || abs(b.y - f.y) > hgt) continue

            val dmg = if (b.big) 6 else 1
            if (!b.big) b.live = false
            f.hp -= dmg
            if (f.kind == E_BOSS) {
                bossHp = (bossHp - dmg).coerceAtLeast(0)
                if (bossHp == 0) {
                    f.alive = false
                    score += 2000
                    evs.add(Ev.Hit(f.x, f.y, f.kind, true, 2000))
                    evs.add(Ev.BossDown)
                } else evs.add(Ev.Hit(hitX, f.y, f.kind, false, 0))
            } else if (f.hp <= 0) {
                f.alive = false
                val pts = when (f.kind) { E_TURRET -> 150; E_WEAVER -> 80; else -> 50 }
                score += pts
                evs.add(Ev.Hit(f.x, f.y, f.kind, true, pts))
            } else evs.add(Ev.Hit(f.x, f.y, f.kind, false, 0))
            if (!b.big) return
        }
    }

    private fun theirBolt(b: Bolt) {
        // THE POD EATS IT. This is the whole reason the pod exists, and the
        // reason its position is a decision rather than a preference.
        if (podMode != POD_AWAY || true) {
            if (abs(b.x - podX) < 0.055f && abs(b.y - podY) < 0.05f) {
                b.live = false
                evs.add(Ev.Absorbed(podX, podY))
                return
            }
        }
        if (mercy <= 0f && abs(b.x - shipX) < 0.05f && b.y in -0.02f..0.09f) {
            b.live = false
            hurt()
        }
    }

    private fun hurt() {
        if (mercy > 0f) return
        mercy = 1.4f
        lives--
        evs.add(Ev.Hurt)
        if (lives <= 0) { over = true; evs.add(Ev.Over) }
    }

    private fun checkStage() {
        if (bossOut && !stageDone && foes.none { it.alive && it.kind == E_BOSS }) {
            stageDone = true
            evs.add(Ev.StageDone)
        }
    }

    fun mercyActive() = mercy > 0f
    fun bossPresent() = foes.any { it.alive && it.kind == E_BOSS }
}

class Stage(
    @JvmField val n: Int, @JvmField val name: String,
    @JvmField val spawnEvery: Float, @JvmField val quota: Int,
    @JvmField val music: Int, @JvmField val blurb: String
)

/**
 * Five stages, each ending in a boss. Short on purpose — R-Type stages are
 * memorised, not ground through, and a stage you can see the end of is one you
 * will try again.
 */
object Stages {
    val all = listOf(
        Stage(1, "APPROACH", 1.30f, 14, 0, "POD FRONT BLOCKS FIRE"),
        Stage(2, "DEBRIS",   1.15f, 18, 1, "TAP TO MOVE THE POD"),
        Stage(3, "BATTERY",  1.00f, 22, 2, "SEND THE POD TO CHARGE"),
        Stage(4, "GAUNTLET", 0.90f, 26, 3, "TURRETS DO NOT RETREAT"),
        Stage(5, "CORE",     0.80f, 30, 4, "HIT THE CORE, NOT THE HULL")
    )
    val count get() = all.size
    fun get(n: Int): Stage {
        val i = n.coerceAtLeast(1)
        val lap = (i - 1) / count
        val b = all[(i - 1) % count]
        if (lap == 0) return b
        return Stage(i, b.name, (b.spawnEvery / (1f + 0.18f * lap)).coerceAtLeast(0.45f),
            b.quota + 6 * lap, b.music, b.blurb)
    }
}
