package io.toaruesper.addon.senior;

import io.github.pinchan4273.reacademycraft.api.ActivationOutcome;
import io.github.pinchan4273.reacademycraft.api.StopReason;
import io.github.pinchan4273.reacademycraft.api.mc.AbilityActor;
import io.github.pinchan4273.reacademycraft.api.mc.AcademySafety;
import io.github.pinchan4273.reacademycraft.api.mc.ActivationContext;
import io.github.pinchan4273.reacademycraft.api.mc.SkillBehavior;
import io.toaruesper.addon.net.SeniorNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppedEvent;

/**
 * Black Hole (Senior). All the gameplay runs on the server; the clients only get two tiny messages to draw it.
 * <ol>
 * <li>Start: the hole opens where the caster looks (up to the configured range) and grows for 1 second.</li>
 * <li>While open: creatures in the radius are pulled toward the centre; those near the centre take damage once a second.</li>
 * <li>End: a final collapse deals heavy damage and throws survivors outward. No blocks are ever changed.</li>
 * </ol>
 * Every attack goes through Re:AcademyCraft's safety check, so protected areas, PvP and team rules still apply.
 * Pressing the skill key again collapses the hole early.
 */
public final class BlackHoleBehavior implements SkillBehavior {
    /** One running hole per caster. Only touched from the server thread. */
    private static final Map<UUID, Hole> ACTIVE = new HashMap<>();
    private static int nextNetId = 1;

    private static final int GROW_TICKS = 20;

    private static final class Hole {
        final int netId;
        final Vec3 center;
        final int duration;
        /** Skill-experience scaling of the damage (about 0.75x to 1.25x). */
        final float scale;
        /** Remembers whether the safety check allows hurting each creature, so it is not asked every tick. */
        final Map<UUID, Boolean> verdict = new HashMap<>();

        Hole(int netId, Vec3 center, int duration, float scale) {
            this.netId = netId;
            this.center = center;
            this.duration = duration;
            this.scale = scale;
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
    }

    @Override
    public ActivationOutcome start(ActivationContext c) {
        var actor = c.actor();
        var body = actor.entity();
        if (body == null || c.naturalUser() == null) return ActivationOutcome.REFUSED_INVALID;

        // Re:AcademyCraft already blocks a second Black Hole while one runs, so an old entry here is left over: clear it.
        var stale = ACTIVE.remove(actor.id());
        if (stale != null) SeniorNetwork.sendEnd(actor.level(), stale.center, stale.netId, false);

        double range = SeniorConfig.BH_RANGE.get();
        Vec3 eye = actor.position();
        Vec3 end = eye.add(actor.look().scale(range));
        var hit = actor.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        Vec3 center;
        if (hit.getType() == HitResult.Type.MISS) {
            center = end;
        } else {
            // Float a little off the surface so the hole is not buried in the wall or floor.
            var face = hit.getDirection();
            center = hit.getLocation().add(face.getStepX() * 1.5, face.getStepY() * 1.5, face.getStepZ() * 1.5);
        }

        if (!c.pay(SeniorConfig.BH_CP_COST.get().floatValue(), SeniorConfig.BH_OVERLOAD_COST.get().floatValue())) {
            return ActivationOutcome.REFUSED_RESOURCE;
        }

        int duration = Math.max(GROW_TICKS, Math.round(SeniorConfig.BH_DURATION_SECONDS.get().floatValue() * 20f));
        var hole = new Hole(nextNetId++, center, duration, 0.75f + 0.5f * c.proficiency());
        ACTIVE.put(actor.id(), hole);

        // The cooldown counts from now, so it is the open time plus the configured cooldown.
        int cooldown = duration + Math.round(SeniorConfig.BH_COOLDOWN_SECONDS.get().floatValue() * 20f);
        c.setCooldown(Math.min(72000, cooldown));
        c.addProficiency(0.01f);

        var level = actor.level();
        SeniorNetwork.sendStart(level, hole.netId, center, SeniorConfig.BH_RADIUS.get().floatValue(), duration);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.5f, 0.5f);
        return ActivationOutcome.STARTED;
    }

    @Override
    public boolean tick(ActivationContext c) {
        var actor = c.actor();
        var hole = ACTIVE.get(actor.id());
        if (hole == null) return false;
        if (!actor.valid()) {
            finish(actor, hole, false);
            return false;
        }
        int age = c.age();
        if (age >= hole.duration) {
            finish(actor, hole, true);
            return false;
        }
        if (age % 40 == 0) hole.verdict.clear();

        double radius = SeniorConfig.BH_RADIUS.get();
        double pull = SeniorConfig.BH_PULL.get();
        double damageRadius = SeniorConfig.BH_DAMAGE_RADIUS.get();
        float perSecond = SeniorConfig.BH_DAMAGE_PER_SECOND.get().floatValue();
        float ramp = Math.min(1f, age / (float) GROW_TICKS);
        boolean damageNow = age >= GROW_TICKS && age % 20 == 0;

        for (var e : targets(actor, hole, radius)) {
            if (!allowed(actor, hole, e)) continue;
            pullToward(hole, e, radius, pull, ramp);
            if (damageNow) {
                double dist = e.getBoundingBox().getCenter().distanceTo(hole.center);
                if (dist <= damageRadius) {
                    // Normal damage at the edge of the damage zone, double right at the core.
                    float amount = (float) (perSecond * hole.scale * (1.0 + (1.0 - dist / damageRadius)));
                    AcademySafety.attack(actor, e, SeniorAbilities.BLACK_HOLE, amount);
                }
            }
        }
        return true;
    }

