package template;

import arc.ApplicationListener;
import arc.Core;
import arc.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * Frame pacing propio: entrega los frames a Android en una cadencia fija, sincronizada con el vsync de la pantalla, sin
 * que el juego se quede bloqueado esperando buffers.
 *
 * Qué problema resuelve: con vsync apagado el juego produce frames lo más rápido que puede (tu captura marcaba 172 FPS en
 * una pantalla de 60 Hz). El compositor solo muestra uno por vsync, los demás se tiran, y cuando la cola de buffers se
 * llena el hilo de GL se bloquea dentro de eglSwapBuffers. Resultado: calor, batería y entrega irregular (judder).
 *
 * Cómo lo hace (el mismo principio que la librería oficial Swappy de Android):
 *  1. Mide el vsync real con Choreographer (hora de cada vsync y periodo exacto).
 *  2. Antes de cada frame (beginFrame) duerme hasta el instante en que conviene EMPEZAR para terminar justo a tiempo.
 *  3. Al acabar el frame (endFrame) elige a qué vsync debe mostrarse (cada N vsyncs, N = cadencia) y se lo dice al sistema
 *     con eglPresentationTimeANDROID: el compositor no lo enseña ANTES de esa hora.
 *
 * Los dos ajustes:
 *  - Cadencia (mo-pace-mode): 0 = libre (apagado); 1..4 = un frame cada N vsyncs (60 Hz: 60, 30, 20, 15 FPS).
 *  - Colchón de latencia (mo-pace-lead): cuántos frames de margen se deja antes del vsync. 0 = justo a tiempo (mínima
 *    latencia, puede perder algún vsync si un frame se alarga); 3 = holgado, como el triple buffer de siempre.
 *
 * Qué NO hace: no muestra más frames de los que la pantalla puede mostrar, ni hace al juego más rápido si ya va por
 * debajo del refresco (en una pantalla de 60 Hz, 60 FPS es el máximo visible). Su valor es entrega estable, menos latencia
 * y menos consumo. Si el sistema no ofrece EGL_ANDROID_presentation_time, solo se usa el sueño sincronizado.
 * Cualquier fallo lo pausa unos segundos; nunca debe afectar al juego.
 */
public final class FramePacer{
    public static final String K_MODE = "mo-pace-mode"; // 0 libre, 1..4 = cada N vsyncs
    public static final String K_LEAD = "mo-pace-lead"; // 0..3 frames de colchón

    /** true mientras el pacing está aplicándose. Lo lee LogicTimer en cada frame. */
    public static volatile boolean active;

    private static int mode, lead = 1, counter, pause;
    private static boolean installed, hooked;

    // vsync medido (hilo UI -> hilo GL)
    private static volatile long periodNs = 16_666_667L, anchorNs;
    private static volatile boolean vsyncKnown;
    private static long lastSampleNs;

    // presentación (EGL)
    private static Method mPresent, mGetDisplay, mGetSurface;
    private static boolean presentFailed;

    // cadencia
    private static long lastTarget, plannedStart, frameStart, lastEnd;
    private static boolean cadence;
    private static float costEma = 8_000_000f;

    // estadísticas
    private static final float[] iv = new float[120];
    private static int ivN, ivI, frames, missed;
    private static volatile String status = "";

    private FramePacer(){
    }

    public static String status(){
        return status;
    }

    /** Frecuencia de refresco medida, en Hz. */
    public static float hz(){
        return 1e9f / Math.max(1L, periodNs);
    }

    public static String modeLabel(int m){
        if(m <= 0) return "Libre (sin pacing)";
        return String.format(Locale.ROOT, "cada %d vsync · %.0f fps", m, hz() / m);
    }

    public static void install(){
        if(installed || !Core.app.isAndroid()) return;
        installed = true;
        Core.app.addListener(new ApplicationListener(){
            // Fin de cada frame, en el hilo de GL, justo antes de que GLSurfaceView haga el swap.
            public void update(){
                try{
                    frameEnd();
                }catch(Throwable t){
                    Refl.once("FramePacer", t);
                    pauseNow();
                }
            }
        });
        Log.info("[MO] FramePacer listo");
    }

    private static void pauseNow(){
        pause = 300;
        cadence = false;
        plannedStart = 0;
    }

    // ------------------------------------------------------------------ ajustes

