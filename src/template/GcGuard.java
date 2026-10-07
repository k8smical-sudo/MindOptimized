package template;

import arc.ApplicationListener;
import arc.Core;
import arc.util.Log;
import mindustry.Vars;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Vigilante del recolector de basura (GC) de Android.
 *
 * Lo que NO se puede hacer: elegir otro recolector. Android 14 usa "generational CC" (lo confirma el log del arranque)
 * y una app no puede cambiarlo. Tampoco se puede ajustar la memoria del runtime desde un mod.
 *
 * Lo que SÍ hace:
 *  - Mide, con la API pública Debug.getRuntimeStat, cuántos ciclos de GC hay, cuánto tiempo gastan, cuántos bloquean al
 *    juego y cuánta memoria se reserva por segundo. La tasa de asignación es la cifra que delata el exceso de basura.
 *  - Avisa en el log cuando un GC bloqueante pasa de 8 ms (un tirón visible).
 *  - Hace una limpieza SOLO en momentos seguros (menú o juego en pausa, sin cargar un mapa) y solo si el heap supera el 85 %,
 *    como mucho una vez por minuto. Nunca fuerza un GC en mitad de una batalla.
 *
 * Se consulta cada ~128 frames desde el hilo del juego; el coste es de unas pocas lecturas de cadenas.
 */
public final class GcGuard{
    public static final String K_ON = "mo-gc";

    private static Method mStat;
    private static boolean failed, installed;
    private static long lastNs, lastCount, lastTimeMs, lastBlkCount, lastBlkMs, lastBytes, lastForceNs, lastBlockLogNs;
    private static int counter;
    private static volatile String status = "";

    private GcGuard(){
    }

    public static String status(){
        return status;
    }

    public static void install(){
        if(installed || !Core.app.isAndroid()) return;
        installed = true;
        Core.app.addListener(new ApplicationListener(){
            public void update(){
                if((counter++ & 127) == 0){
                    try{
                        poll();
                    }catch(Throwable t){
                        Refl.once("GcGuard", t);
                        failed = true;
                    }
                }
            }
        });
        Log.info("[MO] GcGuard listo");
    }

    private static long stat(String key){
        try{
            if(mStat == null) mStat = Class.forName("android.os.Debug").getMethod("getRuntimeStat", String.class);
            String s = (String)mStat.invoke(null, key);
            return s == null ? 0L : Long.parseLong(s.trim());
        }catch(Throwable t){
            failed = true;
            return 0L;
        }
    }

    private static void poll(){
        if(failed || !Core.settings.getBool(K_ON, true)){
            status = "";
            return;
        }

        long now = System.nanoTime();
        long count = stat("art.gc.gc-count"), timeMs = stat("art.gc.gc-time");
        long blk = stat("art.gc.blocking-gc-count"), blkMs = stat("art.gc.blocking-gc-time");
        long bytes = stat("art.gc.bytes-allocated");
        if(failed){
            Log.info("[MO] GC: este equipo no expone Debug.getRuntimeStat; vigilancia desactivada");
            status = "";
            return;
        }

        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L), maxMB = rt.maxMemory() / (1024L * 1024L);

        if(lastNs != 0L){
            double sec = Math.max(0.1, (now - lastNs) / 1e9);
            long dCount = count - lastCount, dTime = timeMs - lastTimeMs, dBlk = blk - lastBlkCount, dBlkMs = blkMs - lastBlkMs;
            double allocMBs = (bytes - lastBytes) / 1048576.0 / sec;

            status = String.format(Locale.ROOT, "[accent]GC[] %d ciclos/%.0f s · %d ms · bloqueantes %d (%d ms) · asignación %.1f MB/s · heap %d/%d MB",
                dCount, sec, dTime, dBlk, dBlkMs, allocMBs, usedMB, maxMB);

            if(dBlk > 0 && dBlkMs >= 8 && now - lastBlockLogNs > 5_000_000_000L){
                lastBlockLogNs = now;
                Log.info("[MO] GC bloqueante: " + dBlk + " en " + dBlkMs + " ms (asignación " + String.format(Locale.ROOT, "%.1f", allocMBs) + " MB/s, heap " + usedMB + "/" + maxMB + " MB)");
            }
        }
        lastNs = now;
        lastCount = count;
        lastTimeMs = timeMs;
        lastBlkCount = blk;
        lastBlkMs = blkMs;
        lastBytes = bytes;

        // Limpieza oportuna: solo en momentos seguros y con el heap realmente apretado.
        boolean safe = !MemGuard.loading && (!Vars.state.isGame() || Vars.state.isPaused());
        if(safe && maxMB > 0 && usedMB * 100 / maxMB >= 85 && now - lastForceNs > 60_000_000_000L){
            lastForceNs = now;
            Log.info("[MO] GC oportuno (heap " + usedMB + "/" + maxMB + " MB, juego en menú o pausa)");
            System.gc();
        }
    }
}