    @Override
    public void stop(ActivationContext c, StopReason reason) {
        var actor = c.actor();
        var hole = ACTIVE.get(actor.id());
        if (hole == null) return; // already finished in tick()
        // Pressing the key again (after the hole has formed) triggers the collapse early. Any other reason just closes it quietly.
        finish(actor, hole, reason == StopReason.SWITCHED_OFF && c.age() >= GROW_TICKS);
    }

    private static void finish(AbilityActor actor, Hole hole, boolean collapse) {
        ACTIVE.remove(actor.id());
        if (collapse) collapse(actor, hole, SeniorConfig.BH_RADIUS.get());
        SeniorNetwork.sendEnd(actor.level(), hole.center, hole.netId, collapse);
    }

    /** Creatures inside the radius that may be affected, closest first, capped for server speed. */
    private static List<LivingEntity> targets(AbilityActor actor, Hole hole, double radius) {
        var self = actor.entity();
        boolean bosses = SeniorConfig.BH_AFFECT_BOSSES.get();
        double radiusSq = radius * radius;
        var box = new AABB(hole.center, hole.center).inflate(radius);
        var found = new ArrayList<>(actor.level().getEntitiesOfClass(LivingEntity.class, box, e ->
                e != self
                        && e.isAlive()
                        && !e.isSpectator()
                        && !(e instanceof Player p && p.isCreative())
                        && (bosses || !(e instanceof EnderDragon || e instanceof WitherBoss))
                        && e.getBoundingBox().getCenter().distanceToSqr(hole.center) <= radiusSq));
        int max = SeniorConfig.BH_MAX_TARGETS.get();
        if (found.size() > max) {
            found.sort(Comparator.comparingDouble(e -> e.getBoundingBox().getCenter().distanceToSqr(hole.center)));
            return new ArrayList<>(found.subList(0, max));
        }
        return found;
    }

    private static boolean allowed(AbilityActor actor, Hole hole, LivingEntity e) {
        return hole.verdict.computeIfAbsent(e.getUUID(), k -> AcademySafety.mayAttack(actor, e, SeniorAbilities.BLACK_HOLE));
    }

    private static void pullToward(Hole hole, LivingEntity e, double radius, double pull, float ramp) {
        Vec3 toCentre = hole.center.subtract(e.getBoundingBox().getCenter());
        double dist = toCentre.length();
        Vec3 v = e.getDeltaMovement();
        if (dist < 0.8) {
            // At the core: hold still instead of swinging back and forth.
            v = v.scale(0.4);
        } else {
            double falloff = 0.35 + 0.65 * (1.0 - Math.min(1.0, dist / radius));
            v = v.scale(0.55).add(toCentre.scale(pull * ramp * falloff / dist));
            if (e.onGround()) v = v.add(0, 0.12, 0); // lift off the ground so friction does not hold them
            if (v.lengthSqr() > 1.0) v = v.normalize();
        }
        e.setDeltaMovement(v);
        e.hurtMarked = true; // makes the server send the new velocity to players
        e.fallDistance = 0;
    }

    private static void collapse(AbilityActor actor, Hole hole, double radius) {
        var level = actor.level();
        float finalDamage = SeniorConfig.BH_FINAL_DAMAGE.get().floatValue() * hole.scale;
        double knockback = SeniorConfig.BH_KNOCKBACK.get();
        for (var e : targets(actor, hole, radius)) {
            if (!allowed(actor, hole, e)) continue;
            Vec3 off = e.getBoundingBox().getCenter().subtract(hole.center);
            double dist = off.length();
            double edge = Math.min(1.0, dist / radius);
            e.invulnerableTime = 0; // the final blow should land even right after a damage tick
            if (!AcademySafety.attack(actor, e, SeniorAbilities.BLACK_HOLE, (float) (finalDamage * (1.0 - 0.5 * edge)))) continue;
            if (!e.isAlive()) continue;
            Vec3 dir = dist > 1.0e-3 ? off.scale(1.0 / dist) : new Vec3(0, 1, 0);
            double k = knockback * (1.0 - 0.5 * edge);
            e.setDeltaMovement(dir.x * k, dir.y * k * 0.6 + 0.45, dir.z * k);
            e.hurtMarked = true;
            e.fallDistance = 0;
        }
        level.playSound(null, hole.center.x, hole.center.y, hole.center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.0f, 0.6f);
    }
}
