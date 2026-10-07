package template;

import arc.ApplicationListener;
import arc.Core;
import arc.func.Floatp;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Ajustes de rendimiento globales:
 *  - VSync apagado (EGL swap interval = 0 en Android) + límite de FPS propio con frame pacing estable.
 *  - Frecuencia de pantalla preferida (pista a Android; el sistema puede ignorarla).
 *  - Tickrate local de la simulación (1..60 hz), solo en partidas locales.
 *
 * Todo lo que toca clases de Android o internals del juego va por reflexión y dentro de try/catch: si algo falla en
 * una versión de MindustryX, solo esa función se desactiva y queda el motivo en el log con el prefijo [MO].
 */
public class PerfTuner{
    public static final String K_VSYNC = "mo-vsync-off";
    public static final String K_FPS = "mo-fps-cap";
    public static final String K_REFRESH = "mo-refresh";
    public static final String K_TICK = "mo-tick-hz";

    private static final String VANILLA_CAP = "fpscap";
    private static final String PREV_CAP = "mo-prev-fpscap";

    private int counter, reapply;
    private boolean vsyncOff, vsyncKnown;
    private int fpsCap, lastRefresh = -1;
    private long next;
    private boolean capLifted;

    private Governor governor;
    private boolean govFailed;

    public void install(){
        Core.app.addListener(new ApplicationListener(){
            // Se ejecuta al final de cada frame (después del núcleo del juego): ideal para medir y dormir.
            public void update(){
                frame();
            }
        });
        Log.info("[MO] PerfTuner listo");
    }

    private void frame(){
        if((counter++ & 31) == 0){
            try{
                applySettings();
            }catch(Throwable t){
                Log.err("[MO] PerfTuner.applySettings", t);
            }
        }
        pace();
    }

    // ------------------------------------------------------------------ ajustes

    private void applySettings(){
        // --- VSync ---
        // Con el pacing propio activo el swap interval debe ser 0: el compositor respeta la hora de presentación que fijamos.
        boolean off = Core.settings.getBool(K_VSYNC, false) || FramePacer.active;
        // Se reaplica cada ~16 sondeos (~500 frames): si Android recrea el contexto GL, el swap interval se pierde.
        if(!vsyncKnown || off != vsyncOff || (off && ++reapply >= 16)){
            reapply = 0;
            vsyncKnown = true;
            vsyncOff = off;
            applyVsync(off);
        }

        // --- límite de FPS ---
        fpsCap = Core.settings.getInt(K_FPS, 0);
        boolean own = vsyncOff || fpsCap > 0 || FramePacer.active;
        liftVanillaCap(own);

        // --- frecuencia de pantalla ---
        // 0 = el sistema decide; 1..29 = el máximo que soporte la pantalla; 30+ = esa tasa (o la más cercana).
        int r = Core.settings.getInt(K_REFRESH, 1);
        if(r != lastRefresh){
            lastRefresh = r;
            applyRefresh(r);
        }

        // --- tickrate local ---
        int hz = Math.max(1, Math.min(60, Core.settings.getInt(K_TICK, 60)));
        if(hz < 60 && !govFailed){
            if(governor == null) installGovernor();
            if(governor != null) governor.hz = hz;
        }else if(governor != null){
            removeGovernor();
        }
    }

    // ------------------------------------------------------------------ vsync

    private void applyVsync(boolean off){
        try{
            Core.graphics.setVSync(!off);
        }catch(Throwable ignored){
            // En Android suele ser un no-op: el cambio real se hace abajo con EGL.
        }

        if(!Core.app.isAndroid()) return;
        try{
            Class<?> egl = Class.forName("android.opengl.EGL14");
            Class<?> disp = Class.forName("android.opengl.EGLDisplay");
            Object display = egl.getMethod("eglGetCurrentDisplay").invoke(null);
            Object ok = egl.getMethod("eglSwapInterval", disp, int.class).invoke(null, display, off ? 0 : 1);
            Log.info("[MO] vsync " + (off ? "OFF" : "ON") + " (eglSwapInterval) -> " + ok);
        }catch(Throwable t){
            Log.err("[MO] no se pudo cambiar eglSwapInterval", t);
        }
    }

    /**
     * Vanilla ya pausa el frame según su ajuste "fpscap". Como aquí hacemos el pacing nosotros, se sube el de vanilla
     * a "sin límite" mientras el módulo está activo y se restaura al desactivarlo.
     */
    private void liftVanillaCap(boolean lift){
        if(lift && !capLifted){
            Core.settings.put(PREV_CAP, Core.settings.getInt(VANILLA_CAP, -1));
            Core.settings.put(VANILLA_CAP, 245);
            capLifted = true;
        }else if(!lift && capLifted){
            int prev = Core.settings.getInt(PREV_CAP, -1);
            if(prev >= 0) Core.settings.put(VANILLA_CAP, prev);
            else Core.settings.remove(VANILLA_CAP);
            capLifted = false;
        }
    }