    private static void poll(){
        int m = Math.max(0, Math.min(4, Core.settings.getInt(K_MODE, 0)));
        lead = Math.max(0, Math.min(3, Core.settings.getInt(K_LEAD, 1)));
        boolean want = m > 0;
        mode = m;
        if(want != active){
            active = want;
            cadence = false;
            plannedStart = 0;
            frames = missed = 0;
            Log.info("[MO] pacing " + (want ? "ACTIVO: " + modeLabel(m) + ", colchón " + lead : "apagado"));
        }
        if(active && System.nanoTime() - lastSampleNs > 3_000_000_000L){
            lastSampleNs = System.nanoTime();
            sampleVsync();
        }
    }

    // ------------------------------------------------------------------ vsync (Choreographer, hilo de interfaz)

    private static void sampleVsync(){
        try{
            final Object act = Core.app;
            final Class<?> actC = Class.forName("android.app.Activity");
            final Class<?> chC = Class.forName("android.view.Choreographer");
            final Class<?> cbC = Class.forName("android.view.Choreographer$FrameCallback");

            Runnable job = () -> {
                try{
                    final Object ch = chC.getMethod("getInstance").invoke(null);
                    final Method post = chC.getMethod("postFrameCallback", cbC);
                    final long[] t = new long[16];
                    final int[] n = {0};
                    final Object[] self = new Object[1];

                    InvocationHandler h = (proxy, m, args) -> {
                        String name = m.getName();
                        if("doFrame".equals(name) && args != null && args.length == 1){
                            t[n[0]++] = (Long)args[0];
                            if(n[0] < t.length) post.invoke(ch, self[0]);
                            else finishSample(t);
                        }else if("hashCode".equals(name)){
                            return System.identityHashCode(proxy);
                        }else if("equals".equals(name)){
                            return proxy == args[0];
                        }else if("toString".equals(name)){
                            return "MO-VsyncProbe";
                        }
                        return null;
                    };
                    self[0] = Proxy.newProxyInstance(cbC.getClassLoader(), new Class<?>[]{cbC}, h);
                    post.invoke(ch, self[0]);
                }catch(Throwable e){
                    Refl.once("Choreographer", e);
                }
            };
            actC.getMethod("runOnUiThread", Runnable.class).invoke(act, job);
        }catch(Throwable e){
            Refl.once("muestreo de vsync", e);
        }
    }

    private static void finishSample(long[] t){
        long p = (t[t.length - 1] - t[0]) / (t.length - 1);
        if(p < 4_000_000L || p > 50_000_000L) return; // 20-250 Hz: lo demás es ruido
        boolean first = !vsyncKnown;
        periodNs = first ? p : (long)(periodNs * 0.7 + p * 0.3);
        anchorNs = t[t.length - 1];
        vsyncKnown = true;
        if(first) Log.info(String.format(Locale.ROOT, "[MO] pacing: vsync medido %.3f Hz (periodo %.3f ms)", 1e9 / periodNs, periodNs / 1e6));
    }

    /** Primer vsync estrictamente posterior a x. */
    private static long vsyncAfter(long x){
        long a = anchorNs, p = periodNs;
        if(a == 0L) return x + p;
        return a + (Math.floorDiv(x - a, p) + 1) * p;
    }

    // ------------------------------------------------------------------ presentación (EGL)

    private static void setPresentationTime(long ns){
        if(presentFailed) return;
        try{
            if(mPresent == null){
                Class<?> egl14 = Class.forName("android.opengl.EGL14");
                Class<?> dispC = Class.forName("android.opengl.EGLDisplay");
                Class<?> surfC = Class.forName("android.opengl.EGLSurface");
                mGetDisplay = egl14.getMethod("eglGetCurrentDisplay");
                mGetSurface = egl14.getMethod("eglGetCurrentSurface", int.class);
                Object d = mGetDisplay.invoke(null);
                String ext = (String)egl14.getMethod("eglQueryString", dispC, int.class).invoke(null, d, 0x3055); // EGL_EXTENSIONS
                if(ext == null || !ext.contains("EGL_ANDROID_presentation_time")){
                    presentFailed = true;
                    Log.info("[MO] pacing: este driver no ofrece EGL_ANDROID_presentation_time; solo sueño sincronizado");
                    return;
                }
                mPresent = Class.forName("android.opengl.EGLExt").getMethod("eglPresentationTimeANDROID", dispC, surfC, long.class);
                Log.info("[MO] pacing: EGL_ANDROID_presentation_time disponible");
            }
            mPresent.invoke(null, mGetDisplay.invoke(null), mGetSurface.invoke(null, 0x3059), ns); // EGL_DRAW
        }catch(Throwable t){
            presentFailed = true;
            Refl.once("eglPresentationTimeANDROID", t);
        }
    }

