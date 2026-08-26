package com.x3.tapforce.game

import com.x3.tapforce.Settings
import com.x3.tapforce.audio.GameAudio
import com.x3.tapforce.input.SwipeControl
import com.x3.tapforce.render.NeonBatch
import com.x3.tapforce.render.Particles
import com.x3.tapforce.render.VectorFont
import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  TAPFORCE — R-Type, turned vertical, on three inputs.
 * ============================================================================
 *
 *     drag   move the ship along the bottom axis
 *     tap    cycle the FORCE pod: front, rear, launched
 *     hold   charge, release for the wave beam
 *
 * The cannon fires by itself. A shooter where the player must also mash to
 * shoot leaves no attention for the decision the game is actually about — and
 * in R-Type that decision is always "where should the pod be".
 *
 * Nothing is bound to a double tap.
 */
class Game(
    private val settings: Settings,
    private val audio: GameAudio,
    private val swipe: SwipeControl
) {

    val batch = NeonBatch()
    @JvmField var fps: Float = 0f
    @JvmField var tempC: Int = 0

    private companion object {
        const val ISO_TILT = 0.20f
        const val HALF_W = 0.1250f
        const val BOT_V = -0.1020f
        const val TOP_V = 0.1020f

        const val ST_MENU = 0
        const val ST_PLAY = 1
        const val ST_STAGE = 2
        const val ST_OVER = 3
        const val ROW_GAP_MS = 150L
    }

    private val force = Force()
    private val parts = Particles(512)

    private var state = ST_MENU
    private var stateT = 0f
    private var row = 1
    private var lastRowMs = 0L
    private var stageNo = 1
    private var stage = Stages.get(1)
    private var musicPlaying = -1
    private var shake = 0f

    private val events = ArrayList<Char>(8)
    private val windowScales = floatArrayOf(70f, 92f, 115f)

    init { parts.scaleLengths(0.013f) }

    fun tap() { synchronized(events) { events.add('T') } }
    fun doubleTap() { }
    fun swipe(steps: Int) { synchronized(events) { events.add(if (steps > 0) '+' else '-') } }

    private fun drainInput() {
        synchronized(events) {
            for (e in events) when (e) {
                '+' -> onSwipe(1); '-' -> onSwipe(-1); 'T' -> onTap()
            }
            events.clear()
        }
    }

    private fun onSwipe(dir: Int) {
        if (state != ST_MENU) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRowMs < ROW_GAP_MS) return
        lastRowMs = now
        row = ((row + dir) % 2 + 2) % 2
        audio.sfx("ui")
    }

    private fun onTap() {
        when (state) {
            ST_MENU -> if (row == 0) { settings.relaxed = !settings.relaxed; audio.sfx("ui") } else startRun()
            ST_PLAY -> { force.cyclePod(); audio.sfx("ui") }
            ST_STAGE -> startStage(stageNo + 1, false)
            else -> { state = ST_MENU; stateT = 0f; row = 1; stopMusic(); audio.sfx("ui") }
        }
    }

    fun back(): Boolean = when (state) {
        ST_PLAY, ST_STAGE -> { stopMusic(); state = ST_MENU; stateT = 0f; row = 1; true }
        ST_OVER -> { state = ST_MENU; stateT = 0f; row = 1; true }
        else -> false
    }

    private fun startRun() = startStage(1, true)

    private fun startStage(n: Int, fresh: Boolean) {
        stageNo = n
        stage = Stages.get(n)
        force.startStage(
            if (settings.relaxed) Stage(stage.n, stage.name, stage.spawnEvery * 1.45f,
                (stage.quota * 0.8f).toInt(), stage.music, stage.blurb) else stage,
            fresh)
        parts.clear()
        state = ST_PLAY; stateT = 0f; shake = 0f
        if (musicPlaying != stage.music) { audio.playLevelMusic(stage.music + 1); musicPlaying = stage.music }
        audio.sfx("wave")
    }

    private fun stopMusic() { if (musicPlaying >= 0) { audio.stopLevelMusic(); musicPlaying = -1 } }

    fun update(dt: Float) {
        stateT += dt
        drainInput()

        if (state == ST_PLAY) {
            force.moveTo(swipe.pos01 * 2f - 1f)
            step(dt)
        }
        parts.update(dt)
        if (shake > 0f) shake = (shake - dt * 2.4f).coerceAtLeast(0f)

        val s = windowScales[settings.windowSize.coerceIn(0, 2)]
        val ct = cos(ISO_TILT); val st = sin(ISO_TILT)
        batch.setBasis(0f, 0f, 0f, s, 0f, 0f, 0f, s * ct, -s * st, 0f, s * st, s * ct)
        batch.lift = 0f

        batch.begin()
        when (state) {
            ST_MENU -> drawMenu()
            ST_PLAY -> { drawField(); drawHud() }
            ST_STAGE -> { drawField(); drawHud(); drawStageEnd() }
            else -> { drawField(); drawOver() }
        }
        parts.draw(batch)
    }

    private fun step(dt: Float) {
        for (e in force.update(dt)) when (e) {
            is Ev.Hit -> {
                audio.sfx(if (e.killed) "hit_big" else "hit")
                if (e.killed) {
                    parts.burst(fx(e.x), fy(e.y), if (e.kind == E_BOSS) 60 else 18, 0.07f,
                        1f, 0.75f, 0.35f, 0.8f)
                    if (e.kind == E_BOSS) shake = 1f
                }
            }
            is Ev.Absorbed -> {
                audio.sfx("enemy")
                parts.burst(fx(e.x), fy(e.y), 8, 0.04f, 0.4f, 0.9f, 1f, 0.3f)
            }
            is Ev.PodMoved -> Unit
            is Ev.Beam -> { audio.sfx("perfect"); shake = 0.3f }
            is Ev.Hurt -> { audio.sfx("hurt"); shake = 1f
                parts.burst(fx(force.shipX), fy(0.05f), 40, 0.09f, 1f, 0.4f, 0.2f, 0.9f) }
            is Ev.BossDown -> { audio.sfx("rescue"); shake = 1f }
            is Ev.StageDone -> { audio.sfx("wave"); state = ST_STAGE; stateT = 0f }
            is Ev.Over -> { audio.sfx("hurt"); stopMusic(); state = ST_OVER; stateT = 0f }
        }
    }

    private fun fx(x: Float) = x * HALF_W + shakeU()
    private fun fy(y: Float) = BOT_V + y * (TOP_V - BOT_V) + shakeV()
    private fun shakeU() = if (shake <= 0f) 0f else sin(stateT * 57f) * 0.0018f * shake
    private fun shakeV() = if (shake <= 0f) 0f else cos(stateT * 41f) * 0.0018f * shake

    private fun drawField() {
        // Scrolling rails: the only thing that says "vertical shooter" before
        // anything else moves. Cheap, and it never covers the room.
        val ph = force.scrollPhase() % 0.25f
        var i = 0
        while (i < 9) {
            val y = (i * 0.25f - ph) % 2.25f
            if (y in 0f..1.05f) {
                val v = fy(y)
                batch.line(-HALF_W, v, -HALF_W * 0.86f, v, 0.0006f, 0.3f, 0.7f, 1f, 0.16f)
                batch.line(HALF_W * 0.86f, v, HALF_W, v, 0.0006f, 0.3f, 0.7f, 1f, 0.16f)
            }
            i++
        }
        batch.line(-HALF_W, fy(0f), HALF_W, fy(0f), 0.0010f, 0.4f, 0.85f, 1f, 0.5f)

        for (f in force.foes) {
            if (!f.alive) continue
            drawFoe(f)
        }
        for (b in force.bolts) {
            if (!b.live) continue
            val u = fx(b.x); val v = fy(b.y)
            if (b.mine) {
                if (b.big) {
                    batch.fill(u, v, 0.010f, 0.012f, 0f, 0.6f, 1f, 1f, 0.55f)
                    batch.circle(u, v, 0.016f, 0.0010f, 0.7f, 1f, 1f, 0.9f)
                } else batch.line(u, v - 0.005f, u, v + 0.006f, 0.0012f, 0.6f, 1f, 1f, 0.95f)
            } else batch.line(u, v - 0.005f, u, v + 0.005f, 0.0012f, 1f, 0.5f, 0.25f, 0.9f)
        }
        drawPod()
        drawShip()
    }

    private fun drawFoe(f: Foe) {
        val u = fx(f.x); val v = fy(f.y)
        when (f.kind) {
            E_BOSS -> {
                val w = 0.052f; val h = 0.018f
                batch.fill(u, v, w * 0.8f, h * 0.6f, 0f, 0.5f, 0.2f, 0.5f, 0.45f)
                batch.line(u - w, v - h, u + w, v - h, 0.0012f, 1f, 0.45f, 0.85f, 0.95f)
                batch.line(u - w, v + h, u + w, v + h, 0.0012f, 1f, 0.45f, 0.85f, 0.95f)
                batch.line(u - w, v - h, u - w, v + h, 0.0012f, 1f, 0.45f, 0.85f, 0.95f)
                batch.line(u + w, v - h, u + w, v + h, 0.0012f, 1f, 0.45f, 0.85f, 0.95f)
                // THE CORE — the only place a shot counts, and it keeps moving.
                val cu = fx(f.coreX)
                val pulse = 0.55f + 0.45f * sin(stateT * 7f)
                batch.circle(cu, v, 0.011f, 0.0013f, 1f, 0.9f, 0.3f, pulse)
                batch.circle(cu, v, 0.005f, 0.0011f, 1f, 1f, 0.6f, pulse)
            }
            E_TURRET -> {
                batch.fill(u, v, 0.009f, 0.007f, 0f, 0.5f, 0.25f, 0.1f, 0.5f)
                batch.line(u - 0.012f, v - 0.008f, u + 0.012f, v - 0.008f, 0.0010f, 1f, 0.6f, 0.25f, 1f)
                batch.line(u - 0.012f, v - 0.008f, u, v + 0.010f, 0.0010f, 1f, 0.6f, 0.25f, 1f)
                batch.line(u + 0.012f, v - 0.008f, u, v + 0.010f, 0.0010f, 1f, 0.6f, 0.25f, 1f)
            }
            E_WEAVER -> {
                batch.line(u - 0.011f, v, u, v + 0.008f, 0.0009f, 1f, 0.9f, 0.3f, 0.95f)
                batch.line(u, v + 0.008f, u + 0.011f, v, 0.0009f, 1f, 0.9f, 0.3f, 0.95f)
                batch.line(u + 0.011f, v, u, v - 0.008f, 0.0009f, 1f, 0.9f, 0.3f, 0.95f)
                batch.line(u, v - 0.008f, u - 0.011f, v, 0.0009f, 1f, 0.9f, 0.3f, 0.95f)
            }
            else -> {
                batch.line(u - 0.010f, v + 0.006f, u + 0.010f, v + 0.006f, 0.0009f, 0.35f, 1f, 0.9f, 0.95f)
                batch.line(u - 0.010f, v + 0.006f, u, v - 0.008f, 0.0009f, 0.35f, 1f, 0.9f, 0.95f)
                batch.line(u + 0.010f, v + 0.006f, u, v - 0.008f, 0.0009f, 0.35f, 1f, 0.9f, 0.95f)
            }
        }
    }

    private fun drawPod() {
        val u = fx(force.podX); val v = fy(force.podY)
        val spin = stateT * 3.4f
        // Drawn as a ring with orbiting ticks so it reads as MACHINERY rather
        // than as another bullet.
        batch.circle(u, v, 0.0115f, 0.0012f, 1f, 0.85f, 0.35f, 0.95f)
        batch.circle(u, v, 0.0055f, 0.0010f, 1f, 1f, 0.7f, 0.85f)
        for (k in 0..2) {
            val a = spin + k * 2.094f
            batch.line(u + cos(a) * 0.013f, v + sin(a) * 0.013f,
                u + cos(a) * 0.018f, v + sin(a) * 0.018f, 0.0010f, 1f, 0.8f, 0.3f, 0.8f)
        }
    }

    private fun drawShip() {
        val u = fx(force.shipX); val v = fy(0.045f)
        val a = if (force.mercyActive() && ((stateT * 12f).toInt() % 2 == 0)) 0.3f else 1f
        val w = 0.012f
        batch.fill(u, v, w * 0.45f, 0.006f, 0f, 0.25f, 0.8f, 0.6f, 0.55f * a)
        batch.line(u, v + 0.013f, u - w, v - 0.008f, 0.0012f, 0.5f, 1f, 0.85f, a)
        batch.line(u, v + 0.013f, u + w, v - 0.008f, 0.0012f, 0.5f, 1f, 0.85f, a)
        batch.line(u - w, v - 0.008f, u + w, v - 0.008f, 0.0011f, 0.5f, 1f, 0.85f, a)

        // Charge ring: the only sign the launched pod is buying you something.
        if (force.charge > 0.02f) {
            val c = force.charge
            batch.circle(u, v, 0.014f + c * 0.012f, 0.0012f,
                1f, 0.6f + c * 0.4f, 0.2f + c * 0.6f, 0.35f + c * 0.6f)
            if (c >= 0.98f) batch.circle(u, v, 0.030f, 0.0011f, 1f, 1f, 0.8f,
                0.5f + 0.5f * sin(stateT * 14f))
        }
    }

    private fun drawHud() {
        VectorFont.draw(batch, "${force.score}", -0.132f, 0.104f, 0.0024f, 0.6f, 0.95f, 1f, 0.85f)
        VectorFont.draw(batch, "S$stageNo ${stage.name}", 0.022f, 0.104f, 0.0022f, 1f, 0.6f, 0.9f, 0.75f)
        var i = 0
        while (i < force.lives) {
            batch.circle(-0.132f + i * 0.009f, 0.092f, 0.0024f, 0.0007f, 0.4f, 1f, 0.7f, 0.9f); i++
        }
        val podTxt = when (force.podMode) {
            POD_FRONT -> "POD FRONT"; POD_REAR -> "POD REAR"; else -> "POD AWAY"
        }
        VectorFont.draw(batch, podTxt, -0.130f, -0.108f, 0.0020f, 1f, 0.85f, 0.35f, 0.7f)

        if (force.bossPresent() && force.bossMax > 0) {
            val p = force.bossHp.toFloat() / force.bossMax
            val w = 0.085f
            batch.line(-w, 0.088f, w, 0.088f, 0.0006f, 0.5f, 0.3f, 0.5f, 0.3f)
            batch.line(-w, 0.088f, -w + 2 * w * p, 0.088f, 0.0013f, 1f, 0.4f, 0.7f, 0.9f)
        }
        if (stateT < 2.4f) {
            val a = if (stateT > 1.9f) (2.4f - stateT) / 0.5f else 1f
            VectorFont.draw(batch, stage.blurb, 0f, 0.052f, 0.0024f, 1f, 0.8f, 0.4f, a * 0.9f, true)
        }
    }

    private fun drawMenu() {
        VectorFont.draw(batch, "TAPFORCE", 0f, 0.062f, 0.0064f, 1f, 0.75f, 0.3f, 1f, true)
        val sel0 = row == 0
        VectorFont.draw(batch, "SPEED", -0.106f, 0.014f, 0.0028f, 0.55f, 0.9f, 1f, if (sel0) 1f else 0.45f)
        VectorFont.draw(batch, if (settings.relaxed) "RELAXED" else "NORMAL", 0.006f, 0.014f, 0.0028f,
            1f, 0.85f, 0.35f, if (sel0) 1f else 0.45f)
        if (sel0) caret(-0.128f, 0.014f, 0.0028f)
        val sel1 = row == 1
        val pulse = if (sel1) 0.55f + 0.45f * sin(stateT * 3f) else 0.4f
        VectorFont.draw(batch, "LAUNCH", 0f, -0.022f, 0.0042f, 1f, 1f, 0.6f, pulse, true)
        if (sel1) caret(-0.090f, -0.022f, 0.0042f)
        VectorFont.draw(batch, "DRAG   MOVE", -0.132f, -0.058f, 0.0019f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "TAP    POD FRONT/REAR/AWAY", -0.132f, -0.076f, 0.0019f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "POD AWAY CHARGES BEAM", -0.132f, -0.094f, 0.0019f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "BACK   QUIT", -0.132f, -0.112f, 0.0019f, 0.5f, 0.9f, 1f, 0.38f)
    }

    private fun caret(u: Float, v: Float, size: Float) {
        val h = size * 1.3f; val cy = v + size * 1.4f
        batch.line(u, cy + h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
        batch.line(u, cy - h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
    }

    private fun drawStageEnd() {
        VectorFont.draw(batch, "STAGE $stageNo CLEAR", 0f, 0.030f, 0.0038f, 0.4f, 1f, 0.7f, 1f, true)
        VectorFont.draw(batch, "${force.score}", 0f, 0.004f, 0.0030f, 1f, 1f, 1f, 0.9f, true)
        val p = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP TO CONTINUE", 0f, -0.026f, 0.0026f, 1f, 1f, 0.6f, p, true)
        if (stateT < 0.8f && parts.live < 200) parts.burst(0f, 0.030f, 5, 0.06f, 0.4f, 1f, 0.8f, 1.1f)
    }

    private fun drawOver() {
        VectorFont.draw(batch, "SHIP LOST", 0f, 0.034f, 0.0046f, 1f, 0.35f, 0.4f, 1f, true)
        VectorFont.draw(batch, "SCORE ${force.score}", 0f, 0.006f, 0.0030f, 1f, 1f, 1f, 0.9f, true)
        VectorFont.draw(batch, "STAGE $stageNo", 0f, -0.016f, 0.0024f, 0.7f, 0.9f, 1f, 0.8f, true)
        val p = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP FOR MENU", 0f, -0.044f, 0.0028f, 1f, 1f, 0.6f, p, true)
    }
}