    /** Frame pacing: duerme lo que sobra del frame para que el tiempo entre frames sea constante. */
    private void pace(){
        if(FramePacer.active){ // el pacer ya reparte los frames; dos limitadores a la vez se estorban
            next = 0;
            return;
        }
        if(fpsCap <= 0){
            next = 0;
            return;
        }

        long cap = 1_000_000_000L / fpsCap;
        long now = Time.nanos();
        if(next == 0) next = now;
        next += cap;

        if(now < next){
            long ns = next - now;
            try{
                Thread.sleep(ns / 1_000_000L, (int)(ns % 1_000_000L));
            }catch(InterruptedException ignored){
            }
        }else if(now - next > cap){
            next = now; // nos retrasamos: no acumular deuda
        }
    }

    // ------------------------------------------------------------------ frecuencia de pantalla

    /**
     * Pide a Android un modo de pantalla concreto (preferredDisplayModeId + preferredRefreshRate). Elige entre los
     * modos con la MISMA resolución física que el actual: el más rápido, o el más cercano a la tasa pedida.
     * Es el método que usaba el antiguo main.js (más fiable que pedir solo preferredRefreshRate, que el sistema ignora
     * a menudo). El sistema puede rechazarlo igualmente.
     */
    private void applyRefresh(int setting){
        if(!Core.app.isAndroid()) return;
        try{
            final Object app = Core.app;
            final Class<?> activity = Class.forName("android.app.Activity");
            if(!activity.isInstance(app)){
                Log.info("[MO] Core.app no es una Activity; no se puede pedir frecuencia de pantalla");
                return;
            }

            final Class<?> windowC = Class.forName("android.view.Window");
            final Class<?> wmC = Class.forName("android.view.WindowManager");
            final Class<?> displayC = Class.forName("android.view.Display");
            final Class<?> modeC = Class.forName("android.view.Display$Mode");
            final Class<?> paramsC = Class.forName("android.view.WindowManager$LayoutParams");
            final int want = setting;

            Runnable job = () -> {
                try{
                    Object window = activity.getMethod("getWindow").invoke(app);
                    Object lp = windowC.getMethod("getAttributes").invoke(window);

                    int modeId = 0;
                    float rate = 0f;
                    if(want > 0){
                        Object wm = activity.getMethod("getWindowManager").invoke(app);
                        Object disp = wmC.getMethod("getDefaultDisplay").invoke(wm);
                        Object cur = displayC.getMethod("getMode").invoke(disp);
                        Object modes = displayC.getMethod("getSupportedModes").invoke(disp);

                        Method gw = modeC.getMethod("getPhysicalWidth"), gh = modeC.getMethod("getPhysicalHeight");
                        Method gr = modeC.getMethod("getRefreshRate"), gid = modeC.getMethod("getModeId");
                        int cw = (Integer)gw.invoke(cur), ch = (Integer)gh.invoke(cur);

                        Object best = null;
                        float bestScore = Float.MAX_VALUE;
                        for(int i = 0; i < java.lang.reflect.Array.getLength(modes); i++){
                            Object m = java.lang.reflect.Array.get(modes, i);
                            if((Integer)gw.invoke(m) != cw || (Integer)gh.invoke(m) != ch) continue;
                            float r = (Float)gr.invoke(m);
                            float score = want >= 30 ? Math.abs(r - want) : -r; // menor = mejor
                            if(best == null || score < bestScore - 0.01f){
                                best = m;
                                bestScore = score;
                            }
                        }
                        if(best != null){
                            modeId = (Integer)gid.invoke(best);
                            rate = (Float)gr.invoke(best);
                        }
                    }

                    paramsC.getField("preferredDisplayModeId").setInt(lp, modeId);
                    paramsC.getField("preferredRefreshRate").setFloat(lp, rate);
                    windowC.getMethod("setAttributes", paramsC).invoke(window, lp);
                    Log.info("[MO] pantalla: " + (want <= 0 ? "decide el sistema" : "modo " + modeId + " a " + rate + " Hz"));
                }catch(Throwable t){
                    Log.err("[MO] frecuencia de pantalla falló", t);
                }
            };
            activity.getMethod("runOnUiThread", Runnable.class).invoke(app, job);
        }catch(Throwable t){
            Log.err("[MO] applyRefresh falló", t);
        }
    }

    // ------------------------------------------------------------------ tickrate local

