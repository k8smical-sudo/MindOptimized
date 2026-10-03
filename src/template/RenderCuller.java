package template;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.Gl;
import arc.graphics.gl.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.storage.*;

import java.lang.reflect.*;
import java.util.*;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * MindOptimized — RenderCuller v2.1
 *
 * ═══ Sistema 1: CULLING DE BLOQUES ════════════════════════════════════════
 * Intercepta BlockRenderer.tileview entre processBlocks() y drawBlocks()
 * reemplazándola por reflexión.  drawBlocks() solo ve la lista filtrada;
 * processBlocks() siempre ve la lista completa (la restauramos en postDraw).
 *
 *  • Niebla        – edificios wasVisible bajo niebla opaca → 0 vértices.
 *  • LOD icono     – cámara lejos (≤N px/tile) → 1 quad con fullIcon.
 *                    Tamaño del quad = region.width * Draw.scl * block.size
 *                    → proporciones correctas por bloque.
 *  • LOD sólido    – cámara muy lejos (≤M px/tile) → mapColor como rect.
 *  • Enmallado voraz – casillas contiguas del mismo color → 1 rect en GPU.
 *
 * ═══ Sistema 2: LOD DE UNIDADES ══════════════════════════════════════════
 * ModTemplate sustituye el lambda de Groups.draw.draw() por uno propio.
 * drawUnit() decide por unidad:
 *  • Sólido: unidad < N px → 1 Fill.rect del color del equipo.
 *  • Icono : unidad < M px → 1 Draw.rect con type.region (hitSize × 2).
 *  La textura se conserva a cualquier zoom; solo se reduce el nº de quads.
 *
 * ═══ Sistema 3: SLEEP DE FÁBRICAS ════════════════════════════════════════
 * Fábricas (GenericCrafter, Drill, Separator) fuera de la cámara:
 *  – Se fuerzan a dormir escribiendo sleepTime > timeToSleep por reflexión
 *    y llamando a building.sleep() (que las elimina de Groups si procede).
 *  – Cada (sleepPeriod) ticks reciben 1 tick de catch-up con
 *    applyBoost(period, 2f) para conservar la producción media.
 *  – Al volver a vista: noSleep() las despierta normalmente.
 *  – Se escanea un bloque del grupo por frame (< 1 ms de coste JS).
 *
 * ═══ Sistema 4: SCISSOR DE CÁMARA (FrameBuffer) ══════════════════════════
 * El mundo se renderiza en un FB de tamaño reducido (scissorW% × scissorH%).
 * En postDraw la pantalla se limpia de negro completo y el FB se vuelca
 * centrado (blit). La UI de Arc corre después de postDraw en su propio
 * listener y no pasa por el FB → barras negras limpias, sin ojo de araña.
 * La cámara se reduce proporcionalmente → el zoom y los tiles por pantalla
 * quedan igual, solo cambia el área de cobertura visible.
 */
public class RenderCuller{

    // ── Claves de settings ────────────────────────────────────────────
    public static final String
        K_ON       = "mo-on",
        K_FOG      = "mo-fog",
        K_ICON     = "mo-lod-icon",
        K_SOLID    = "mo-lod-solid",
        K_MERGE    = "mo-merge",
        K_UICON    = "mo-unit-icon",
        K_USOLID   = "mo-unit-solid",
        K_SLEEP    = "mo-sleep",
        K_SLEEP_HZ = "mo-sleep-hz",
        K_SCISSOR  = "mo-scissor",
        K_SCIS_W   = "mo-scissor-w",
        K_SCIS_H   = "mo-scissor-h",
        K_STATS    = "mo-stats";

    private static final int MAX_GRID            = 1 << 21;   // 8 MB máx de rejilla
    private static final int POLL_MASK           = 15;         // re-leer settings cada 16 frames
    private static final long SLEEP_SCAN_NS      = 800_000L;  // 0.8 ms máx de escaneo por frame
    private static final int  SLEEP_SCAN_TICKS   = 8;         // escanear cada N ticks

    // ── Reflexión ─────────────────────────────────────────────────────
    private Field fTileview, fSleepTime, fTimeScale, fTimeScaleDuration, fSleeping;
    private boolean bindFailed;

