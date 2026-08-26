# tapforce — R-Type, turned vertical, for the RayNeo X3 Pro

Free software, GPL v3 (see LICENSE). Derived from `x3breakout`, which is GPL v3.
A vertical descendant of [`tapshot`](https://github.com/tropicalstream/tapshot).

## Controls

| gesture | |
|---|---|
| **drag** | move the ship along the bottom axis |
| **tap** | cycle the FORCE pod — front, rear, launched |
| BACK | quit a run |

The cannon **fires by itself**. A shooter where the player must also mash to
shoot leaves no attention for the decision the game is actually about — and in
R-Type that decision is always *where should the pod be*.

Nothing is bound to a double tap.

## What was worth copying from R-Type

R-Type (1987) is remembered for three things and none of them is shooting:

1. **The Force.** An indestructible pod that docks front or back, eats anything
   that hits it, and fires on its own. It turns the game from "dodge everything"
   into "decide which side needs armour" — a movable shield that is also a gun.
2. **The charge beam.** Give up rate of fire for a moment, get something that
   clears a lane.
3. **Bosses with a weak point.** Not a hit-point sponge; a specific place you
   have to put the shot, while the safe ground keeps moving.

All three survive the move to a vertical field and a one-axis controller, which
is why this is the shooter worth copying rather than another Invaders.

## The charge beam had to be redesigned, and the game is better for it

It was going to charge on a held finger, the way the arcade did it. **That is
impossible on this hardware: the launcher owns long press.** Holding still opens
the system control panel over the game and the app never sees the gesture —
verified on device, and confirmed as intended OS behaviour.

So the charge is tied to the pod instead. **Launching the pod costs you your
shield *and* your forward gun; what you get is a wave beam building.** At full
charge it fires itself. Recalling the pod early banks nothing, so the risk has
to be taken for the whole build.

That is a genuine decision made with the one button the game already has, rather
than a second control fighting the first for the same finger.

## Stages

Five, each ending in a boss, cycling with a difficulty lap. Short on purpose —
R-Type stages are memorised, not ground through, and a stage you can see the end
of is one you will try again.

| stage | teaches |
|---|---|
| APPROACH | the pod in front blocks fire |
| DEBRIS | tap to move the pod |
| BATTERY | send the pod away to charge |
| GAUNTLET | turrets park and do not retreat |
| CORE | hit the core, not the hull |

**On the boss, only the core counts.** Hitting the hull does nothing, and the
core tracks along the hull as it moves, so the place you must shoot from keeps
changing. That is what makes a boss a puzzle instead of a wall.

## Not a bullet hell

Enemy fire is sparse and aimed, dives are single, ramming and bolts both grant
1.4 s of mercy so one bad moment costs one life rather than three, and **SPEED:
RELAXED** on the menu thins the spawns further.

## Icon

`ic_force_foreground.xml` draws the **pod**, not the ship: the pod is what the
game is about, and a ring reads at 48 dp where a small triangle turns to mush.

## Build

```
./gradlew :app:assembleDebug
adb -s <glasses-serial> install -r app/build/outputs/apk/debug/tapforce.apk
```

## Device notes

- **Long press belongs to the OS.** Do not design a gesture around it.
- The launcher **force-stops the app** the moment it loses foreground, and a
  force-stop never delivers `onDestroy` — so anything that must be saved has to
  be saved in `onPause`.
- Visible field ≈ **±0.144 across, ±0.117 up** in plane-local metres; the vector
  font advances ~5.4× its size parameter per character and stands ~7.3× tall.
- **If nothing appears, check `batch.setBasis(...)` is still called in
  `Game.update`.**
