package template;

import arc.Core;
import arc.Events;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.gl.FrameBuffer;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Shaders;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.logic.LogicDisplay;
import mindustry.world.blocks.storage.CoreBlock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

public class RenderCuller{
    public static final String K_ON = "mo-on";
    public static final String K_FOG = "mo-fog";
    public static final String K_ICON = "mo-lod-icon";
    public static final String K_SOLID = "mo-lod-solid";
    public static final String K_MERGE = "mo-merge";
    public static final String K_UICON = "mo-unit-icon";
    public static final String K_USOLID = "mo-unit-solid";
    public static final String K_SLEEP = "mo-sleep";
    public static final String K_SLEEP_HZ = "mo-sleep-hz";
    public static final String K_SCISSOR = "mo-scissor";
    public static final String K_SCIS_W = "mo-scissor-w";
    public static final String K_SCIS_H = "mo-scissor-h";
    public static final String K_STATS = "mo-stats";

    private static final int MAX_GRID = 0x200000;
    private static final int POLL_MASK = 15;
    private static final long SLEEP_SCAN_NS = 800000L;
    private static final int SLEEP_SCAN_TICKS = 8;

    private Field fTileview, fSleepTime, fTimeScale, fTimeScaleDuration, fSleeping;
    private boolean bindFailed;
    private boolean swapped;
    private Seq<Tile> originalView;

    private final Seq<Tile> scratch = new Seq<>(false, 2048, Tile.class);
    private final Seq<Tile> solidTiles = new Seq<>(false, 2048, Tile.class);
    private final IntSeq solidColors = new IntSeq();
    private int[] grid = new int[0];

    /** Se crea de forma perezosa en el hilo de render (nunca en el constructor ni en el init estático). */
    private FrameBuffer scissorFB;
    private boolean scissorFBFailed;
    private float savedCamW, savedCamH;
    private boolean scissorFBActive;

    private static final Class<?>[] CRAFTER_TYPES = initCrafterTypes();
    private final IntMap<SleepEntry> sleepMap = new IntMap<>();
    private final Seq<Building> toWake = new Seq<>(false, 64, Building.class);
    private int scanCursor, sleepTick, sleepPeriod = 6;
    private boolean sleepOn;

    private boolean on, cullFog, merge, scissorOn, statsOn;
    private int iconPx, solidPx, uIconPx, uSolidPx, scissorW, scissorH;
    private int poll;

    private int sIn, sFog, sIcon, sSolidT, sSolidQ, sKept, sUI, sUS, frames;
    private long nanos, wNanos, wStart;

    private static Class<?>[] initCrafterTypes(){
        String[] names = {
            "mindustry.world.blocks.production.GenericCrafter",
            "mindustry.world.blocks.production.Drill",
            "mindustry.world.blocks.production.Separator"
        };
        ArrayList<Class<?>> list = new ArrayList<>();
        for(String n : names){
            try{
                list.add(Class.forName(n));
            }catch(Throwable ignored){
                // clase ausente en esta versión: se omite
            }
        }
        return list.toArray(new Class<?>[0]);
    }

    public RenderCuller(){
        Events.run(Trigger.preDraw, this::onPreDraw);
        Events.run(Trigger.drawOver, this::applyBlockCull);
        Events.run(Trigger.postDraw, this::onPostDraw);
        Events.run(Trigger.update, this::onUpdate);
        Events.on(WorldLoadEvent.class, e -> resetSleep());
    }

