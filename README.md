# Toaru Esper Add-on (for Re:AcademyCraft 1.5.0, Minecraft 1.20.1, Forge 47.4.10)

Version 0.1.0. Adds a new category **Senior** with the first ability, **Black Hole**.

> **Honest status:** the code is written but has **not been compiled against Minecraft and has not been tested in the game.**
> The only checks done so far: the text of every file parses as valid Java, all Re:AcademyCraft API names resolve
> against the real 1.5.0 API jar, and a script confirms the add-on uses no particle code.

## What Black Hole does
- Aim somewhere (up to 28 blocks) and use the skill. A black hole opens there and grows for 1 second.
- For 6 seconds it drags nearby creatures toward its centre (12 block radius). Creatures close to the centre take damage once a second.
- Then it collapses: heavy damage (less at the edge) and survivors are thrown outward.
- Press the skill key again to collapse it early.
- It never breaks or changes blocks. Every hit goes through Re:AcademyCraft's safety check (protected areas, PvP and team rules).
- Bosses (Ender Dragon, Wither) are ignored by default. Creative-mode and spectator players are ignored.

## Zero particles
- The visuals are plain 3D shapes drawn directly in the world: a black sphere, two spinning glowing discs, inward-moving rings and an expanding shockwave shell.
- No particles, no extra Minecraft entities. Only two tiny messages go to players, and only to players within 160 blocks.
- Speed protections: max 6 black holes drawn at once, far ones use fewer segments, ones out of view are skipped, finished ones remove themselves.
- `python3 tools/check_no_particles.py` scans the code and fails if any particle code appears. GitHub runs it on every build.
- This covers our own code. Other mods, and Re:AcademyCraft's own skills, still use particles.

## Build the jar (pick ONE)

### Option A: let GitHub build it (nothing to install)
1. On github.com click **+** (top right), then **New repository**. Name it, click **Create repository**.
2. Click **uploading an existing file**. Open this unzipped folder and **drag everything inside it** (including the hidden-looking `.github` folder, `libs`, `gradle`, `src`, `tools`) onto the page. Click **Commit changes**.
3. Click the **Actions** tab. Open **Build the add-on**. Click **Run workflow**. Wait about 5-10 minutes for a green tick.
4. Open the finished run. At the bottom, under **Artifacts**, download **toaru-esper-addon**. Unzip it: the `.jar` inside is the add-on.
5. If the run shows a red X, open it, copy the red error text, and send it to me. I'll fix it.

### Option B: build on your own PC
1. Install **Java 17** (Temurin 17 JDK from adoptium.net).
2. Open the unzipped folder, click the address bar, type `cmd`, press Enter.
3. Type `gradlew.bat build` and press Enter. The first build downloads a lot and can take 10+ minutes.
4. The jar appears in the `build\libs` folder. If it fails, send me the red error text.

## Install
Put **two** jars in the `mods` folder of Minecraft **Forge 1.20.1 (47.4.10 or newer 47.x)**, on the server **and** on every player:
1. Re:AcademyCraft 1.5.0 (the normal mod jar from CurseForge, not the API jar)
2. `toaru-esper-addon-forge-1.20.1-0.1.0.jar`

## Test it
1. Make a creative singleplayer world with cheats on.
2. In chat, one at a time:
   - `/aim cat toaruesper:senior` (gives you the Senior category)
   - `/aim learn toaruesper:black_hole`
   - `/aim fullcp`
   - If a command says you are not allowed, run `/aim cheats_on` first. If `learn` says the skill is unknown, tell me; that would be something for me to fix.
3. Press Esc, Options, Controls, find the **AcademyCraft** group. Find **Skills and presets** and **Skill slot 1** (I haven't checked the default keys). Put Black Hole in slot 1 of preset 1.
4. Press **Toggle ability** to switch your ability on, look at a group of mobs about 15 blocks away, press the **Skill slot 1** key.
5. What you should see, in order: a black ball growing, glowing purple-blue discs, rings shrinking inward, mobs pulled in and hurt, then a bright shockwave and survivors thrown outward.
6. Also test: pressing the key again mid-way; using it while two players watch (multiplayer); two players casting at once; death during the effect; going through a portal during the effect; and your frame rate with several active. Tell me anything that looks or feels wrong.

## Settings
After the first start, edit `config/toaruesper-common.toml` (range, radius, duration, pull strength, damage, knockback, cooldown, CP cost, overload, target limit, bosses on/off). Restart to apply.
Re:AcademyCraft also creates `config/academy-addon-toaruesper.toml` where the skill can be switched off or its damage and CP use scaled.
The CP cost (800) is a **guess**; tune it to your server's CP scale.

## Known open questions (untested)
- Whether other Re:AcademyCraft skills can be used while a Black Hole is open (it runs as a toggle session).
- Whether pulled players feel smooth or get jerked back by the game's movement correction.
- Whether `/aim learn` accepts this add-on's skill; whether the skill tree on the developer machine shows it correctly.
- Players who walk into range after the hole opened will not see it (the opening message was already sent).
- Shader packs (Oculus/Iris) and "Fabulous" graphics are untested.
- The category icon and skill icon use no custom pictures yet; only the Senior Factor item has a texture.

## License note
Re:AcademyCraft and its API are GPLv3. This README is not legal advice: before you share this add-on outside your server, check the license obligations (see the SDK_README of Re:AcademyCraft).
