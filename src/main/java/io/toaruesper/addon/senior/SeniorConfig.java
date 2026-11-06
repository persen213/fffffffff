package io.toaruesper.addon.senior;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Settings file: config/toaruesper-common.toml (created the first time the game starts with the add-on).
 * Changes apply the next time the game or server starts. On a server, the server's file is the one that counts.
 */
public final class SeniorConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue BH_RANGE;
    public static final ForgeConfigSpec.DoubleValue BH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue BH_DURATION_SECONDS;
    public static final ForgeConfigSpec.DoubleValue BH_PULL;
    public static final ForgeConfigSpec.DoubleValue BH_DAMAGE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue BH_DAMAGE_PER_SECOND;
    public static final ForgeConfigSpec.DoubleValue BH_FINAL_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue BH_KNOCKBACK;
    public static final ForgeConfigSpec.DoubleValue BH_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.DoubleValue BH_CP_COST;
    public static final ForgeConfigSpec.DoubleValue BH_OVERLOAD_COST;
    public static final ForgeConfigSpec.IntValue BH_MAX_TARGETS;
    public static final ForgeConfigSpec.BooleanValue BH_AFFECT_BOSSES;

    static {
        var b = new ForgeConfigSpec.Builder();
        b.comment("Black Hole (Senior). The first 'seconds' value is how long the hole stays; the cooldown starts after it ends.").push("black_hole");
        BH_RANGE = b.comment("How far away (blocks) the hole can be placed.")
                .defineInRange("range", 28.0, 5.0, 64.0);
        BH_RADIUS = b.comment("Radius (blocks) of the area that pulls things in and is hit by the final collapse.")
                .defineInRange("radius", 12.0, 3.0, 32.0);
        BH_DURATION_SECONDS = b.comment("How long the hole stays open before it collapses.")
                .defineInRange("duration_seconds", 6.0, 2.0, 30.0);
        BH_PULL = b.comment("Pull strength. Higher drags targets in faster.")
                .defineInRange("pull_strength", 0.22, 0.02, 1.0);
        BH_DAMAGE_RADIUS = b.comment("Targets this close to the centre take damage while the hole is open.")
                .defineInRange("damage_radius", 4.0, 1.0, 16.0);
        BH_DAMAGE_PER_SECOND = b.comment("Damage once per second near the centre (doubles right at the core). Scales a little with skill experience.")
                .defineInRange("damage_per_second", 4.0, 0.0, 200.0);
        BH_FINAL_DAMAGE = b.comment("Damage of the final collapse (less at the edge of the radius).")
                .defineInRange("final_damage", 24.0, 0.0, 500.0);
        BH_KNOCKBACK = b.comment("How hard survivors are thrown outward by the final collapse.")
                .defineInRange("knockback", 1.8, 0.0, 6.0);
        BH_COOLDOWN_SECONDS = b.comment("Cooldown in seconds after the hole has ended.")
                .defineInRange("cooldown_seconds", 45.0, 0.0, 1800.0);
        BH_CP_COST = b.comment("CP (Re:AcademyCraft energy) cost. A starting guess; tune it to your server's CP scale.")
                .defineInRange("cp_cost", 800.0, 0.0, 1_000_000.0);
        BH_OVERLOAD_COST = b.comment("Overload added when used.")
                .defineInRange("overload_cost", 150.0, 0.0, 1_000_000.0);
        BH_MAX_TARGETS = b.comment("At most this many creatures are pulled at once (the closest ones). Protects server speed.")
                .defineInRange("max_targets", 40, 1, 200);
        BH_AFFECT_BOSSES = b.comment("If false, the Ender Dragon and the Wither are not pulled or hurt.")
                .define("affect_bosses", false);
        b.pop();
        SPEC = b.build();
    }

    private SeniorConfig() { }
}