    // ── Culling de bloques ────────────────────────────────────────────
    private boolean swapped;
    private Seq<Tile>   originalView;
    private final Seq<Tile>   scratch      = new Seq<>(false, 2048, Tile.class);
    private final Seq<Tile>   solidTiles   = new Seq<>(false, 2048, Tile.class);
    private final IntSeq      solidColors  = new IntSeq();
    private int[] grid = new int[0];

    // ── Scissor con FrameBuffer (ver beginScissor/endScissor) ────────────────
    private final FrameBuffer scissorFB = new FrameBuffer();
    private float savedCamW, savedCamH;
    private boolean scissorFBActive;

    // ── Sleep de fábricas ─────────────────────────────────────────────
    @SuppressWarnings({"rawtypes","unchecked"})
    private static final Class[] CRAFTER_TYPES = initCrafterTypes();

    @SuppressWarnings({"rawtypes","unchecked"})
    private static Class[] initCrafterTypes(){
        String[] names = {
            "mindustry.world.blocks.production.GenericCrafter",
            "mindustry.world.blocks.production.Drill",
            "mindustry.world.blocks.production.Separator"
        };
        java.util.List<Class<?>> list = new java.util.ArrayList<>();
        for(String n : names){
            try{ list.add(Class.forName(n)); }catch(ClassNotFoundException ignored){}
        }
        return list.toArray(new Class[0]);
    }

    private static class SleepEntry{
        Building b; int id, slot; boolean dead;
        SleepEntry(Building b, int period){ this.b = b; this.id = b.id; this.slot = b.id % Math.max(1,period); }
    }

    private final IntMap<SleepEntry> sleepMap = new IntMap<>();
    private final Seq<Building>      toWake   = new Seq<>(false, 64, Building.class);
    private int scanCursor, sleepTick, sleepPeriod = 6;
    private boolean sleepOn;

    // ── Settings cacheados ────────────────────────────────────────────
    private boolean on, cullFog, merge, scissorOn, statsOn;
    private int iconPx, solidPx, uIconPx, uSolidPx, scissorW, scissorH;
    private int poll;

    // ── Estadísticas ──────────────────────────────────────────────────
    private int sIn, sFog, sIcon, sSolidT, sSolidQ, sKept, sUI, sUS, frames;
    private long nanos, wNanos, wStart;

    // ─────────────────────────────────────────────────────────────────
    public RenderCuller(){
        Events.run(Trigger.preDraw,  this::onPreDraw);
        Events.run(Trigger.drawOver, this::applyBlockCull);
        Events.run(Trigger.postDraw, this::onPostDraw);
        Events.run(Trigger.update,   this::onUpdate);
        Events.on(WorldLoadEvent.class, e -> resetSleep());
    }

    // ════════════════════════════════════════════════════════════════
    //  SETTINGS
    // ════════════════════════════════════════════════════════════════
    private void readSettings(){
        on        = settings.getBool(K_ON,   true);
        cullFog   = settings.getBool(K_FOG,  true);
        iconPx    = settings.getInt (K_ICON,  14);
        solidPx   = settings.getInt (K_SOLID,  6);
        merge     = settings.getBool(K_MERGE, true);
        uIconPx   = settings.getInt (K_UICON,  12);
        uSolidPx  = settings.getInt (K_USOLID,  5);
        scissorOn = settings.getBool(K_SCISSOR, false);
        scissorW  = Math.max(20, Math.min(100, settings.getInt(K_SCIS_W, 100)));
        scissorH  = Math.max(20, Math.min(100, settings.getInt(K_SCIS_H, 100)));
        statsOn   = settings.getBool(K_STATS, true);

        boolean newSleep = settings.getBool(K_SLEEP, true) && !net.client();
        if(!newSleep && sleepOn) releaseAllSleep();
        sleepOn = newSleep;
        int hz = Math.max(1, Math.min(60, settings.getInt(K_SLEEP_HZ, 10)));
        int np = hz >= 60 ? 1 : Math.max(2, Math.round(60f / hz));
        if(np != sleepPeriod){ sleepPeriod = np; if(np == 1) releaseAllSleep(); }
    }

