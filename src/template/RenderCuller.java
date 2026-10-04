package template;

import arc.Core;
import arc.Events;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

import java.lang.reflect.Field;
import java.util.ArrayList;

public class RenderCuller{
    public static final String K_ON = "mo-on";
    public static final String K_FOG = "mo-fog";
    public static final String K_SLEEP = "mo-sleep";
    public static final String K_SLEEP_HZ = "mo-sleep-hz";
    public static final String K_STATS = "mo-stats";
    public static final String K_PAR = "mo-par";
    public static final String K_THREADS = "mo-threads";

    private static final int POLL_MASK = 15;
    private static final long SLEEP_SCAN_NS = 800000L;
    private static final int SLEEP_SCAN_TICKS = 8;

    private Field fTileview, fSleepTime, fTimeScale, fTimeScaleDuration, fSleeping;
    private boolean bindFailed;
    private boolean swapped;
    private Seq<Tile> originalView;

    private final Seq<Tile> scratch = new Seq<>(false, 2048, Tile.class);


    private static final Class<?>[] CRAFTER_TYPES = initCrafterTypes();
    private final IntMap<SleepEntry> sleepMap = new IntMap<>();
    private final Seq<Building> toWake = new Seq<>(false, 64, Building.class);
    private final Seq<Building> toFree = new Seq<>(false, 64, Building.class);
    private final Seq<Building> toResleep = new Seq<>(false, 64, Building.class);
    private final Seq<Building> toSleepNew = new Seq<>(false, 64, Building.class);
    private int scanCursor, sleepTick, sleepPeriod = 6;
    private boolean sleepOn;
    // "fuera de vista" también incluye edificios de otros equipos bajo niebla (idea del antiguo culling en JS)
    private boolean sleepFog, fogFailed;
    private Team sleepTeam;
    private Object fogCtl;
    private java.lang.reflect.Method mFogVisible;

    private boolean on, cullFog, statsOn;
    private int poll;

