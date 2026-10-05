package template;

import arc.ApplicationListener;
import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Groups;

import java.lang.reflect.Method;

/**
 * Pide a Android más recursos mientras se carga/juega un mapa enorme. Todo por reflexión y con try/catch: lo que el
 * dispositivo no soporte se omite y se anota en el log ([MO]).
 *
 *  - Pantalla siempre encendida: una carga larga con la pantalla apagándose pausa la Activity y puede perder el contexto GL.
 *  - Modo de rendimiento sostenido (Window.setSustainedPerformanceMode): menos caídas por temperatura (si el equipo lo soporta).
 *  - ADPF (PerformanceHintManager, Android 12+): se abre una sesión para el hilo del juego y, mientras se carga un mapa o
 *    hay un mapa enorme (más de 100 000 edificios), se informa al sistema de que el trabajo va más lento que el objetivo,
 *    lo que le hace subir la frecuencia de CPU. El efecto depende del fabricante; no es una garantía.
 *
 * Qué NO hace: subir el límite de memoria Java de la app ni fijar hilos a núcleos concretos (Android no lo permite a una app).
 */
public final class AndroidBoost{
    private static final long TARGET_NS = 16_666_667L;
    private static final int HEAVY_BUILDINGS = 100_000;

    private static Object activity, session;
    private static Method mReport;
    private static volatile boolean enabled;
    private static boolean installed, windowOn;
    private static Thread thread;

    private AndroidBoost(){
    }

    public static void install(){
        if(installed || Vars.headless || !Core.app.isAndroid()) return;
        try{
            if(!Class.forName("android.app.Activity").isInstance(Core.app)) return;
            activity = Core.app;
        }catch(Throwable t){
            return;
        }
        installed = true;

        Core.app.addListener(new ApplicationListener(){
            int counter;

            public void update(){
                if((counter++ & 63) == 0) poll();
            }
        });
        poll();
        Log.info("[MO] AndroidBoost listo");
    }

    private static void poll(){
        boolean want = Core.settings.getBool("mo-boost", true);
        if(want == enabled && (want == windowOn)) return;
        enabled = want;
        try{
            applyWindow(want);
            if(want){
                openSession();
                startThread();
            }else{
                closeSession();
            }
        }catch(Throwable t){
            Refl.once("AndroidBoost", t);
        }
    }

    public static String status(){
        if(!installed) return "no instalado (no es Android o no es una Activity)";
        return (enabled ? "activo" : "apagado") + ", ADPF " + (session != null ? "sí" : "no");
    }

    /** Un pulso de "voy justo de CPU". Seguro desde cualquier hilo; sin efecto si no hay sesión ADPF. */
    public static void pulse(){
        Object s = session;
        Method m = mReport;
        if(!enabled || s == null || m == null) return;
        try{
            m.invoke(s, 40_000_000L); // 40 ms de trabajo real frente a un objetivo de 16,7 ms
        }catch(Throwable t){
            Refl.once("ADPF reportActualWorkDuration", t);
            session = null;
        }
    }

    // ------------------------------------------------------------------ ventana

    private static void applyWindow(boolean on) throws Exception{
        windowOn = on;
        final Class<?> activityC = Class.forName("android.app.Activity");
        final Class<?> windowC = Class.forName("android.view.Window");
        final Object app = activity;

        Runnable job = () -> {
            try{
                Object window = activityC.getMethod("getWindow").invoke(app);
                // FLAG_KEEP_SCREEN_ON = 128
                windowC.getMethod(on ? "addFlags" : "clearFlags", int.class).invoke(window, 128);
                try{
                    windowC.getMethod("setSustainedPerformanceMode", boolean.class).invoke(window, on);
                }catch(Throwable ignored){
                    // equipo sin soporte (API < 24 o sin la característica)
                }
                Log.info("[MO] pantalla " + (on ? "siempre encendida + rendimiento sostenido" : "ajustes de ventana restaurados"));
            }catch(Throwable t){
                Refl.once("AndroidBoost ventana", t);
            }
        };
        activityC.getMethod("runOnUiThread", Runnable.class).invoke(app, job);
    }

    // ------------------------------------------------------------------ ADPF

    private static void openSession(){
        if(session != null) return;
        try{
            Class<?> ctx = Class.forName("android.content.Context");
            Object mgr = ctx.getMethod("getSystemService", String.class).invoke(activity, "performance_hint");
            if(mgr == null){
                Log.info("[MO] ADPF: este equipo no ofrece PerformanceHintManager");
                return;
            }
            Class<?> mgrC = Class.forName("android.os.PerformanceHintManager");
            int tid = (Integer)Class.forName("android.os.Process").getMethod("myTid").invoke(null);
            Object s = mgrC.getMethod("createHintSession", int[].class, long.class).invoke(mgr, new int[]{tid}, TARGET_NS);
            if(s == null){
                Log.info("[MO] ADPF: no se pudo crear la sesión");
                return;
            }
            mReport = Class.forName("android.os.PerformanceHintManager$Session").getMethod("reportActualWorkDuration", long.class);
            session = s;
            Log.info("[MO] ADPF activo para el hilo " + tid);
        }catch(ClassNotFoundException e){
            Log.info("[MO] ADPF: requiere Android 12 o superior");
        }catch(Throwable t){
            Refl.once("ADPF", t);
        }
    }

    private static void closeSession(){
        Object s = session;
        session = null;
        if(s == null) return;
        try{
            Class.forName("android.os.PerformanceHintManager$Session").getMethod("close").invoke(s);
        }catch(Throwable ignored){
        }
    }

    /** Mientras el mapa sea enorme, mantiene el pulso aunque no se esté cargando. */
    private static void startThread(){
        if(thread != null && thread.isAlive()) return;
        thread = new Thread(() -> {
            while(true){
                try{
                    Thread.sleep(500);
                    if(enabled && session != null && Vars.state != null && Vars.state.isGame() && Groups.build.size() > HEAVY_BUILDINGS){
                        pulse();
                    }
                }catch(InterruptedException e){
                    return;
                }catch(Throwable ignored){
                }
            }
        }, "MO-Boost");
        thread.setDaemon(true);
        thread.start();
    }
}