    @SuppressWarnings("unchecked")
    private void installGovernor(){
        try{
            if(Vars.logic == null) return;

            Method getListeners = Core.app.getClass().getMethod("getListeners");
            Seq<ApplicationListener> roots = (Seq<ApplicationListener>)getListeners.invoke(Core.app);

            for(ApplicationListener root : roots){
                Field f = findField(root.getClass(), "modules");
                if(f == null) continue;
                f.setAccessible(true);

                Object mods = f.get(root);
                if(mods == null) continue;

                // En esta versión "modules" es un ApplicationListener[] (el log lo mostró); se admite también un Seq.
                int idx = -1;
                if(mods instanceof ApplicationListener[] arr){
                    LogicTimer timer = null;
                    for(int i = 0; i < arr.length; i++){
                        if(arr[i] == Vars.logic){
                            idx = i;
                            break;
                        }
                        // el medidor de lógica ocupa el hueco de Logic: el gobernador va DENTRO de él
                        if(arr[i] instanceof LogicTimer lt && lt.inner == Vars.logic){
                            idx = i;
                            timer = lt;
                            break;
                        }
                    }
                    if(idx < 0) continue;
                    Governor g = new Governor(Vars.logic);
                    g.index = idx;
                    if(timer != null){
                        g.timer = timer;
                        timer.inner = g;
                    }else{
                        g.array = arr;
                        arr[idx] = g;
                    }
                    governor = g;
                }else if(mods instanceof Seq<?>){
                    Seq<ApplicationListener> seq = (Seq<ApplicationListener>)mods;
                    idx = seq.indexOf(Vars.logic, true);
                    if(idx < 0) continue;
                    Governor g = new Governor(Vars.logic);
                    g.modules = seq;
                    g.index = idx;
                    seq.set(idx, g);
                    governor = g;
                }else{
                    continue;
                }

                Time.setDeltaProvider(governor.provider);
                Log.info("[MO] tickrate local: gobernador instalado");
                return;
            }

            govFailed = true;
            Log.err("[MO] tickrate local: no se encontró Logic en los módulos; función desactivada");
        }catch(Throwable t){
            govFailed = true;
            Log.err("[MO] tickrate local: instalación falló; función desactivada", t);
        }
    }

    private void removeGovernor(){
        Governor g = governor;
        governor = null;
        if(g == null) return;
        try{
            if(g.timer != null){
                if(g.timer.inner == g) g.timer.inner = g.inner;
            }else if(g.array != null){
                if(g.array[g.index] == g) g.array[g.index] = g.inner;
            }else if(g.modules != null && g.modules.get(g.index) == g){
                g.modules.set(g.index, g.inner);
            }
            Time.setDeltaProvider(Governor::vanillaDelta);
            Log.info("[MO] tickrate local: gobernador retirado");
        }catch(Throwable t){
            Log.err("[MO] tickrate local: no se pudo retirar", t);
        }
    }

    private static Field findField(Class<?> c, String name){
        while(c != null){
            try{
                return c.getDeclaredField(name);
            }catch(NoSuchFieldException e){
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Sustituye a Logic dentro de la lista de módulos. Si hz < 60 y la partida es local, solo ejecuta Logic.update()
     * cuando se acumuló un periodo de tick, pasándole el tiempo real acumulado como delta (la simulación sigue a 1x).
     * Con hz = 60, en red o fuera de partida, es transparente.
     */
    private static class Governor implements ApplicationListener{
        final ApplicationListener inner;
        Seq<ApplicationListener> modules;
        ApplicationListener[] array;
        LogicTimer timer;
        int index;
        volatile int hz = 60;

        private float due, use;
        private boolean inCall;

        final Floatp provider = () -> inCall ? use : vanillaDelta();

        Governor(ApplicationListener inner){
            this.inner = inner;
        }

        /** Mismo cálculo que el delta por defecto del juego (60 = un tick por segundo, máx 6 ticks por frame). */
        static float vanillaDelta(){
            float r = Core.graphics.getDeltaTime() * 60f;
            return (Float.isNaN(r) || Float.isInfinite(r)) ? 1f : Mathf.clamp(r, 0.0001f, 60f / 10f);
        }

        public void update(){
            if(hz >= 60 || Vars.net.active() || !Vars.state.isGame()){
                due = 0f;
                inCall = false;
                inner.update();
                return;
            }

            float dt = Core.graphics.getDeltaTime() * 60f;
            if(Float.isNaN(dt) || Float.isInfinite(dt)) dt = 1f;
            due += dt;

            float period = 60f / hz;
            if(due + 0.001f < period) return; // frame sin tick de simulación

            use = Math.min(due, 60f);
            due = 0f;
            inCall = true;
            try{
                inner.update();
            }finally{
                inCall = false;
            }
        }

        public void resize(int w, int h){
            inner.resize(w, h);
        }

        public void pause(){
            inner.pause();
        }

        public void resume(){
            inner.resume();
        }

        public void dispose(){
            inner.dispose();
        }
    }
}