    private void readSettings(){
        on = Core.settings.getBool(K_ON, true);
        cullFog = Core.settings.getBool(K_FOG, true);
        iconPx = Core.settings.getInt(K_ICON, 14);
        solidPx = Core.settings.getInt(K_SOLID, 6);
        merge = Core.settings.getBool(K_MERGE, true);
        uIconPx = Core.settings.getInt(K_UICON, 12);
        uSolidPx = Core.settings.getInt(K_USOLID, 5);
        scissorOn = Core.settings.getBool(K_SCISSOR, false);
        scissorW = Math.max(20, Math.min(100, Core.settings.getInt(K_SCIS_W, 100)));
        scissorH = Math.max(20, Math.min(100, Core.settings.getInt(K_SCIS_H, 100)));
        statsOn = Core.settings.getBool(K_STATS, true);

        boolean newSleep = Core.settings.getBool(K_SLEEP, true) && !Vars.net.client();
        if(!newSleep && sleepOn){
            releaseAllSleep();
        }
        sleepOn = newSleep;

        int hz = Math.max(1, Math.min(60, Core.settings.getInt(K_SLEEP_HZ, 10)));
        int np = hz >= 60 ? 1 : Math.max(2, Math.round(60f / hz));
        if(np != sleepPeriod){
            sleepPeriod = np;
            if(np == 1){
                releaseAllSleep();
            }
        }
    }