    // ════════════════════════════════════════════════════════════════
    //  REFLEXIÓN
    // ════════════════════════════════════════════════════════════════
    private boolean bind(){
        if(fTileview != null) return true;
        if(bindFailed) return false;
        try{
            // tileview está en la clase concreta de BlockRenderer
            for(Field f : renderer.blocks.getClass().getDeclaredFields()){
                if(f.getName().equals("tileview")){
                    fTileview = f; fTileview.setAccessible(true); break;
                }
            }
            if(fTileview == null){
                // Si MindustryX lo movió a la superclase
                for(Field f : renderer.blocks.getClass().getSuperclass().getDeclaredFields()){
                    if(f.getName().equals("tileview")){
                        fTileview = f; fTileview.setAccessible(true); break;
                    }
                }
            }
            if(fTileview == null) throw new NoSuchFieldException("tileview no encontrado");

            fSleepTime         = Building.class.getDeclaredField("sleepTime");
            fTimeScale         = Building.class.getDeclaredField("timeScale");
            fTimeScaleDuration = Building.class.getDeclaredField("timeScaleDuration");
            fSleeping          = Building.class.getDeclaredField("sleeping");
            fSleepTime.setAccessible(true);
            fTimeScale.setAccessible(true);
            fTimeScaleDuration.setAccessible(true);
            fSleeping.setAccessible(true);
            Log.info("[MO] reflexión OK – tileview: " + fTileview.getDeclaringClass().getSimpleName());
            return true;
        }catch(Throwable t){
            bindFailed = true;
            Log.err("[MO] bind falló: " + t);
            Log.info("[MO] campos disponibles en BlockRenderer:");
            for(Field f : renderer.blocks.getClass().getDeclaredFields())
                Log.info("  " + f.getType().getSimpleName() + " " + f.getName());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  EVENTOS PRINCIPALES
    // ════════════════════════════════════════════════════════════════
    private void onPreDraw(){
        restoreView();
        wStart = Time.nanos();
        if(scissorOn && state.isGame()) beginScissor();
    }

    private void onPostDraw(){
        restoreView();
        wNanos += Time.nanos() - wStart;
        if(scissorOn) endScissor();
    }

    private void onUpdate(){
        if((poll++ & POLL_MASK) == 0) readSettings();
        if(sleepOn && state.isGame() && !net.client()) tickSleep();
    }

    // ════════════════════════════════════════════════════════════════
    //  SISTEMA 1: CULLING DE BLOQUES
    // ════════════════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    private void applyBlockCull(){
        if(!on || headless || !state.isGame() || !bind()) return;
        long t0 = Time.nanos();
        try{
            Seq<Tile> src = (Seq<Tile>)fTileview.get(renderer.blocks);
            if(src == null || src.size == 0) return;

            Team pteam   = player.team();
            boolean fogOn = cullFog && state.rules.fog;
            // px por tile (1 tile = tilesize=8 unidades de mundo)
            float ppt    = (float)graphics.getWidth() / camera.width;
            boolean doSolid = solidPx > 0 && ppt <= solidPx;
            boolean doIcon  = !doSolid && iconPx > 0 && ppt <= iconPx;

            scratch.clear(); solidTiles.clear(); solidColors.clear();
            int fog = 0, icon = 0;

            for(int i = 0; i < src.size; i++){
                Tile tile      = src.items[i];
                Block block    = tile.block();
                Building build = tile.build;

                if(block == Blocks.air){ scratch.add(tile); continue; }

                // ── niebla ──────────────────────────────────────────
                if(fogOn && build != null && build.inFogTo(pteam)){ fog++; continue; }

                // ── LOD solo si ya es wasVisible (primer frame: vanilla) ──
                if((doSolid || doIcon) && blockEligible(block)
                        && (build == null || build.wasVisible)){
                    if(doSolid){
                        Color c = Tmp.c1.set(block.mapColor);
                        if(build != null && build.team != pteam)
                            c.lerp(build.team.color, 0.45f);
                        c.a = 1f;
                        solidTiles.add(tile); solidColors.add(c.rgba());
                    }else{
                        // icono: tamaño = ancho de región × scl × tamaño del bloque
                        // → sprite proporcional independientemente del bloque
                        float scl = Draw.scl * block.size;
                        float w   = block.fullIcon.width  * scl;
                        float h   = block.fullIcon.height * scl;
                        Draw.z(Layer.block);
                        Draw.rect(block.fullIcon, tile.drawx(), tile.drawy(),
                                  w, h, build != null ? build.drawrot() : 0f);
                        Draw.reset();
                        icon++;
                    }
                    continue;
                }
                scratch.add(tile);
            }

            int quads = solidTiles.size > 0 ? emitSolid() : 0;
            sIn += src.size; sFog += fog; sIcon += icon;
            sSolidT += solidTiles.size; sSolidQ += quads; sKept += scratch.size;

            originalView = src;
            fTileview.set(renderer.blocks, scratch);
            swapped = true;
        }catch(Throwable t){
            restoreView();
            bindFailed = true;
            Log.err("[MO] applyBlockCull falló: " + t);
        }
        nanos += Time.nanos() - t0;
        if(++frames >= 600) flushStats();
    }

    @SuppressWarnings("unchecked")
    private void restoreView(){
        if(!swapped) return;
        swapped = false;
        try{ fTileview.set(renderer.blocks, originalView); }
        catch(Throwable t){ Log.err("[MO] restoreView: " + t); bindFailed = true; }
        originalView = null;
    }

    private static boolean blockEligible(Block b){
        return !(b instanceof ConstructBlock)
            && !(b instanceof LogicDisplay)
            && !(b instanceof CoreBlock)
            && b.fullIcon != null && b.fullIcon.found();
    }

    // ── Sólido + enmallado ────────────────────────────────────────────
    private int emitSolid(){
        int n = solidTiles.size;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0;
        for(int i = 0; i < n; i++){
            Tile t = solidTiles.items[i];
            if(t.block().size != 1) continue;
            if(t.x < minX) minX = t.x; if(t.x > maxX) maxX = t.x;
            if(t.y < minY) minY = t.y; if(t.y > maxY) maxY = t.y;
        }
        int w = maxX - minX + 1, h = maxY - minY + 1;
        boolean useGrid = merge && maxX >= minX && (long)w * h <= MAX_GRID;
        if(useGrid){
            if(grid.length < w * h) grid = new int[w * h];
            else Arrays.fill(grid, 0, w * h, 0);
        }
        int quads = 0;
        Draw.z(Layer.block);
        for(int i = 0; i < n; i++){
            Tile t = solidTiles.items[i]; int c = solidColors.items[i];
            if(useGrid && t.block().size == 1){
                grid[(t.y - minY) * w + (t.x - minX)] = c;
            }else{
                int s = t.block().size * tilesize;
                Draw.color(Tmp.c2.set(c));
                Fill.rect(t.drawx(), t.drawy(), s, s);
                quads++;
            }
        }
        if(useGrid){
            final int fx = minX, fy = minY;
            quads += mergeGrid(grid, w, h, (gx, gy, rw, rh, c) -> {
                Draw.color(Tmp.c2.set(c));
                Fill.rect((fx + gx + (rw - 1) / 2f) * tilesize,
                          (fy + gy + (rh - 1) / 2f) * tilesize,
                          rw * tilesize, rh * tilesize);
            });
        }
        Draw.reset();
        return quads;
    }

    // ════════════════════════════════════════════════════════════════
    //  SISTEMA 2: LOD DE UNIDADES
    //  Llamado por ModTemplate en lugar de unit.draw() cuando proceda.
    // ════════════════════════════════════════════════════════════════
    public boolean drawUnit(Unit unit){
        if(!on || unit.dead || unit.inFogTo(player.team())) return unit.inFogTo(player.team());

        // px en pantalla que ocupa hitSize (radio → diámetro)
        float ppt    = (float)graphics.getWidth() / camera.width;
        float unitPx = unit.hitSize * 2f * ppt / tilesize;

        if(uSolidPx > 0 && unitPx <= uSolidPx){
            // 1 quad de color de equipo, tamaño hitSize × 2
            float sz = unit.hitSize * 2f;
            Draw.z(unit.type.flying ? unit.type.flyingLayer : unit.type.groundLayer);
            Draw.color(unit.team.color);
            Fill.rect(unit.x, unit.y, sz, sz);
            Draw.reset();
            sUS++;
            return true;
        }
        if(uIconPx > 0 && unitPx <= uIconPx){
            TextureRegion r = unit.type.region != null && unit.type.region.found()
                ? unit.type.region : unit.type.fullIcon;
            if(r != null && r.found()){
                float sz = unit.hitSize * 2f;
                Draw.z(unit.type.flying ? unit.type.flyingLayer : unit.type.groundLayer);
                // tinte de equipo semitransparente: se ve el sprite pero el color indica bando
                Draw.color(unit.team.color, 0.35f);
                Draw.rect(r, unit.x, unit.y, sz, sz, unit.rotation - 90f);
                Draw.color();
                Draw.reset();
                sUI++;
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  SISTEMA 3: SLEEP DE FÁBRICAS
    // ════════════════════════════════════════════════════════════════
    @SuppressWarnings({"rawtypes","unchecked"})
    private boolean isCrafter(Block b){
        for(Class c : CRAFTER_TYPES) if(c.isInstance(b)) return true;
        return false;
    }

    private boolean offScreen(Building b){
        float margin = b.block.size * tilesize + tilesize * 2f;
        return Math.abs(b.x - camera.position.x) > camera.width  * 0.5f + margin
            || Math.abs(b.y - camera.position.y) > camera.height * 0.5f + margin;
    }

    private void forceSleep(Building b){
        try{
            fSleepTime.setFloat(b, Building.timeToSleep + 1f);
            b.sleep();
        }catch(Throwable ignored){}
    }

    private void forceWake(Building b, int period){
        b.noSleep();
        if(period > 1){
            try{
                fTimeScale.setFloat(b, period);
                fTimeScaleDuration.setFloat(b, 2f);
            }catch(Throwable ignored){}
        }
    }

    private boolean isSleeping(Building b){
        try{ return fSleeping.getBoolean(b); }catch(Throwable e){ return false; }
    }

    private void tickSleep(){
        if(fSleeping == null) return;
        sleepTick++;
        int P = sleepPeriod;

        // ── despertar el bucket del tick actual ──────────────────────
        toWake.clear();
        for(IntMap.Entry<SleepEntry> e : sleepMap.entries()){
            SleepEntry se = e.value;
            if(!se.b.isAdded()){ se.dead = true; continue; }
            if((sleepTick % P) == se.slot) toWake.add(se.b);
        }
        for(int i = 0; i < toWake.size; i++) forceWake(toWake.items[i], P);

        // ── escanear para dormir los que salieron de vista ───────────
        if((sleepTick % SLEEP_SCAN_TICKS) != 0) return;
        long t0 = Time.nanos();
        IntSeq toRemove = null;

        int n = Groups.build.size();
        if(n == 0){ scanCursor = 0; return; }
        if(scanCursor >= n) scanCursor = 0;
        int checked = 0;

        while(checked < n && Time.nanos() - t0 < SLEEP_SCAN_NS){
            Building b = Groups.build.index(scanCursor);
            scanCursor = (scanCursor + 1) % n;
            if(b == null || !b.isAdded()){ checked++; continue; }

            int id = b.id;
            if(sleepMap.containsKey(id)){
                // ¿volvió a pantalla?
                SleepEntry se = sleepMap.get(id);
                if(se.dead || !b.isAdded()){
                    if(toRemove == null) toRemove = new IntSeq();
                    toRemove.add(id);
                }else if(!offScreen(b)){
                    if(toRemove == null) toRemove = new IntSeq();
                    toRemove.add(id);
                    forceWake(b, 1);
                }
            }else if(isCrafter(b.block) && !(b.block instanceof CoreBlock)
                    && b.enabled && !isSleeping(b) && offScreen(b)){
                sleepMap.put(id, new SleepEntry(b, P));
                forceSleep(b);
            }
            checked++;
        }
        if(toRemove != null){
            for(int i = 0; i < toRemove.size; i++) sleepMap.remove(toRemove.items[i]);
        }
    }

    private void releaseAllSleep(){
        for(IntMap.Entry<SleepEntry> e : sleepMap.entries()){
            try{ if(e.value.b.isAdded()) forceWake(e.value.b, 1); }catch(Throwable ignored){}
        }
        sleepMap.clear(); scanCursor = 0;
    }

    private void resetSleep(){ releaseAllSleep(); sleepTick = 0; }

    // ════════════════════════════════════════════════════════════════
    //  SISTEMA 4: SCISSOR CON FRAMEBUFFER (patrón idéntico al Pixelator)
    // ════════════════════════════════════════════════════════════════
    //  preDraw  → fb.begin(Color.black) + reducir camera.width/height
    //  drawWorld corre dentro del FB (solo el % configurado)
    //  postDraw → fb.end() + graphics.clear(black) + fb.blit centrado
    //  La UI (scene.draw) corre DESPUÉS de postDraw en su propio listener
    //  y NO está en el FB → barras negras limpias, sin ojo de araña.

    private void beginScissor(){
        if(scissorW >= 100 && scissorH >= 100) return;
        int sw = graphics.getWidth(), sh = graphics.getHeight();
        int vw = Math.max(2, sw * scissorW / 100);
        int vh = Math.max(2, sh * scissorH / 100);
        scissorFB.resizeCheck(vw, vh);
        // Reducir la cámara en proporción para que el mundo se dibuje
        // en la misma escala pero en menos píxeles.
        savedCamW = camera.width;
        savedCamH = camera.height;
        camera.width  = savedCamW  * scissorW / 100f;
        camera.height = savedCamH  * scissorH / 100f;
        camera.update();
        scissorFB.begin(Color.black);
        scissorFBActive = true;
    }

    private void endScissor(){
        if(!scissorFBActive) return;
        scissorFBActive = false;
        scissorFB.end();
        // Restaurar cámara antes de dibujar el blit
        camera.width  = savedCamW;
        camera.height = savedCamH;
        camera.update();
        // Limpiar la pantalla completa de negro
        Gl.clearColor(0f, 0f, 0f, 1f);
        Gl.clear(Gl.colorBufferBit);
        // Volcar el FB centrado usando screenspace (sin blending para evitar transparencia)
        Blending.disabled.apply();
        Draw.blit(scissorFB, Shaders.screenspace);
        Blending.normal.apply();
    }

    // ════════════════════════════════════════════════════════════════
    //  ENMALLADO VORAZ (helper estático reutilizable y testeable)
    // ════════════════════════════════════════════════════════════════
    public interface RectSink{ void rect(int x, int y, int w, int h, int color); }

    public static int mergeGrid(int[] grid, int w, int h, RectSink sink){
        int quads = 0;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                int c = grid[y * w + x]; if(c == 0) continue;
                int rw = 1;
                while(x + rw < w && grid[y * w + x + rw] == c) rw++;
                int rh = 1; boolean ok = true;
                while(ok && y + rh < h){
                    int row = (y + rh) * w + x;
                    for(int k = 0; k < rw; k++) if(grid[row + k] != c){ ok = false; break; }
                    if(ok) rh++;
                }
                for(int yy = 0; yy < rh; yy++){
                    int row = (y + yy) * w + x;
                    for(int k = 0; k < rw; k++) grid[row + k] = 0;
                }
                sink.rect(x, y, rw, rh, c); quads++;
            }
        }
        return quads;
    }

    // ════════════════════════════════════════════════════════════════
    //  ESTADÍSTICAS
    // ════════════════════════════════════════════════════════════════
    private void flushStats(){
        if(statsOn && frames > 0){
            Log.info(String.format(
                "[MO] bloques/f: total=%d fog=%d icon=%d solid=%d->%dq kept=%d" +
                " | unidades: icon=%d solid=%d | sleep=%d" +
                " | MO %.3fms | mundo %.2fms",
                sIn/frames, sFog/frames, sIcon/frames, sSolidT/frames, sSolidQ/frames, sKept/frames,
                sUI/frames, sUS/frames, sleepMap.size,
                nanos/(double)frames/1e6, wNanos/(double)frames/1e6));
        }
        sIn=sFog=sIcon=sSolidT=sSolidQ=sKept=sUI=sUS=frames=0;
        nanos=wNanos=0;
    }
}
