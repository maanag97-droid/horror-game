package com.shadowward.horror;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * PHANTOM PRISM: 3D Ghost-Paper & Pyramid Hologram Ghost-Hunter
 * Engineered for Samsung Galaxy A17 5G Super AMOLED (#000000 True Black).
 * Supports both:
 *  1) 45-Degree Ghost Paper (Pepper's Ghost Sheet) Mode
 *  2) 4-Sided Hologram Pyramid Mode
 */
public class MainActivity extends Activity {

    private HologramGameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        // Maximum screen brightness for crisp Ghost Paper & Pyramid reflections
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        hideSystemUI();

        gameView = new HologramGameView(this);
        setContentView(gameView);
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (gameView != null) gameView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) gameView.pause();
    }

    public static class HologramGameView extends SurfaceView implements Runnable {

        private Thread gameThread;
        private volatile boolean running = false;
        private final SurfaceHolder holder;
        private final SharedPreferences prefs;
        private final Vibrator vibrator;
        private final Random rng = new Random();

        // Display Modes
        private static final int MODE_GHOST_PAPER = 0; // 45-deg sheet (Top 70% hologram, Bottom 30% controls)
        private static final int MODE_PYRAMID_4WAY = 1; // 4-sided center pyramid + outer corner controls
        private int displayMode = MODE_GHOST_PAPER;
        private boolean mirrorFlip = true; // Mirror vertical axis so 45-deg reflection is upright
        private float holoScale = 1.0f;    // Adjustable size calibration (0.65f .. 1.35f)

        // Game State
        private boolean gameOver = false;
        private int score = 0;
        private int bestScore = 0;
        private int wave = 1;
        private int ghostsBanished = 0;
        private float playerShieldHP = 100f;
        private float beamEnergy = 100f;
        private float novaCharge = 100f;
        private float tunnelScrollZ = 0f;
        private float novaShockwaveZ = -1f;
        private float damageFlash = 0f;

        // 3D Crosshair & Parallax Camera (-1.0 .. +1.0)
        private float aimX = 0f;
        private float aimY = 0f;
        private float camParallaxX = 0f;
        private float camParallaxY = 0f;
        private boolean firingBeam = false;
        private boolean shieldActive = false;

        // Entities in 3D Tunnel (x, y in -1.2..1.2, z in 1.0..12.0 where 1.0 is player screen plane)
        private final List<HoloGhost> ghosts = new ArrayList<>();
        private final List<SoulOrb> enemyOrbs = new ArrayList<>();
        private final List<HoloParticle> particles = new ArrayList<>();
        private float spawnTimer = 0f;

        // Touch Controls
        private int joyPointerId = -1;
        private float joyBaseX, joyBaseY, joyKnobX, joyKnobY;
        private float joyVecX = 0f, joyVecY = 0f;

        private int beamPointerId = -1;
        private int shieldPointerId = -1;

        private final RectF btnBeam = new RectF();
        private final RectF btnNova = new RectF();
        private final RectF btnShield = new RectF();
        private final RectF btnToggleMode = new RectF();
        private final RectF btnToggleMirror = new RectF();
        private final RectF btnScaleMinus = new RectF();
        private final RectF btnScalePlus = new RectF();

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tempPath = new Path();
        private final DashPathEffect dashEffect = new DashPathEffect(new float[]{18f, 14f}, 0f);
        private final HoloSoundSynth soundSynth = new HoloSoundSynth();

        public HologramGameView(Context context) {
            super(context);
            holder = getHolder();
            prefs = context.getSharedPreferences("phantom_prism_prefs", Context.MODE_PRIVATE);
            bestScore = prefs.getInt("best_score", 0);
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            resetGame();
        }

        private void resetGame() {
            gameOver = false;
            score = 0;
            wave = 1;
            ghostsBanished = 0;
            playerShieldHP = 100f;
            beamEnergy = 100f;
            novaCharge = 100f;
            aimX = 0f;
            aimY = 0f;
            ghosts.clear();
            enemyOrbs.clear();
            particles.clear();
            spawnInitialWave();
        }

        private void spawnInitialWave() {
            ghosts.add(new HoloGhost(0f, -0.1f, 8.5f, HoloGhost.TYPE_SKULL, wave));
            ghosts.add(new HoloGhost(-0.55f, 0.15f, 10.5f, HoloGhost.TYPE_WRAITH, wave));
        }

        public void resume() {
            running = true;
            soundSynth.start();
            gameThread = new Thread(this, "HoloGameLoop");
            gameThread.start();
        }

        public void pause() {
            running = false;
            soundSynth.stop();
            try {
                if (gameThread != null) gameThread.join(400);
            } catch (InterruptedException ignored) {
            }
        }

        @Override
        public void run() {
            long lastTime = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                float dt = (now - lastTime) / 1_000_000_000f;
                lastTime = now;
                if (dt > 0.05f) dt = 0.05f;

                if (holder.getSurface().isValid()) {
                    Canvas canvas = holder.lockCanvas();
                    if (canvas != null) {
                        try {
                            int sw = canvas.getWidth();
                            int sh = canvas.getHeight();
                            updateControlBounds(sw, sh);
                            if (!gameOver) {
                                updateGame(dt);
                            }
                            renderScreen(canvas, sw, sh, dt);
                        } finally {
                            holder.unlockCanvasAndPost(canvas);
                        }
                    }
                }
            }
        }

        // =====================================================================
        // UPDATE 3D HOLOGRAM GAME LOGIC
        // =====================================================================
        private void updateGame(float dt) {
            // Move 3D corridor grid toward viewer
            tunnelScrollZ += dt * (2.6f + wave * 0.25f);
            if (tunnelScrollZ >= 1.5f) tunnelScrollZ -= 1.5f;

            if (damageFlash > 0f) damageFlash = Math.max(0f, damageFlash - dt * 2.5f);

            // Update crosshair & parallax camera from left joystick
            aimX = Math.max(-0.95f, Math.min(0.95f, aimX + joyVecX * dt * 1.45f));
            aimY = Math.max(-0.75f, Math.min(0.75f, aimY + joyVecY * dt * 1.45f));
            camParallaxX += (aimX * 0.35f - camParallaxX) * dt * 6f;
            camParallaxY += (aimY * 0.35f - camParallaxY) * dt * 6f;

            // Energy & Nova recharge
            if (firingBeam && beamEnergy > 0f) {
                beamEnergy = Math.max(0f, beamEnergy - dt * 22f);
                soundSynth.setLaserActive(true);
            } else {
                firingBeam = false;
                soundSynth.setLaserActive(false);
                beamEnergy = Math.min(100f, beamEnergy + dt * 18f);
            }

            if (shieldActive && beamEnergy > 5f) {
                beamEnergy = Math.max(0f, beamEnergy - dt * 14f);
            } else {
                shieldActive = false;
            }

            novaCharge = Math.min(100f, novaCharge + dt * 6.5f);

            // Update Pulse Nova shockwave
            if (novaShockwaveZ >= 0f) {
                novaShockwaveZ += dt * 14f;
                enemyOrbs.clear();
                for (HoloGhost g : ghosts) {
                    if (Math.abs(g.z - novaShockwaveZ) < 1.2f) {
                        g.hp -= dt * 160f;
                        spawnHoloSparks(g.x, g.y, g.z, Color.rgb(0, 255, 255), 4);
                    }
                }
                if (novaShockwaveZ > 12.5f) novaShockwaveZ = -1f;
            }

            // Spawn new ghosts in 3D depth
            spawnTimer -= dt;
            int maxGhosts = Math.min(7, 2 + wave);
            if (spawnTimer <= 0f && ghosts.size() < maxGhosts) {
                spawnTimer = Math.max(1.1f, 2.8f - wave * 0.18f);
                int type = rng.nextInt(3); // 0=Skull, 1=Crimson Wraith, 2=Phase Banshee
                float gx = (rng.nextFloat() - 0.5f) * 1.3f;
                float gy = (rng.nextFloat() - 0.5f) * 0.85f;
                ghosts.add(new HoloGhost(gx, gy, 11.2f, type, wave));
            }

            // Update Ghosts
            Iterator<HoloGhost> git = ghosts.iterator();
            while (git.hasNext()) {
                HoloGhost g = git.next();
                g.phase += dt * 3.2f;
                g.z -= dt * g.speedZ;

                if (g.type == HoloGhost.TYPE_SKULL) {
                    g.x = g.baseX + (float) Math.sin(g.phase) * 0.32f;
                    g.y = g.baseY + (float) Math.cos(g.phase * 0.7f) * 0.18f;
                } else if (g.type == HoloGhost.TYPE_WRAITH) {
                    g.x = g.baseX + (float) Math.sin(g.phase * 0.6f) * 0.42f;
                    g.attackCooldown -= dt;
                    if (g.attackCooldown <= 0f && g.z > 2.8f && g.z < 9.5f) {
                        g.attackCooldown = Math.max(1.4f, 3.0f - wave * 0.12f);
                        enemyOrbs.add(new SoulOrb(g.x, g.y, g.z));
                        soundSynth.triggerEffect(HoloSoundSynth.FX_ORB);
                    }
                } else {
                    // Phase Banshee teleports side-to-side
                    g.teleportTimer -= dt;
                    if (g.teleportTimer <= 0f) {
                        g.teleportTimer = 1.6f;
                        g.baseX = (rng.nextFloat() - 0.5f) * 1.2f;
                        g.baseY = (rng.nextFloat() - 0.5f) * 0.75f;
                        g.x = g.baseX;
                        g.y = g.baseY;
                        spawnHoloSparks(g.x, g.y, g.z, Color.rgb(255, 60, 255), 8);
                    }
                }

                // Check Proton Beam Hit on Ghost
                if (firingBeam) {
                    float hitRadius = 0.26f + (12f - g.z) * 0.015f;
                    if (Math.hypot(aimX - g.x, aimY - g.y) < hitRadius) {
                        g.hp -= dt * 88f;
                        g.hitFlash = 0.15f;
                        if (rng.nextFloat() < dt * 18f) {
                            spawnHoloSparks(g.x, g.y, g.z, Color.rgb(100, 255, 255), 2);
                        }
                    }
                }
                if (g.hitFlash > 0f) g.hitFlash -= dt;

                // Ghost Banished!
                if (g.hp <= 0f) {
                    score += (g.type == HoloGhost.TYPE_WRAITH) ? 250 : 120;
                    if (score > bestScore) {
                        bestScore = score;
                        prefs.edit().putInt("best_score", bestScore).apply();
                    }
                    ghostsBanished++;
                    if (ghostsBanished % 6 == 0) wave++;
                    spawnHoloSparks(g.x, g.y, g.z, Color.rgb(80, 255, 190), 18);
                    soundSynth.triggerEffect(HoloSoundSynth.FX_BANISH);
                    vibrate(35);
                    git.remove();
                    continue;
                }

                // Ghost Reaches Player Screen Plane (z <= 1.25f)
                if (g.z <= 1.25f) {
                    if (shieldActive) {
                        // Bounce ghost back!
                        g.z = 5.5f;
                        g.hp -= 35f;
                        soundSynth.triggerEffect(HoloSoundSynth.FX_SHIELD);
                        vibrate(30);
                    } else {
                        playerShieldHP -= 22f;
                        damageFlash = 1.0f;
                        soundSynth.triggerEffect(HoloSoundSynth.FX_HURT);
                        vibrate(180);
                        git.remove();
                        if (playerShieldHP <= 0f) {
                            playerShieldHP = 0f;
                            gameOver = true;
                            soundSynth.setLaserActive(false);
                            return;
                        }
                    }
                }
            }

            // Update Incoming Enemy Soul Orbs
            Iterator<SoulOrb> oit = enemyOrbs.iterator();
            while (oit.hasNext()) {
                SoulOrb orb = oit.next();
                orb.z -= dt * 4.2f;
                // Can shoot down orbs with Proton Beam
                if (firingBeam && Math.hypot(aimX - orb.x, aimY - orb.y) < 0.22f) {
                    spawnHoloSparks(orb.x, orb.y, orb.z, Color.rgb(255, 90, 90), 8);
                    score += 25;
                    oit.remove();
                    continue;
                }
                if (orb.z <= 1.2f) {
                    if (shieldActive) {
                        soundSynth.triggerEffect(HoloSoundSynth.FX_SHIELD);
                        score += 30;
                    } else {
                        playerShieldHP -= 12f;
                        damageFlash = 0.7f;
                        soundSynth.triggerEffect(HoloSoundSynth.FX_HURT);
                        vibrate(90);
                        if (playerShieldHP <= 0f) {
                            playerShieldHP = 0f;
                            gameOver = true;
                            soundSynth.setLaserActive(false);
                            return;
                        }
                    }
                    oit.remove();
                }
            }

            // Update 3D Hologram Particles
            Iterator<HoloParticle> pit = particles.iterator();
            while (pit.hasNext()) {
                HoloParticle p = pit.next();
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.z += p.vz * dt;
                p.life -= dt;
                if (p.life <= 0f || p.z < 1.05f) pit.remove();
            }
        }

        // =====================================================================
        // RENDERING (PURE #000000 AMOLED BLACK FOR HOLOGRAM REFLECTION)
        // =====================================================================
        private void renderScreen(Canvas canvas, int sw, int sh, float dt) {
            // 100% Pure Black background so AMOLED emits zero stray light on Ghost Paper / Pyramid
            canvas.drawColor(Color.BLACK);

            if (displayMode == MODE_GHOST_PAPER) {
                renderGhostPaperLayout(canvas, sw, sh);
            } else {
                renderPyramid4WayLayout(canvas, sw, sh);
            }
        }

        private void renderGhostPaperLayout(Canvas canvas, int sw, int sh) {
            float splitY = sh * 0.68f; // Top 68% = Hologram Projection, Bottom 32% = Arcade Touch Deck
            float holoCenterX = sw * 0.5f;
            float holoCenterY = splitY * 0.5f;
            float vpHalfW = (sw * 0.42f) * holoScale;
            float vpHalfH = (splitY * 0.44f) * holoScale;

            // 1. Render Hologram Scene in Top 68%
            canvas.save();
            canvas.clipRect(0, 0, sw, splitY - 4f);
            canvas.translate(holoCenterX, holoCenterY);
            if (mirrorFlip) {
                // Flip vertically so when reflected in a 45-degree Ghost Paper sheet tilted toward the player,
                // the hologram and all HUD text appear right-side-up in mid-air!
                canvas.scale(1f, -1f);
            }
            draw3DHologramScene(canvas, vpHalfW, vpHalfH, true);
            canvas.restore();

            // 2. Glowing Placement Guide Line for Ghost Paper Sheet Edge
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setPathEffect(dashEffect);
            paint.setColor(Color.argb(150, 0, 255, 200));
            canvas.drawLine(40f, splitY, sw - 40f, splitY, paint);
            paint.setPathEffect(null);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(170, 0, 255, 200));
            paint.setTextSize(18f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("▲ PLACE 45° GHOST PAPER SHEET ALONG THIS LINE ▲", sw * 0.5f, splitY + 22f, paint);

            // 3. Render Glowing Neon Arcade Control Deck on Bottom 32% (like Demo #3)
            renderControlDeck(canvas, sw, sh, splitY);
        }

        private void renderPyramid4WayLayout(Canvas canvas, int sw, int sh) {
            float cx = sw * 0.5f;
            float cy = sh * 0.5f;
            float maxSquare = Math.min(sw, sh) * 0.46f * holoScale;

            // Faint Diagonal X Alignment Guides for placing the 4-sided pyramid in the center
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(Color.argb(75, 0, 220, 255));
            canvas.drawLine(cx - maxSquare, cy - maxSquare, cx + maxSquare, cy + maxSquare, paint);
            canvas.drawLine(cx - maxSquare, cy + maxSquare, cx + maxSquare, cy - maxSquare, paint);

            // Render 4 Symmetrical Quadrants (Top=0°, Right=90°, Bottom=180°, Left=270°) facing inward
            float quadOffset = maxSquare * 0.56f;
            float quadHalfW = maxSquare * 0.42f;
            float quadHalfH = maxSquare * 0.34f;

            for (int q = 0; q < 4; q++) {
                canvas.save();
                canvas.translate(cx, cy);
                canvas.rotate(q * 90f);
                canvas.translate(0f, -quadOffset);
                if (mirrorFlip) {
                    canvas.scale(1f, -1f);
                }
                draw3DHologramScene(canvas, quadHalfW, quadHalfH, false);
                canvas.restore();
            }

            // Render Corner Touch Controls outside the center pyramid (like Demo #1, #2, #4)
            renderControlDeck(canvas, sw, sh, sh * 0.68f);
        }

        // =====================================================================
        // CORE 3D WIREFRAME TUNNEL & SPECTRAL GHOST RENDERER
        // =====================================================================
        private void draw3DHologramScene(Canvas canvas, float halfW, float halfH, boolean showTextHUD) {
            // 1. Draw 3D Receding Neon Wireframe Corridor (Green -> Cyan gradient by depth)
            int depthRings = 8;
            float prevRx = 0f, prevRy = 0f, prevRw = 0f, prevRh = 0f;

            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < depthRings; i++) {
                float z = 1.2f + ((i + (1.5f - tunnelScrollZ) / 1.5f) * 1.35f);
                float invZ = 1.15f / z;
                float cx = -camParallaxX * (1f - invZ) * halfW * 0.8f;
                float cy = -camParallaxY * (1f - invZ) * halfH * 0.8f;
                float rw = halfW * invZ;
                float rh = halfH * invZ;

                int alpha = (int) Math.max(25, Math.min(245, 265 - z * 20f));
                int green = (int) Math.max(120, Math.min(255, 255 - i * 16));
                int blue = (int) Math.max(90, Math.min(255, 130 + i * 18));
                paint.setStrokeWidth(Math.max(1.5f, 4.5f * invZ));
                paint.setColor(Color.argb(alpha, 0, green, blue));

                canvas.drawRect(cx - rw, cy - rh, cx + rw, cy + rh, paint);

                // Connect depth corners & grid lines for 3D perspective tunnel
                if (i > 0) {
                    int cornerAlpha = (int) (alpha * 0.75f);
                    paint.setColor(Color.argb(cornerAlpha, 0, 230, 255));
                    // 4 Main corners
                    canvas.drawLine(prevRx - prevRw, prevRy - prevRh, cx - rw, cy - rh, paint);
                    canvas.drawLine(prevRx + prevRw, prevRy - prevRh, cx + rw, cy - rh, paint);
                    canvas.drawLine(prevRx - prevRw, prevRy + prevRh, cx - rw, cy + rh, paint);
                    canvas.drawLine(prevRx + prevRw, prevRy + prevRh, cx + rw, cy + rh, paint);
                    // Intermediate grid subdivisions on walls/floor/ceiling
                    for (float f = -0.5f; f <= 0.5f; f += 0.5f) {
                        canvas.drawLine(prevRx + prevRw * f, prevRy - prevRh, cx + rw * f, cy - rh, paint);
                        canvas.drawLine(prevRx + prevRw * f, prevRy + prevRh, cx + rw * f, cy + rh, paint);
                        canvas.drawLine(prevRx - prevRw, prevRy + prevRh * f, cx - rw, cy + rh * f, paint);
                        canvas.drawLine(prevRx + prevRw, prevRy + prevRh * f, cx + rw, cy + rh * f, paint);
                    }
                }
                prevRx = cx;
                prevRy = cy;
                prevRw = rw;
                prevRh = rh;
            }

            // 2. Draw Pulse Nova Shockwave Ring if active
            if (novaShockwaveZ >= 0f) {
                float invZ = 1.15f / Math.max(1.1f, novaShockwaveZ);
                float sx = -camParallaxX * (1f - invZ) * halfW * 0.8f;
                float sy = -camParallaxY * (1f - invZ) * halfH * 0.8f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(7f * invZ);
                paint.setColor(Color.rgb(255, 255, 110));
                canvas.drawRect(sx - halfW * invZ, sy - halfH * invZ, sx + halfW * invZ, sy + halfH * invZ, paint);
            }

            // 3. Draw Holographic Ghosts (Sorted back-to-front by Z)
            for (int i = ghosts.size() - 1; i >= 0; i--) {
                HoloGhost g = ghosts.get(i);
                float invZ = 1.15f / Math.max(1.05f, g.z);
                float gx = (g.x - camParallaxX * (1f - invZ)) * halfW;
                float gy = (g.y - camParallaxY * (1f - invZ)) * halfH;
                float radius = Math.min(halfH * 0.65f, halfH * 0.55f * invZ);

                if (g.type == HoloGhost.TYPE_SKULL) {
                    drawHologramSkull(canvas, gx, gy, radius, g.hitFlash > 0f, g.phase);
                } else if (g.type == HoloGhost.TYPE_WRAITH) {
                    drawHologramWraith(canvas, gx, gy, radius * 1.25f, g.hitFlash > 0f, g.phase);
                } else {
                    drawHologramBanshee(canvas, gx, gy, radius * 1.1f, g.hitFlash > 0f, g.phase);
                }
            }

            // 4. Draw Incoming Enemy Soul Orbs
            for (SoulOrb orb : enemyOrbs) {
                float invZ = 1.15f / Math.max(1.05f, orb.z);
                float ox = (orb.x - camParallaxX * (1f - invZ)) * halfW;
                float oy = (orb.y - camParallaxY * (1f - invZ)) * halfH;
                float orad = Math.max(6f, 34f * invZ);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(120, 255, 30, 70));
                canvas.drawCircle(ox, oy, orad * 1.5f, paint);
                paint.setColor(Color.rgb(255, 200, 220));
                canvas.drawCircle(ox, oy, orad * 0.6f, paint);
            }

            // 5. Draw 3D Particles
            paint.setStyle(Paint.Style.FILL);
            for (HoloParticle p : particles) {
                float invZ = 1.15f / Math.max(1.05f, p.z);
                float px = (p.x - camParallaxX * (1f - invZ)) * halfW;
                float py = (p.y - camParallaxY * (1f - invZ)) * halfH;
                paint.setColor(p.color);
                canvas.drawCircle(px, py, Math.max(2.5f, 8f * invZ), paint);
            }

            // 6. Draw 3D Laser Crosshair & Proton Beams
            float targetX = aimX * halfW * 0.72f;
            float targetY = aimY * halfH * 0.72f;

            if (firingBeam) {
                // Twin high-voltage proton beams from bottom corners into crosshair
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(7f);
                paint.setColor(Color.argb(140, 255, 50, 90));
                canvas.drawLine(-halfW * 0.75f, halfH * 0.85f, targetX, targetY, paint);
                canvas.drawLine(halfW * 0.75f, halfH * 0.85f, targetX, targetY, paint);

                paint.setStrokeWidth(2.8f);
                paint.setColor(Color.rgb(255, 240, 180));
                canvas.drawLine(-halfW * 0.75f, halfH * 0.85f, targetX, targetY, paint);
                canvas.drawLine(halfW * 0.75f, halfH * 0.85f, targetX, targetY, paint);
            }

            // Glowing Red/Cyan Laser Crosshair (like Demo #3)
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(Color.rgb(255, 35, 65));
            canvas.drawCircle(targetX, targetY, 22f, paint);
            canvas.drawLine(targetX - 38f, targetY, targetX + 38f, targetY, paint);
            canvas.drawLine(targetX, targetY - 38f, targetX, targetY + 38f, paint);

            // 7. Draw Hexagonal Energy Shield if active
            if (shieldActive) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4f);
                paint.setColor(Color.argb(190, 0, 255, 255));
                canvas.drawCircle(0f, 0f, Math.min(halfW, halfH) * 0.75f, paint);
            }

            // 8. Floating Hologram Status Text (only in Ghost Paper primary view)
            if (showTextHUD) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(22f);
                paint.setColor(Color.rgb(0, 255, 210));
                canvas.drawText("WAVE " + wave + "   |   SCORE: " + score + "   |   SHIELD: " + (int) playerShieldHP + "%", 0f, -halfH + 26f, paint);

                if (gameOver) {
                    paint.setColor(Color.rgb(255, 45, 75));
                    paint.setTextSize(42f);
                    canvas.drawText("HOLOGRAM BREACHED", 0f, -15f, paint);
                    paint.setColor(Color.rgb(0, 255, 220));
                    paint.setTextSize(24f);
                    canvas.drawText("SCORE: " + score + "  (BEST: " + bestScore + ")  •  TAP SCREEN TO REBOOT", 0f, 32f, paint);
                }
            }
        }

        // Draw Detailed Neon Wireframe/Point-Cloud Skull (matches Demo #3!)
        private void drawHologramSkull(Canvas canvas, float cx, float cy, float r, boolean hit, float phase) {
            int mainCol = hit ? Color.WHITE : Color.rgb(60, 255, 195);
            int glowCol = Color.argb(95, 0, 255, 160);

            // Outer spectral aura
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(glowCol);
            canvas.drawCircle(cx, cy, r * 1.25f, paint);

            // Cranium dome & cheekbone outline
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, r * 0.08f));
            paint.setColor(mainCol);
            RectF cranium = new RectF(cx - r * 0.8f, cy - r * 0.9f, cx + r * 0.8f, cy + r * 0.35f);
            canvas.drawArc(cranium, 160f, 220f, false, paint);

            // Wireframe mesh latitude/longitude lines across cranium
            canvas.drawArc(new RectF(cx - r * 0.45f, cy - r * 0.9f, cx + r * 0.45f, cy + r * 0.35f), 180f, 180f, false, paint);
            canvas.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.7f, paint);
            canvas.drawLine(cx - r * 0.72f, cy - r * 0.35f, cx + r * 0.72f, cy - r * 0.35f, paint);

            // Jaw & chattering teeth
            float jawDrop = (float) Math.abs(Math.sin(phase * 2.2f)) * r * 0.14f;
            RectF jaw = new RectF(cx - r * 0.48f, cy + r * 0.2f, cx + r * 0.48f, cy + r * 0.75f + jawDrop);
            canvas.drawRoundRect(jaw, r * 0.12f, r * 0.12f, paint);
            for (float tx = -0.3f; tx <= 0.31f; tx += 0.2f) {
                canvas.drawLine(cx + r * tx, cy + r * 0.2f, cx + r * tx, cy + r * 0.55f + jawDrop, paint);
            }

            // Glowing Eye Sockets
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(220, 255, 245));
            canvas.drawCircle(cx - r * 0.32f, cy - r * 0.15f, r * 0.2f, paint);
            canvas.drawCircle(cx + r * 0.32f, cy - r * 0.15f, r * 0.2f, paint);

            // Nasal cavity triangle
            tempPath.reset();
            tempPath.moveTo(cx, cy - r * 0.02f);
            tempPath.lineTo(cx - r * 0.13f, cy + r * 0.16f);
            tempPath.lineTo(cx + r * 0.13f, cy + r * 0.16f);
            tempPath.close();
            canvas.drawPath(tempPath, paint);
        }

        // Draw Cyan & Crimson Spectral Wraith Boss (matches Demo #1 and #2!)
        private void drawHologramWraith(Canvas canvas, float cx, float cy, float r, boolean hit, float phase) {
            // Crimson outer energy tendrils
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2.5f, r * 0.07f));
            paint.setColor(hit ? Color.WHITE : Color.rgb(255, 40, 85));
            for (int i = -3; i <= 3; i++) {
                float waveX = (float) Math.sin(phase + i) * r * 0.22f;
                canvas.drawLine(cx + i * r * 0.2f, cy - r * 0.2f, cx + i * r * 0.32f + waveX, cy + r * 0.95f, paint);
            }

            // Cyan Spectral Torso, Claws & Hooded Skull
            paint.setColor(hit ? Color.WHITE : Color.rgb(0, 245, 255));
            paint.setStrokeWidth(Math.max(3f, r * 0.09f));
            canvas.drawCircle(cx, cy - r * 0.45f, r * 0.32f, paint);
            // Outstretched arms with claws
            float armLift = (float) Math.sin(phase * 1.5f) * r * 0.18f;
            canvas.drawLine(cx - r * 0.25f, cy - r * 0.2f, cx - r * 0.95f, cy - r * 0.05f + armLift, paint);
            canvas.drawLine(cx + r * 0.25f, cy - r * 0.2f, cx + r * 0.95f, cy - r * 0.05f + armLift, paint);

            // Glowing Crimson Eyes
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(255, 30, 60));
            canvas.drawCircle(cx - r * 0.12f, cy - r * 0.46f, r * 0.09f, paint);
            canvas.drawCircle(cx + r * 0.12f, cy - r * 0.46f, r * 0.09f, paint);
        }

        // Draw Electric Magenta/Blue Phase Banshee
        private void drawHologramBanshee(Canvas canvas, float cx, float cy, float r, boolean hit, float phase) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2.5f, r * 0.08f));
            paint.setColor(hit ? Color.WHITE : Color.rgb(220, 75, 255));
            canvas.drawCircle(cx, cy, r * (0.75f + 0.12f * (float) Math.sin(phase * 3f)), paint);
            paint.setColor(Color.rgb(80, 220, 255));
            canvas.drawCircle(cx, cy - r * 0.15f, r * 0.45f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx - r * 0.16f, cy - r * 0.2f, r * 0.09f, paint);
            canvas.drawCircle(cx + r * 0.16f, cy - r * 0.2f, r * 0.09f, paint);
        }

        // =====================================================================
        // BOTTOM ARCADE TOUCH CONTROL DECK (OUTSIDE THE GHOST PAPER SHEET)
        // =====================================================================
        private void renderControlDeck(Canvas canvas, int sw, int sh, float deckTopY) {
            // Left Neon 3D Joystick (matches Demo #3 glowing cyan ring + mushroom stick)
            float jbx = (joyPointerId != -1) ? joyBaseX : 210f;
            float jby = (joyPointerId != -1) ? joyBaseY : sh - 135f;
            float jkx = (joyPointerId != -1) ? joyKnobX : jbx;
            float jky = (joyPointerId != -1) ? joyKnobY : jby;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(Color.rgb(0, 240, 255));
            canvas.drawCircle(jbx, jby, 86f, paint);
            paint.setStrokeWidth(2f);
            paint.setColor(Color.argb(140, 0, 255, 190));
            canvas.drawCircle(jbx, jby, 62f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(180, 0, 235, 255));
            canvas.drawCircle(jkx, jky, 38f, paint);

            // Right Glowing Arcade Action Buttons (Beam, Nova, Shield)
            drawArcadeButton(canvas, btnBeam, "FIRE BEAM", Color.rgb(255, 130, 30), firingBeam);
            drawArcadeButton(canvas, btnNova, (novaCharge >= 100f) ? "NOVA READY" : "NOVA " + (int) novaCharge + "%",
                    Color.rgb(255, 225, 45), novaCharge >= 100f);
            drawArcadeButton(canvas, btnShield, "HEX SHIELD", Color.rgb(0, 235, 255), shieldActive);

            // Center-Bottom Calibration & Mode Strip
            drawMiniPillButton(canvas, btnToggleMode,
                    displayMode == MODE_GHOST_PAPER ? "MODE: 45° GHOST PAPER" : "MODE: 4-WAY PYRAMID",
                    Color.rgb(0, 220, 180));
            drawMiniPillButton(canvas, btnToggleMirror,
                    mirrorFlip ? "REFLECT FLIP: ON" : "DIRECT SCREEN: ON",
                    Color.rgb(90, 190, 255));
            drawMiniPillButton(canvas, btnScaleMinus, "SIZE -", Color.rgb(180, 190, 210));
            drawMiniPillButton(canvas, btnScalePlus, "SIZE +", Color.rgb(180, 190, 210));
        }

        private void drawArcadeButton(Canvas canvas, RectF r, String label, int neonColor, boolean active) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(active ? 110 : 45, Color.red(neonColor), Color.green(neonColor), Color.blue(neonColor)));
            canvas.drawOval(r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(active ? 6f : 3.5f);
            paint.setColor(neonColor);
            canvas.drawOval(r, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(19f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, r.centerX(), r.centerY() + 7f, paint);
        }

        private void drawMiniPillButton(Canvas canvas, RectF r, String text, int strokeCol) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(40, 0, 200, 255));
            canvas.drawRoundRect(r, 14f, 14f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            paint.setColor(strokeCol);
            canvas.drawRoundRect(r, 14f, 14f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(17f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(text, r.centerX(), r.centerY() + 6f, paint);
        }

        private void updateControlBounds(int sw, int sh) {
            // Right Arcade Buttons on Bottom Strip
            btnBeam.set(sw - 220f, sh - 210f, sw - 70f, sh - 60f);
            btnShield.set(sw - 390f, sh - 175f, sw - 255f, sh - 45f);
            btnNova.set(sw - 365f, sh - 305f, sw - 230f, sh - 190f);

            // Center-Bottom Calibration Pills
            float cx = sw * 0.5f;
            btnToggleMode.set(cx - 220f, sh - 135f, cx - 10f, sh - 82f);
            btnToggleMirror.set(cx + 10f, sh - 135f, cx + 220f, sh - 82f);
            btnScaleMinus.set(cx - 140f, sh - 68f, cx - 15f, sh - 20f);
            btnScalePlus.set(cx + 15f, sh - 68f, cx + 140f, sh - 20f);
        }

        // =====================================================================
        // MULTI-TOUCH INPUT
        // =====================================================================
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int index = event.getActionIndex();
            int pid = event.getPointerId(index);
            float tx = event.getX(index);
            float ty = event.getY(index);

            if (gameOver) {
                if (action == MotionEvent.ACTION_DOWN) {
                    resetGame();
                }
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (btnToggleMode.contains(tx, ty)) {
                        displayMode = (displayMode == MODE_GHOST_PAPER) ? MODE_PYRAMID_4WAY : MODE_GHOST_PAPER;
                        vibrate(25);
                    } else if (btnToggleMirror.contains(tx, ty)) {
                        mirrorFlip = !mirrorFlip;
                        vibrate(25);
                    } else if (btnScaleMinus.contains(tx, ty)) {
                        holoScale = Math.max(0.65f, holoScale - 0.08f);
                        vibrate(15);
                    } else if (btnScalePlus.contains(tx, ty)) {
                        holoScale = Math.min(1.35f, holoScale + 0.08f);
                        vibrate(15);
                    } else if (btnBeam.contains(tx, ty)) {
                        beamPointerId = pid;
                        firingBeam = true;
                        vibrate(20);
                    } else if (btnShield.contains(tx, ty)) {
                        shieldPointerId = pid;
                        shieldActive = true;
                        soundSynth.triggerEffect(HoloSoundSynth.FX_SHIELD);
                        vibrate(20);
                    } else if (btnNova.contains(tx, ty)) {
                        if (novaCharge >= 100f) {
                            novaCharge = 0f;
                            novaShockwaveZ = 1.2f;
                            soundSynth.triggerEffect(HoloSoundSynth.FX_BANISH);
                            vibrate(90);
                        }
                    } else if (tx < getWidth() * 0.45f && joyPointerId == -1) {
                        joyPointerId = pid;
                        joyBaseX = tx;
                        joyBaseY = ty;
                        joyKnobX = tx;
                        joyKnobY = ty;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        if (event.getPointerId(i) == joyPointerId) {
                            float dx = event.getX(i) - joyBaseX;
                            float dy = event.getY(i) - joyBaseY;
                            float dist = (float) Math.hypot(dx, dy);
                            float maxR = 85f;
                            if (dist > maxR) {
                                dx = (dx / dist) * maxR;
                                dy = (dy / dist) * maxR;
                            }
                            joyKnobX = joyBaseX + dx;
                            joyKnobY = joyBaseY + dy;
                            joyVecX = dx / maxR;
                            joyVecY = dy / maxR;
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pid == joyPointerId) {
                        joyPointerId = -1;
                        joyVecX = 0f;
                        joyVecY = 0f;
                    } else if (pid == beamPointerId) {
                        beamPointerId = -1;
                        firingBeam = false;
                    } else if (pid == shieldPointerId) {
                        shieldPointerId = -1;
                        shieldActive = false;
                    }
                    break;
            }
            return true;
        }

        private void spawnHoloSparks(float x, float y, float z, int color, int count) {
            for (int i = 0; i < count; i++) {
                particles.add(new HoloParticle(x, y, z, color, rng));
            }
        }

        private void vibrate(long ms) {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(ms);
                }
            }
        }

        // =====================================================================
        // 3D ENTITIES & HOLOGRAM AUDIO SYNTHESIZER
        // =====================================================================
        private static class HoloGhost {
            static final int TYPE_SKULL = 0;
            static final int TYPE_WRAITH = 1;
            static final int TYPE_BANSHEE = 2;

            float x, y, z, baseX, baseY;
            final int type;
            float hp;
            final float speedZ;
            float phase = 0f;
            float hitFlash = 0f;
            float attackCooldown = 2.0f;
            float teleportTimer = 1.8f;

            HoloGhost(float x, float y, float z, int type, int wave) {
                this.x = x;
                this.y = y;
                this.baseX = x;
                this.baseY = y;
                this.z = z;
                this.type = type;
                this.hp = (type == TYPE_WRAITH) ? (95f + wave * 18f) : (55f + wave * 12f);
                this.speedZ = (type == TYPE_WRAITH ? 0.72f : 1.05f) + wave * 0.08f;
            }
        }

        private static class SoulOrb {
            final float x, y;
            float z;
            SoulOrb(float x, float y, float z) {
                this.x = x;
                this.y = y;
                this.z = z;
            }
        }

        private static class HoloParticle {
            float x, y, z, vx, vy, vz, life;
            final int color;
            HoloParticle(float x, float y, float z, int color, Random rng) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.vx = (rng.nextFloat() - 0.5f) * 1.4f;
                this.vy = (rng.nextFloat() - 0.5f) * 1.4f;
                this.vz = (rng.nextFloat() - 0.5f) * 2.0f;
                this.life = 0.35f + rng.nextFloat() * 0.35f;
                this.color = color;
            }
        }

        private static class HoloSoundSynth implements Runnable {
            static final int FX_BANISH = 1;
            static final int FX_SHIELD = 2;
            static final int FX_ORB = 3;
            static final int FX_HURT = 4;

            private Thread thread;
            private volatile boolean active = false;
            private volatile boolean laserActive = false;
            private volatile int pendingFx = 0;

            void start() {
                active = true;
                thread = new Thread(this, "HoloAudio");
                thread.start();
            }

            void stop() {
                active = false;
                try {
                    if (thread != null) thread.join(300);
                } catch (InterruptedException ignored) {
                }
            }

            void setLaserActive(boolean on) { this.laserActive = on; }
            void triggerEffect(int fx) { this.pendingFx = fx; }

            @Override
            public void run() {
                final int sampleRate = 22050;
                int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) return;

                AudioTrack track;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        track = new AudioTrack.Builder()
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_GAME)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build())
                                .setAudioFormat(new AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setSampleRate(sampleRate)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .build())
                                .setBufferSizeInBytes(minBuf)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build();
                    } else {
                        track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf, AudioTrack.MODE_STREAM);
                    }
                    track.play();
                } catch (Exception e) {
                    return;
                }

                short[] buf = new short[512];
                double phase = 0.0;
                double laserPhase = 0.0;
                int curFx = 0;
                int fxSample = 0;

                while (active) {
                    if (pendingFx != 0) {
                        curFx = pendingFx;
                        pendingFx = 0;
                        fxSample = 0;
                    }
                    for (int i = 0; i < buf.length; i++) {
                        phase += (2.0 * Math.PI * 52.0) / sampleRate;
                        double sig = Math.sin(phase) * 0.08;

                        if (laserActive) {
                            laserPhase += (2.0 * Math.PI * 195.0) / sampleRate;
                            sig += (Math.sin(laserPhase) + Math.sin(laserPhase * 2.5) * 0.5) * 0.22;
                        }

                        if (curFx != 0) {
                            double t = fxSample++ / (double) sampleRate;
                            if (curFx == FX_BANISH) {
                                if (t < 0.28) sig += Math.sin(2.0 * Math.PI * (320.0 + t * 900.0) * t) * 0.45;
                                else curFx = 0;
                            } else if (curFx == FX_SHIELD) {
                                if (t < 0.16) sig += Math.sin(2.0 * Math.PI * 660.0 * t) * 0.35;
                                else curFx = 0;
                            } else if (curFx == FX_ORB) {
                                if (t < 0.14) sig += Math.sin(2.0 * Math.PI * (480.0 - t * 1200.0) * t) * 0.3;
                                else curFx = 0;
                            } else if (curFx == FX_HURT) {
                                if (t < 0.35) sig += (Math.sin(2.0 * Math.PI * 110.0 * t) + (Math.random() - 0.5)) * 0.5;
                                else curFx = 0;
                            }
                        }
                        if (sig > 1.0) sig = 1.0;
                        if (sig < -1.0) sig = -1.0;
                        buf[i] = (short) (sig * 32767);
                    }
                    track.write(buf, 0, buf.length);
                }
                try {
                    track.stop();
                    track.release();
                } catch (Exception ignored) {
                }
            }
        }
    }
                            }