    private boolean bind(){
        if(fTileview != null) return true;
        if(bindFailed) return false;
        try{
            Class<?> c = Vars.renderer.blocks.getClass();
            while(c != null && fTileview == null){
                for(Field f : c.getDeclaredFields()){
                    if(f.getName().equals("tileview")){
                        fTileview = f;
                        fTileview.setAccessible(true);
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if(fTileview == null){
                throw new NoSuchFieldException("tileview no encontrado");
            }

            fSleepTime = Building.class.getDeclaredField("sleepTime");
            fTimeScale = Building.class.getDeclaredField("timeScale");
            fTimeScaleDuration = Building.class.getDeclaredField("timeScaleDuration");
            fSleeping = Building.class.getDeclaredField("sleeping");
            fSleepTime.setAccessible(true);
            fTimeScale.setAccessible(true);
            fTimeScaleDuration.setAccessible(true);
            fSleeping.setAccessible(true);

            Log.info("[MO] reflexión OK – tileview: " + fTileview.getDeclaringClass().getSimpleName());
            return true;
        }catch(Throwable t){
            bindFailed = true;
            // Si falla solo la parte de sleep, el recorte de bloques puede seguir funcionando.
            Log.err("[MO] bind falló: " + t);
            Log.info("[MO] campos disponibles en BlockRenderer:");
            for(Field f : Vars.renderer.blocks.getClass().getDeclaredFields()){
                Log.info("  " + f.getType().getSimpleName() + " " + f.getName());
            }
            return false;
        }
    }

    private void onPreDraw(){
        restoreView();
        wStart = Time.nanos();
        if(scissorOn && Vars.state.isGame()){
            beginScissor();
        }
    }

    private void onPostDraw(){
        restoreView();
        wNanos += Time.nanos() - wStart;
        if(scissorOn){
            endScissor();
        }
    }

    private void onUpdate(){
        if((poll++ & POLL_MASK) == 0){
            readSettings();
        }
        if(sleepOn && Vars.state.isGame() && !Vars.net.client()){
            tickSleep();
        }
    }

    private void applyBlockCull(){
        if(!on || Vars.headless || !Vars.state.isGame() || !bind()) return;

        long t0 = Time.nanos();
        try{
            @SuppressWarnings("unchecked")
            Seq<Tile> src = (Seq<Tile>)fTileview.get(Vars.renderer.blocks);
            if(src == null || src.size == 0) return;

            Team pteam = Vars.player.team();
            boolean fogOn = cullFog && Vars.state.rules.fog;
            float ppt = (float)Core.graphics.getWidth() / Core.camera.width;
            boolean doSolid = solidPx > 0 && ppt <= solidPx;
            boolean doIcon = !doSolid && iconPx > 0 && ppt <= iconPx;

            scratch.clear();
            solidTiles.clear();
            solidColors.clear();
            int fog = 0, icon = 0;

            for(int i = 0; i < src.size; i++){
                Tile tile = src.items[i];
                Block block = tile.block();
                Building build = tile.build;

                if(block == Blocks.air){
                    scratch.add(tile);
                    continue;
                }

                if(fogOn && build != null && build.inFogTo(pteam)){
                    fog++;
                    continue;
                }

                if((doSolid || doIcon) && blockEligible(block) && (build == null || build.wasVisible)){
                    if(doSolid){
                        Color c = Tmp.c1.set(block.mapColor);
                        if(build != null && build.team != pteam){
                            c.lerp(build.team.color, 0.45f);
                        }
                        c.a = 1f;
                        solidTiles.add(tile);
                        solidColors.add(c.rgba());
                        continue;
                    }

                    float scl = Draw.scl * block.size;
                    float w = block.fullIcon.width * scl;
                    float h = block.fullIcon.height * scl;
                    Draw.z(30f);
                    Draw.rect(block.fullIcon, tile.drawx(), tile.drawy(), w, h, build != null ? build.drawrot() : 0f);
                    Draw.reset();
                    icon++;
                    continue;
                }

                scratch.add(tile);
            }

            int quads = solidTiles.size > 0 ? emitSolid() : 0;

            sIn += src.size;
            sFog += fog;
            sIcon += icon;
            sSolidT += solidTiles.size;
            sSolidQ += quads;
            sKept += scratch.size;

            originalView = src;
            fTileview.set(Vars.renderer.blocks, scratch);
            swapped = true;
        }catch(Throwable t){
            restoreView();
            bindFailed = true;
            Log.err("[MO] applyBlockCull falló: " + t);
        }

        nanos += Time.nanos() - t0;
        if(++frames >= 600){
            flushStats();
        }
    }

    private void restoreView(){
        if(!swapped) return;
        swapped = false;
        try{
            fTileview.set(Vars.renderer.blocks, originalView);
        }catch(Throwable t){
            Log.err("[MO] restoreView: " + t);
            bindFailed = true;
        }
        originalView = null;
    }

    private static boolean blockEligible(Block b){
        return !(b instanceof ConstructBlock) && !(b instanceof LogicDisplay) && !(b instanceof CoreBlock)
            && b.fullIcon != null && b.fullIcon.found();
    }

    private int emitSolid(){
        int n = solidTiles.size;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for(int i = 0; i < n; i++){
            Tile t = solidTiles.items[i];
            if(t.block().size != 1) continue;
            if(t.x < minX) minX = t.x;
            if(t.x > maxX) maxX = t.x;
            if(t.y < minY) minY = t.y;
            if(t.y > maxY) maxY = t.y;
        }

        int w = 0, h = 0;
        boolean useGrid = false;
        if(merge && maxX >= minX && maxY >= minY){
            w = maxX - minX + 1;
            h = maxY - minY + 1;
            useGrid = (long)w * (long)h <= MAX_GRID;
        }

        if(useGrid){
            if(grid.length < w * h){
                grid = new int[w * h];
            }else{
                Arrays.fill(grid, 0, w * h, 0);
            }
        }

        int quads = 0;
        Draw.z(30f);

        for(int i = 0; i < n; i++){
            Tile t = solidTiles.items[i];
            int c = solidColors.items[i];

            if(useGrid && t.block().size == 1){
                grid[(t.y - minY) * w + (t.x - minX)] = c;
                continue;
            }

            float s = t.block().size * 8f;
            Draw.color(Tmp.c2.set(c));
            Fill.rect(t.drawx(), t.drawy(), s, s);
            quads++;
        }

        if(useGrid){
            final int fx = minX, fy = minY;
            quads += mergeGrid(grid, w, h, (gx, gy, rw, rh, c) -> {
                Draw.color(Tmp.c2.set(c));
                Fill.rect((fx + gx + (rw - 1) / 2f) * 8f, (fy + gy + (rh - 1) / 2f) * 8f, rw * 8f, rh * 8f);
            });
        }

        Draw.reset();
        return quads;
    }

    /** @return true si el RenderCuller ya dibujó la unidad (o está bajo niebla) y no hay que dibujarla completa. */
    public boolean drawUnit(Unit unit){
        if(!on || unit.dead || unit.inFogTo(Vars.player.team())){
            return unit.inFogTo(Vars.player.team());
        }

        float ppt = (float)Core.graphics.getWidth() / Core.camera.width;
        float unitPx = unit.hitSize * 2f * ppt / 8f;

        if(uSolidPx > 0 && unitPx <= uSolidPx){
            float sz = unit.hitSize * 2f;
            Draw.z(unit.type.flying ? unit.type.flyingLayer : unit.type.groundLayer);
            Draw.color(unit.team.color);
            Fill.rect(unit.x, unit.y, sz, sz);
            Draw.reset();
            sUS++;
            return true;
        }

        if(uIconPx > 0 && unitPx <= uIconPx){
            TextureRegion r = unit.type.region != null && unit.type.region.found() ? unit.type.region : unit.type.fullIcon;
            if(r != null && r.found()){
                float sz = unit.hitSize * 2f;
                Draw.z(unit.type.flying ? unit.type.flyingLayer : unit.type.groundLayer);
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

    private boolean isCrafter(Block b){
        for(Class<?> c : CRAFTER_TYPES){
            if(c.isInstance(b)) return true;
        }
        return false;
    }

    private boolean offScreen(Building b){
        float margin = b.block.size * 8f + 16f;
        return Math.abs(b.x - Core.camera.position.x) > Core.camera.width * 0.5f + margin
            || Math.abs(b.y - Core.camera.position.y) > Core.camera.height * 0.5f + margin;
    }

    private void forceSleep(Building b){
        try{
            fSleepTime.setFloat(b, 61f);
            b.sleep();
        }catch(Throwable ignored){
        }
    }

    private void forceWake(Building b, int period){
        b.noSleep();
        if(period > 1){
            try{
                fTimeScale.setFloat(b, period);
                fTimeScaleDuration.setFloat(b, 2f);
            }catch(Throwable ignored){
            }
        }
    }

    private boolean isSleeping(Building b){
        try{
            return fSleeping.getBoolean(b);
        }catch(Throwable e){
            return false;
        }
    }

    private void tickSleep(){
        // Los campos reflejados se enlazan en bind(); sin ellos no hay sleep.
        if(fSleeping == null){
            bind();
            if(fSleeping == null) return;
        }

        sleepTick++;
        int P = sleepPeriod;

        toWake.clear();
        for(IntMap.Entry<SleepEntry> e : sleepMap.entries()){
            SleepEntry se = e.value;
            if(!se.b.isAdded()){
                se.dead = true;
                continue;
            }
            if(sleepTick % P != se.slot) continue;
            toWake.add(se.b);
        }
        for(int i = 0; i < toWake.size; i++){
            forceWake(toWake.items[i], P);
        }

        if(sleepTick % SLEEP_SCAN_TICKS != 0) return;

        long t0 = Time.nanos();
        IntSeq toRemove = null;
        int n = Groups.build.size();
        if(n == 0){
            scanCursor = 0;
            return;
        }
        if(scanCursor >= n) scanCursor = 0;

        int checked = 0;
        while(checked < n && Time.nanos() - t0 < SLEEP_SCAN_NS){
            Building b = Groups.build.index(scanCursor);
            scanCursor = (scanCursor + 1) % n;
            if(b == null || !b.isAdded()){
                checked++;
                continue;
            }

            int id = b.id;
            if(sleepMap.containsKey(id)){
                SleepEntry se = sleepMap.get(id);
                if(se.dead || !b.isAdded()){
                    if(toRemove == null) toRemove = new IntSeq();
                    toRemove.add(id);
                }else if(!offScreen(b)){
                    if(toRemove == null) toRemove = new IntSeq();
                    toRemove.add(id);
                    forceWake(b, 1);
                }
            }else if(isCrafter(b.block) && !(b.block instanceof CoreBlock) && b.enabled && !isSleeping(b) && offScreen(b)){
                sleepMap.put(id, new SleepEntry(b, P));
                forceSleep(b);
            }
            checked++;
        }

        if(toRemove != null){
            for(int i = 0; i < toRemove.size; i++){
                sleepMap.remove(toRemove.items[i]);
            }
        }
    }

    private void releaseAllSleep(){
        for(IntMap.Entry<SleepEntry> e : sleepMap.entries()){
            try{
                if(!e.value.b.isAdded()) continue;
                forceWake(e.value.b, 1);
            }catch(Throwable ignored){
            }
        }
        sleepMap.clear();
        scanCursor = 0;
    }

    private void resetSleep(){
        releaseAllSleep();
        sleepTick = 0;
    }

    private void beginScissor(){
        if(scissorFBFailed) return;
        if(scissorW >= 100 && scissorH >= 100) return;

        int sw = Core.graphics.getWidth();
        int sh = Core.graphics.getHeight();
        int vw = Math.max(2, sw * scissorW / 100);
        int vh = Math.max(2, sh * scissorH / 100);

        try{
            if(scissorFB == null){
                scissorFB = new FrameBuffer(vw, vh);
            }
            scissorFB.resize(vw, vh);
        }catch(Throwable t){
            scissorFBFailed = true;
            scissorFBActive = false;
            Log.err("[MO] no se pudo crear el FrameBuffer del recorte; recorte desactivado: " + t);
            return;
        }

        savedCamW = Core.camera.width;
        savedCamH = Core.camera.height;
        Core.camera.width = savedCamW * scissorW / 100f;
        Core.camera.height = savedCamH * scissorH / 100f;
        Core.camera.update();

        scissorFB.begin(Color.black);
        scissorFBActive = true;
    }

    private void endScissor(){
        if(!scissorFBActive) return;
        scissorFBActive = false;

        scissorFB.end();
        Core.camera.width = savedCamW;
        Core.camera.height = savedCamH;
        Core.camera.update();

        Gl.clearColor(0f, 0f, 0f, 1f);
        Gl.clear(Gl.colorBufferBit);
        Blending.disabled.apply();
        Draw.blit(scissorFB, Shaders.screenspace);
        Blending.normal.apply();
    }

    public static int mergeGrid(int[] grid, int w, int h, RectSink sink){
        int quads = 0;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                int c = grid[y * w + x];
                if(c == 0) continue;

                int rw = 1;
                while(x + rw < w && grid[y * w + x + rw] == c) rw++;

                int rh = 1;
                boolean ok = true;
                while(ok && y + rh < h){
                    int row = (y + rh) * w + x;
                    for(int k = 0; k < rw; k++){
                        if(grid[row + k] != c){
                            ok = false;
                            break;
                        }
                    }
                    if(ok) rh++;
                }

                for(int yy = 0; yy < rh; yy++){
                    int row = (y + yy) * w + x;
                    for(int k = 0; k < rw; k++){
                        grid[row + k] = 0;
                    }
                }

                sink.rect(x, y, rw, rh, c);
                quads++;
            }
        }
        return quads;
    }

    private void flushStats(){
        if(statsOn && frames > 0){
            Log.info(String.format(
                "[MO] bloques/f: total=%d fog=%d icon=%d solid=%d->%dq kept=%d | unidades: icon=%d solid=%d | sleep=%d | MO %.3fms | mundo %.2fms",
                sIn / frames, sFog / frames, sIcon / frames, sSolidT / frames, sSolidQ / frames, sKept / frames,
                sUI / frames, sUS / frames, sleepMap.size,
                nanos / (double)frames / 1e6, wNanos / (double)frames / 1e6));
        }
        frames = 0;
        sUS = sUI = sKept = sSolidQ = sSolidT = sIcon = sFog = sIn = 0;
        wNanos = nanos = 0L;
    }

    public interface RectSink{
        void rect(int x, int y, int w, int h, int color);
    }

    private static class SleepEntry{
        final Building b;
        final int id;
        final int slot;
        boolean dead;

        SleepEntry(Building b, int period){
            this.b = b;
            this.id = b.id;
            this.slot = b.id % Math.max(1, period);
        }
    }
}
