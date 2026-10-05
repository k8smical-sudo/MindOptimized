package template;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType.WorldLoadEvent;

import java.lang.reflect.Method;

/**
 * Diagnóstico y mitigación de memoria durante la carga de mapas enormes.
 *
 * Qué hace:
 *  - Al empezar a cargar un mapa: compacta el heap (System.gc) y registra memoria Java, nativa y del sistema cada segundo
 *    hasta que termina la carga. Si el juego se cae a mitad, las últimas líneas del log muestran qué memoria se agotó.
 *  - Encadena el manejador de excepciones no capturadas: si la causa es OutOfMemoryError (o la incluye), lo deja escrito
 *    con las cifras de memoria del momento y se lo pasa al manejador original (el juego sigue generando su informe).
 *  - Avisa a AndroidBoost y al resto del mod de que se está cargando (MemGuard.loading).
 *
 * Qué NO puede hacer: subir el límite de heap de la app (lo fija el manifiesto / el sistema). Si el mapa necesita más de
 * lo permitido, esto solo permite verlo y evitar los fallos por fragmentación.
 */
public final class MemGuard{
    private static final long MB = 1024L * 1024L;

    /** true mientras se carga un mapa. */
    public static volatile boolean loading;

    private static long loadStart;
    private static Thread watcher;
    private static Method mNativeAlloc, mMemInfo;
    private static Object activityMgr;

    private MemGuard(){
    }

    @SuppressWarnings("unchecked")
    public static void install(){
        Class<?> begin = Refl.cls("mindustry.game.EventType$WorldLoadBeginEvent");
        if(begin != null){
            Events.on((Class<Object>)begin, (Cons<Object>)e -> onBegin());
        }
        Class<?> end = Refl.cls("mindustry.game.EventType$WorldLoadEndEvent");
        if(end != null){
            Events.on((Class<Object>)end, (Cons<Object>)e -> onEnd());
        }
        Events.on(WorldLoadEvent.class, e -> onEnd());

        try{
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try{
                    boolean oom = false;
                    for(Throwable c = e; c != null; c = c.getCause()){
                        if(c instanceof OutOfMemoryError){
                            oom = true;
                            break;
                        }
                    }
                    Log.err("[MO] excepción no capturada en hilo '" + t.getName() + "'" + (oom ? " (OutOfMemoryError)" : "")
                        + " | " + snapshot() + (loading ? " | durante la carga de un mapa" : ""));
                    if(oom){
                        Log.err("[MO] Sin memoria. Si 'java usado' está cerca de 'máx', el límite de heap de la app es el problema "
                            + "(lo fija el APK/sistema, no el mod). Si 'sistema libre' es bajo, es presión de RAM del teléfono.");
                    }
                }catch(Throwable ignored){
                }
                if(prev != null) prev.uncaughtException(t, e);
            });
        }catch(Throwable t){
            Log.err("[MO] MemGuard: no se pudo encadenar el manejador de excepciones", t);
        }
        Log.info("[MO] MemGuard listo | " + snapshot());
    }

    // ------------------------------------------------------------------ eventos de carga

    private static synchronized void onBegin(){
        if(loading) return;
        loading = true;
        loadStart = Time.millis();
        Log.info("[MO] carga de mapa: INICIO | " + snapshot());
        // Compactar antes de reservar arrays enormes: evita OOM por fragmentación aunque quede memoria libre en total.
        System.gc();
        AndroidBoost.pulse();
        startWatcher();
    }

    private static synchronized void onEnd(){
        if(!loading) return;
        loading = false;
        Log.info("[MO] carga de mapa: FIN en " + (Time.millis() - loadStart) + " ms | " + snapshot());
        System.gc();
    }

    private static void startWatcher(){
        if(!Core.settings.getBool("mo-memlog", true)) return;
        if(watcher != null && watcher.isAlive()) return;
        watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 10 * 60_000L;
            while(loading && System.currentTimeMillis() < deadline){
                try{
                    Log.info("[MO] cargando (" + (Time.millis() - loadStart) / 1000 + " s) | " + snapshot());
                    AndroidBoost.pulse();
                    Thread.sleep(1000);
                }catch(InterruptedException e){
                    return;
                }catch(Throwable t){
                    // nunca debe afectar a la carga
                }
            }
        }, "MO-MemWatch");
        watcher.setDaemon(true);
        watcher.start();
    }

    // ------------------------------------------------------------------ cifras

    /** Una línea con memoria Java, nativa y del sistema. Seguro de llamar desde cualquier hilo. */
    public static String snapshot(){
        StringBuilder sb = new StringBuilder(96);
        try{
            Runtime rt = Runtime.getRuntime();
            sb.append("java ").append((rt.totalMemory() - rt.freeMemory()) / MB).append('/').append(rt.maxMemory() / MB).append(" MB");
        }catch(Throwable ignored){
        }
        try{
            if(mNativeAlloc == null) mNativeAlloc = Class.forName("android.os.Debug").getMethod("getNativeHeapAllocatedSize");
            sb.append(" | nativa ").append(((Long)mNativeAlloc.invoke(null)) / MB).append(" MB");
        }catch(Throwable ignored){
        }
        try{
            String sys = systemMem();
            if(sys != null) sb.append(" | ").append(sys);
        }catch(Throwable ignored){
        }
        return sb.toString();
    }

    private static String systemMem() throws Exception{
        if(!Core.app.isAndroid()) return null;
        Class<?> act = Class.forName("android.app.Activity");
        if(!act.isInstance(Core.app)) return null;

        Class<?> amC = Class.forName("android.app.ActivityManager");
        Class<?> miC = Class.forName("android.app.ActivityManager$MemoryInfo");
        if(activityMgr == null){
            activityMgr = Class.forName("android.content.Context").getMethod("getSystemService", String.class).invoke(Core.app, "activity");
            mMemInfo = amC.getMethod("getMemoryInfo", miC);
        }
        if(activityMgr == null) return null;

        Object mi = miC.getConstructor().newInstance();
        mMemInfo.invoke(activityMgr, mi);
        long avail = miC.getField("availMem").getLong(mi) / MB;
        long total = miC.getField("totalMem").getLong(mi) / MB;
        boolean low = miC.getField("lowMemory").getBoolean(mi);
        return "sistema libre " + avail + "/" + total + " MB" + (low ? " (MEMORIA BAJA)" : "");
    }
}