    // ------------------------------------------------------------------ el frame

    /** Llamar al principio de cada frame (desde LogicTimer, antes de la lógica). Duerme hasta la hora de empezar. */
    public static void beginFrame(){
        hooked = true;
        if(pause > 0) return;
        try{
            long now = System.nanoTime();
            if(plannedStart > now) sleepUntil(plannedStart);
            frameStart = System.nanoTime();
        }catch(Throwable t){
            Refl.once("FramePacer.beginFrame", t);
            pauseNow();
        }
    }

    private static void frameEnd(){
        if((counter++ & 31) == 0) poll();
        if(!active) return;
        if(pause > 0){
            pause--;
            return;
        }

        long now = System.nanoTime();
        long period = periodNs;

        // Una pausa larga (carga de mapa, menú, app en segundo plano): se reinicia la cadencia.
        if(lastEnd != 0L && now - lastEnd > 250_000_000L) cadence = false;
        if(lastEnd != 0L){
            iv[ivI] = (now - lastEnd) / 1e6f;
            ivI = (ivI + 1) % iv.length;
            if(ivN < iv.length) ivN++;
        }
        lastEnd = now;
        if(frameStart != 0L) costEma = costEma * 0.9f + (now - frameStart) * 0.1f;

        // La GPU aún tiene trabajo pendiente de este frame: no se puede mostrar antes de que lo acabe.
        float gpuMs = Math.max(3f, SysMonitor.gpuMsFrame);
        long ready = now + (long)(gpuMs * 1e6f) + 1_000_000L;
        long earliest = vsyncAfter(ready);

        long interval = period * mode;
        long target = cadence ? lastTarget + interval : earliest;
        if(target < earliest){
            target = earliest; // llegamos tarde al vsync previsto: se reengancha la cadencia
            missed++;
        }
        lastTarget = target;
        cadence = true;
        frames++;

        // "No antes de": medio periodo antes del vsync elegido, para que cualquier error de fase siga cayendo en él.
        setPresentationTime(target - period / 2);

        // Cuándo empezar el siguiente frame: terminar justo a tiempo para el vsync siguiente, más el colchón elegido.
        long nextTarget = target + interval;
        long need = (long)(Math.max(costEma, gpuMs * 1e6f) * 1.15f) + 1_500_000L + lead * period;
        plannedStart = nextTarget - need;

        // Si no hay gancho al inicio de frame (LogicTimer no instalado), se duerme aquí.
        if(!hooked && plannedStart > now) sleepUntil(plannedStart);

        if((frames & 63) == 0) updateStatus();
    }

    private static void sleepUntil(long target){
        long end = Math.min(target, System.nanoTime() + 100_000_000L); // nunca más de 100 ms seguidos
        while(true){
            long left = end - System.nanoTime();
            if(left <= 0L) return;
            if(left > 500_000L) LockSupport.parkNanos(left - 350_000L);
            // los últimos ~0,35 ms se esperan activamente: parkNanos no es tan preciso
        }
    }

    private static void updateStatus(){
        if(ivN == 0) return;
        float sum = 0f, max = 0f;
        for(int i = 0; i < ivN; i++){
            sum += iv[i];
            max = Math.max(max, iv[i]);
        }
        float mean = sum / ivN, var = 0f;
        for(int i = 0; i < ivN; i++) var += (iv[i] - mean) * (iv[i] - mean);
        float sd = (float)Math.sqrt(var / ivN);
        status = String.format(Locale.ROOT, "[accent]Pacing[] %s · %.1f±%.1f ms (máx %.0f) · vsync perdidos %d%% · %s",
            modeLabel(mode), mean, sd, max, frames == 0 ? 0 : missed * 100 / frames,
            presentFailed ? "solo sueño" : "EGL hora de presentación");
    }
}
