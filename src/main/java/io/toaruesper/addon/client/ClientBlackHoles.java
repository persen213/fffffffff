package io.toaruesper.addon.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import io.toaruesper.addon.ToaruEsper;
import io.toaruesper.addon.net.BlackHoleEnd;
import io.toaruesper.addon.net.BlackHoleStart;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Draws the Black Hole. Client only. Everything here is plain 3D geometry drawn directly into the world:
 * a black sphere (the core), two spinning glowing discs, rings that flow inward, and an expanding shockwave
 * shell when it collapses. Nothing here spawns Minecraft particles, and no Minecraft entities are created.
 *
 * Speed protections: at most MAX_ACTIVE holes at once, holes farther than MAX_DISTANCE or outside the camera view are
 * skipped, far holes use fewer segments, the sphere shape is built once, and finished holes remove themselves
 * (also if the "ended" message never arrives).
 */
@Mod.EventBusSubscriber(modid = ToaruEsper.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientBlackHoles {
    private static final int MAX_ACTIVE = 6;
    private static final double MAX_DISTANCE = 160.0;
    private static final int GROW_TICKS = 20;
    private static final int COLLAPSE_TICKS = 14;
    private static final int FADE_TICKS = 10;
    private static final int LEASE_EXTRA_TICKS = 40;
    private static final float CORE_RADIUS = 1.0f;

    private static final List<Hole> HOLES = new ArrayList<>();

    private static final class Hole {
        final int id;
        final ResourceKey<Level> dimension;
        final Vec3 center;
        final float radius;
        final int duration;
        final long startTick;
        long endTick = -1;
        boolean collapsed;

        Hole(int id, ResourceKey<Level> dimension, Vec3 center, float radius, int duration, long startTick) {
            this.id = id;
            this.dimension = dimension;
            this.center = center;
            this.radius = radius;
            this.duration = duration;
            this.startTick = startTick;
        }
    }

    private ClientBlackHoles() { }

    // ---------------------------------------------------------------- messages from the server

    public static void start(BlackHoleStart msg) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        HOLES.removeIf(h -> h.id == msg.id());
        if (HOLES.size() >= MAX_ACTIVE) return;
        HOLES.add(new Hole(msg.id(), level.dimension(), new Vec3(msg.x(), msg.y(), msg.z()), msg.radius(), msg.duration(), level.getGameTime()));
    }

    public static void end(BlackHoleEnd msg) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        for (var h : HOLES) {
            if (h.id == msg.id() && h.endTick < 0) {
                h.endTick = level.getGameTime();
                h.collapsed = msg.collapsed();
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || HOLES.isEmpty()) return;
        var level = Minecraft.getInstance().level;
        if (level == null) {
            HOLES.clear();
            return;
        }
        long now = level.getGameTime();
        HOLES.removeIf(h -> h.endTick >= 0
                ? now - h.endTick > (h.collapsed ? COLLAPSE_TICKS : FADE_TICKS)
                : now - h.startTick > h.duration + LEASE_EXTRA_TICKS);
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || HOLES.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        double time = level.getGameTime() + event.getPartialTick();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        try {
            for (var h : HOLES) {
                if (!h.dimension.equals(level.dimension())) continue;
                double dist = h.center.distanceTo(cam);
                if (dist > MAX_DISTANCE) continue;
                if (!event.getFrustum().isVisible(new AABB(h.center, h.center).inflate(h.radius + 2.0))) continue;
                int step = dist > 110 ? 4 : dist > 48 ? 2 : 1;
                draw(pose, h, cam, dist, time, step);
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static void draw(PoseStack pose, Hole h, Vec3 cam, double camDist, double time, int step) {
        double t = time - h.startTick;
        float grow = smooth((float) (t / GROW_TICKS));
        float field = grow;          // how visible the pull field (discs and rings) is
        float coreR = CORE_RADIUS * grow;
        float shock = -1f;           // below 0 = no shockwave
        if (h.endTick >= 0) {
            double te = time - h.endTick;
            if (h.collapsed) {
                field = (float) Math.max(0.0, 1.0 - te / 3.0);
                coreR *= field;
                shock = (float) Math.min(1.0, te / COLLAPSE_TICKS);
            } else {
                field *= (float) Math.max(0.0, 1.0 - te / FADE_TICKS);
                coreR *= field;
            }
        }

        BufferBuilder b = Tesselator.getInstance().getBuilder();
        pose.pushPose();
        pose.translate(h.center.x - cam.x, h.center.y - cam.y, h.center.z - cam.z);
        try {
            // 1. The core: solid black. Skipped when the camera is inside it, so the screen does not go fully black.
            if (coreR > 0.02f && camDist > coreR + 0.3) {
                RenderSystem.depthMask(true);
                RenderSystem.defaultBlendFunc();
                begin(b);
                sphere(b, pose.last().pose(), coreR, 0.01f, 0.0f, 0.03f, 1f);
                flush(b);
            }

            // Everything else glows (adds light) and does not write depth.
            RenderSystem.depthMask(false);
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

            if (coreR > 0.02f && step == 1) {
                begin(b);
                sphere(b, pose.last().pose(), coreR * 1.08f, 0.55f, 0.35f, 1.0f, 0.22f * field);
                flush(b);
            }
            if (field > 0.01f) {
                float inner = Math.max(coreR, 0.3f) * 1.5f;
                float outer = Math.max(coreR, 0.3f) * 4.2f;
                float phase = (float) ((time * 0.35) % (2.0 * Math.PI));
                disc(pose, b, 72f, (float) (time * 0.12), inner, outer, 0.9f * field, phase, step);
                disc(pose, b, -35f, (float) (-time * 0.08), inner, outer * 0.85f, 0.7f * field, -phase, step);
                inflowRings(pose, b, h.radius, coreR, field, time, step);
            }
            if (shock >= 0f) shockwave(pose, b, h.radius, shock, step);
        } finally {
            pose.popPose();
        }
    }

    // ---------------------------------------------------------------- shapes

    private static final int SEGS = 64;
    private static final float[] COS = new float[SEGS];
    private static final float[] SIN = new float[SEGS];
    private static final float[] ANGLE = new float[SEGS];

    static {
        for (int i = 0; i < SEGS; i++) {
            ANGLE[i] = Mth.TWO_PI * i / SEGS;
            COS[i] = Mth.cos(ANGLE[i]);
            SIN[i] = Mth.sin(ANGLE[i]);
        }
    }

    private static final int STACKS = 12;
    private static final int SLICES = 20;
    /** A unit sphere made of quads, built once. 12 numbers (4 corners x xyz) per quad. */
    private static final float[] SPHERE = buildSphere();

    private static float[] buildSphere() {
        float[] q = new float[STACKS * SLICES * 12];
        int n = 0;
        for (int i = 0; i < STACKS; i++) {
            float p0 = Mth.PI * i / STACKS;
            float p1 = Mth.PI * (i + 1) / STACKS;
            for (int j = 0; j < SLICES; j++) {
                float t0 = Mth.TWO_PI * j / SLICES;
                float t1 = Mth.TWO_PI * (j + 1) / SLICES;
                n = corner(q, n, p0, t0);
                n = corner(q, n, p1, t0);
                n = corner(q, n, p1, t1);
                n = corner(q, n, p0, t1);
            }
        }
        return q;
    }

    private static int corner(float[] q, int n, float phi, float theta) {
        q[n++] = Mth.sin(phi) * Mth.cos(theta);
        q[n++] = Mth.cos(phi);
        q[n++] = Mth.sin(phi) * Mth.sin(theta);
        return n;
    }

    private static void begin(BufferBuilder b) {
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    }

    private static void flush(BufferBuilder b) {
        BufferUploader.drawWithShader(b.end());
    }

    private static void sphere(BufferBuilder b, Matrix4f m, float r, float red, float green, float blue, float alpha) {
        if (alpha <= 0.002f || r <= 0.001f) return;
        for (int i = 0; i < SPHERE.length; i += 3) {
            b.vertex(m, SPHERE[i] * r, SPHERE[i + 1] * r, SPHERE[i + 2] * r).color(red, green, blue, alpha).endVertex();
        }
    }

    /** A flat spinning disc, bright violet at the inside fading to clear blue at the outside, with a slow swirl in its brightness. */
    private static void disc(PoseStack pose, BufferBuilder b, float tiltDeg, float spin, float inner, float outer,
                             float alpha, float phase, int step) {
        if (alpha <= 0.002f) return;
        pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(tiltDeg));
        pose.mulPose(Axis.YP.rotation(spin));
        Matrix4f m = pose.last().pose();
        begin(b);
        for (int i = 0; i < SEGS; i += step) {
            int j = (i + step) % SEGS;
            float a0 = alpha * (0.55f + 0.45f * Mth.sin(3f * ANGLE[i] + phase));
            float a1 = alpha * (0.55f + 0.45f * Mth.sin(3f * ANGLE[j] + phase));
            b.vertex(m, COS[i] * inner, 0f, SIN[i] * inner).color(0.80f, 0.62f, 1.0f, a0).endVertex();
            b.vertex(m, COS[i] * outer, 0f, SIN[i] * outer).color(0.20f, 0.30f, 1.0f, 0f).endVertex();
            b.vertex(m, COS[j] * outer, 0f, SIN[j] * outer).color(0.20f, 0.30f, 1.0f, 0f).endVertex();
            b.vertex(m, COS[j] * inner, 0f, SIN[j] * inner).color(0.80f, 0.62f, 1.0f, a1).endVertex();
        }
        flush(b);
        pose.popPose();
    }

    /** A thin glowing ring in the flat plane: clear at both edges, brightest in the middle. */
    private static void ring(Matrix4f m, BufferBuilder b, float radius, float halfWidth, float red, float green, float blue,
                             float alpha, int step) {
        if (alpha <= 0.002f) return;
        float in = Math.max(0.01f, radius - halfWidth);
        float mid = radius;
        float out = radius + halfWidth;
        begin(b);
        for (int i = 0; i < SEGS; i += step) {
            int j = (i + step) % SEGS;
            // inner half
            b.vertex(m, COS[i] * in, 0f, SIN[i] * in).color(red, green, blue, 0f).endVertex();
            b.vertex(m, COS[i] * mid, 0f, SIN[i] * mid).color(red, green, blue, alpha).endVertex();
            b.vertex(m, COS[j] * mid, 0f, SIN[j] * mid).color(red, green, blue, alpha).endVertex();
            b.vertex(m, COS[j] * in, 0f, SIN[j] * in).color(red, green, blue, 0f).endVertex();
            // outer half
            b.vertex(m, COS[i] * mid, 0f, SIN[i] * mid).color(red, green, blue, alpha).endVertex();
            b.vertex(m, COS[i] * out, 0f, SIN[i] * out).color(red, green, blue, 0f).endVertex();
            b.vertex(m, COS[j] * out, 0f, SIN[j] * out).color(red, green, blue, 0f).endVertex();
            b.vertex(m, COS[j] * mid, 0f, SIN[j] * mid).color(red, green, blue, alpha).endVertex();
        }
        flush(b);
    }

    /** Rings that start at the edge of the pull area and shrink toward the core, showing that space is being drawn in. */
    private static void inflowRings(PoseStack pose, BufferBuilder b, float radius, float coreR, float field, double time, int step) {
        for (int k = 0; k < 3; k++) {
            float f = (float) ((time / 50.0 + k / 3.0) % 1.0);
            float r = Mth.lerp(f, radius * 0.95f, Math.max(coreR, 0.3f) * 1.8f);
            float a = Mth.sin(f * Mth.PI) * 0.32f * field;
            float halfWidth = 0.10f + 0.12f * (1f - f);
            for (int o = 0; o < 2; o++) {
                pose.pushPose();
                if (o == 1) pose.mulPose(Axis.XP.rotationDegrees(90f));
                ring(pose.last().pose(), b, r, halfWidth, 0.45f, 0.40f, 1.0f, a, step);
                pose.popPose();
            }
        }
    }

    /** The collapse: a bright flash, an expanding glowing shell and a flat ring that races outward. */
    private static void shockwave(PoseStack pose, BufferBuilder b, float radius, float u, int step) {
        float eased = 1f - (1f - u) * (1f - u) * (1f - u);
        float fade = 1f - u;
        Matrix4f m = pose.last().pose();
        begin(b);
        sphere(b, m, CORE_RADIUS * (1f + 2.5f * u), 1.0f, 0.90f, 1.0f, 0.85f * fade * fade * fade);
        flush(b);
        float shell = Mth.lerp(eased, CORE_RADIUS, radius * 1.05f);
        begin(b);
        sphere(b, m, shell, 0.62f, 0.45f, 1.0f, 0.50f * fade * fade);
        flush(b);
        ring(m, b, shell * 1.25f, 0.5f + 0.8f * u, 0.85f, 0.75f, 1.0f, 0.8f * fade, step);
    }

    private static float smooth(float x) {
        float c = Mth.clamp(x, 0f, 1f);
        return c * c * (3f - 2f * c);
    }
}