    private int sIn, sFog, sKept, frames;
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
        statsOn = Core.settings.getBool(K_STATS, true);
        parallel = Core.settings.getBool(K_PAR, true);
        Cores.get().configure(Core.settings.getInt(K_THREADS, 0));

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
    }

    private void onPostDraw(){
        restoreView();
        wNanos += Time.nanos() - wStart;
    }

    private void onUpdate(){
        if((poll++ & POLL_MASK) == 0){
            readSettings();
        }
        if(sleepOn && Vars.state.isGame() && !Vars.net.client()){
            try{
                tickSleep();
            }catch(Throwable t){
                Log.err("[MO] sleep falló; se desactiva hasta reiniciar el mundo", t);
                sleepOn = false;
                try{
                    releaseAllSleep();
                }catch(Throwable ignored){
                }
            }
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
            if(!fogOn) return; // sin niebla no hay nada que descartar: se deja la lista del juego intacta (cero coste)

            scratch.clear();
            int fog = 0;

            // ---- Fase 1: clasificar (solo lectura; se reparte entre núcleos si hay suficientes tiles) ----
            final int n = src.size;
            if(act.length < n){
                act = new byte[n + 1024];
            }
            cTeam = pteam;
            cFogOn = fogOn;
            final Tile[] items = src.items;

            boolean ran = false;
            if(parallel && !parFailed && n >= PAR_MIN_TILES){
                try{
                    ran = Cores.get().parallelFor(n, PAR_CHUNK, (from, to) -> classify(items, from, to));
                    if(ran) sPar++;
                }catch(Throwable th){
                    parFailed = true;
                    Log.err("[MO] clasificación paralela falló; se usa un solo hilo", th);
                    ran = false;
                }
            }
            if(!ran) classify(items, 0, n);

            // ---- Fase 2: construir la lista final (hilo principal) ----
            for(int i = 0; i < n; i++){
                if(act[i] == A_KEEP) scratch.add(items[i]);
                else fog++;
            }

            sIn += n;
            sFog += fog;
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

    private static final byte A_KEEP = 0, A_FOG = 1;
    private static final int PAR_MIN_TILES = 3000, PAR_CHUNK = 1500;

    // Parámetros de la clasificación del frame actual (se escriben en el hilo principal antes de repartir).
    private byte[] act = new byte[0];
    private Team cTeam;
    private boolean cFogOn;
    private boolean parallel = true, parFailed;
    private int sPar;

    /** Decide qué hacer con cada tile en [from, to): conservarlo o descartarlo por niebla. Solo lee el mundo. */
    private void classify(Tile[] items, int from, int to){
        final Team pteam = cTeam;
        final boolean fogOn = cFogOn;

        for(int i = from; i < to; i++){
            Tile tile = items[i];
            Building build = tile.build;
            boolean hidden = fogOn && tile.block() != Blocks.air && build != null && build.inFogTo(pteam);
            act[i] = hidden ? A_FOG : A_KEEP;
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

    private boolean isCrafter(Block b){
        for(Class<?> c : CRAFTER_TYPES){
            if(c.isInstance(b)) return true;
        }
        return false;
    }

    private boolean offScreen(Building b){
        float margin = b.block.size * 8f + 16f;
        if(Math.abs(b.x - Core.camera.position.x) > Core.camera.width * 0.5f + margin
            || Math.abs(b.y - Core.camera.position.y) > Core.camera.height * 0.5f + margin){
            return true;
        }
        // Edificio de otro equipo que el jugador no ve ahora mismo (niebla): también cuenta como fuera de vista.
        return sleepFog && sleepTeam != null && b.team != sleepTeam && !fogVisible(b);
    }

    private boolean fogVisible(Building b){
        if(fogFailed) return true;
        try{
            if(mFogVisible == null){
                fogCtl = Refl.getStatic(Vars.class, "fogControl");
                if(fogCtl == null){
                    fogFailed = true;
                    return true;
                }
                mFogVisible = fogCtl.getClass().getMethod("isVisible", Team.class, float.class, float.class);
            }
            return (Boolean)mFogVisible.invoke(fogCtl, sleepTeam, b.x, b.y);
        }catch(Throwable t){
            fogFailed = true; // otra firma en esta versión: sin niebla en el criterio, pero el resto sigue igual
            Refl.once("niebla en sleep", t);
            return true;
        }
    }

    /** Un procesador lógico controla este edificio (enabledControlTime > 0): no se toca. */
    private boolean controlled(Building b){
        return Refl.getF(b, "enabledControlTime", 0f) > 0f;
    }

    private void forceSleep(Building b){
        try{
            fSleepTime.setFloat(b, 61f);
            b.enabled = false; // dormida no debe seguir pidiendo energía al grafo; el tick de puesta al día la pide de golpe
            b.sleep();
        }catch(Throwable ignored){
        }
    }

    private void forceWake(Building b, int period){
        b.enabled = true;
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

    /**
     * Sleep de crafters fuera de pantalla.
     *
     * OJO: Building.sleep() hace remove() del edificio en Groups.build (y noSleep() lo vuelve a añadir). Por eso:
     *  - los edificios dormidos NO aparecen en Groups.build ni cumplen isAdded(); se vigilan desde sleepMap con isValid();
     *  - nunca se debe dormir/despertar mientras se recorre Groups.build (el tamaño cambia a mitad del bucle:
     *    era el IndexOutOfBounds "index can't be >= size" del crash). Aquí se recolectan y se aplican después.
     */
    private void tickSleep(){
        // Los campos reflejados se enlazan en bind(); sin ellos no hay sleep.
        if(fSleeping == null){
            bind();
            if(fSleeping == null) return;
        }

        sleepTick++;
        final int P = sleepPeriod;
        sleepFog = Vars.state.rules.fog;
        sleepTeam = Vars.player != null ? Vars.player.team() : null;

        // 1) Entradas registradas
        toWake.clear();
        toFree.clear();
        toResleep.clear();
        IntSeq toRemove = null;

        for(IntMap.Entry<SleepEntry> e : sleepMap.entries()){
            SleepEntry se = e.value;
            Building b = se.b;

            if(!b.isValid()){ // destruido, deconstruido o reemplazado
                if(toRemove == null) toRemove = new IntSeq();
                toRemove.add(e.key);
                continue;
            }

            if(controlled(b)){ // un procesador lógico lo controla: se libera y se deja en paz
                if(toRemove == null) toRemove = new IntSeq();
                toRemove.add(e.key);
                toFree.add(b);
                continue;
            }

            if(se.asleep){
                if(!offScreen(b)){ // el jugador se acerca: despertar definitivamente
                    if(toRemove == null) toRemove = new IntSeq();
                    toRemove.add(e.key);
                    toFree.add(b);
                }else if(sleepTick % P == se.slot){ // tick de puesta al día (con timeScale = P)
                    toWake.add(b);
                    se.asleep = false;
                    se.wokeTick = sleepTick;
                }
            }else if(sleepTick > se.wokeTick + 1){ // ya tuvo al menos un frame de update
                if(offScreen(b)){
                    toResleep.add(b);
                    se.asleep = true;
                }else{
                    if(toRemove == null) toRemove = new IntSeq();
                    toRemove.add(e.key);
                }
            }
        }

        if(toRemove != null){
            for(int i = 0; i < toRemove.size; i++){
                sleepMap.remove(toRemove.items[i]);
            }
        }
        for(int i = 0; i < toFree.size; i++) forceWake(toFree.items[i], 1);
        for(int i = 0; i < toWake.size; i++) forceWake(toWake.items[i], P);
        for(int i = 0; i < toResleep.size; i++) forceSleep(toResleep.items[i]);

        // 2) Buscar nuevos candidatos (solo lectura de Groups.build; se duerme después)
        if(sleepTick % SLEEP_SCAN_TICKS != 0) return;

        final int n = Groups.build.size();
        if(n == 0){
            scanCursor = 0;
            return;
        }
        if(scanCursor >= n) scanCursor = 0;

        toSleepNew.clear();
        long t0 = Time.nanos();
        int checked = 0;
        while(checked < n && Time.nanos() - t0 < SLEEP_SCAN_NS){
            Building b = Groups.build.index(scanCursor);
            scanCursor = (scanCursor + 1) % n;
            checked++;

            if(b == null || !b.isAdded() || sleepMap.containsKey(b.id)) continue;
            if(isCrafter(b.block) && !(b.block instanceof CoreBlock) && b.enabled && !isSleeping(b)
                && Refl.getB(b.block, "canOverdrive", true) && !controlled(b) && offScreen(b)){
                toSleepNew.add(b);
            }
        }

        for(int i = 0; i < toSleepNew.size; i++){
            Building b = toSleepNew.items[i];
            sleepMap.put(b.id, new SleepEntry(b, P));
            forceSleep(b);
        }
    }

    private void releaseAllSleep(){
        for(IntMap.Entry<SleepEntry> e : sleepMap.entries()){
            try{
                if(!e.value.b.isValid()) continue;
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

    private void flushStats(){
        if(statsOn && frames > 0){
            Log.info(String.format(
                "[MO] bloques/f: total=%d niebla=%d conservados=%d | sleep=%d | par=%d/%d | MO %.3fms | mundo %.2fms",
                sIn / frames, sFog / frames, sKept / frames,
                sleepMap.size, sPar, frames,
                nanos / (double)frames / 1e6, wNanos / (double)frames / 1e6));
        }
        frames = 0;
        sPar = 0;
        sKept = sFog = sIn = 0;
        wNanos = nanos = 0L;
    }

    private static class SleepEntry{
        final Building b;
        final int id;
        final int slot;
        boolean asleep = true;
        int wokeTick;

        SleepEntry(Building b, int period){
            this.b = b;
            this.id = b.id;
            this.slot = b.id % Math.max(1, period);
        }
    }
}
