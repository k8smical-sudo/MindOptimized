package template;

import arc.ApplicationListener;
import arc.Core;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Scl;
import arc.util.Align;
import arc.util.Log;
import mindustry.Vars;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitor de rendimiento + balanceador de hilos.
 *
 * CPU por núcleo:
 *   - Si /proc/stat se puede leer, uso real del sistema por núcleo.
 *   - Android 8+ lo bloquea para las apps en casi todos los equipos. Entonces se usa lo que SÍ se puede leer: el tiempo
 *     de CPU de cada hilo de ESTE proceso (/proc/self/task/*) y el último núcleo en que corrió. Es el uso de MindOptimized
 *     /Mindustry por núcleo, no el de todo el teléfono (se marca "app").
 *
 * GPU:
 *   - Tiempo real de GPU por frame con GL_EXT_disjoint_timer_query (si el driver lo expone; en el log del equipo aparece).
 *   - Además, si existe, el porcentaje de carga del hardware desde sysfs (suele estar bloqueado).
 *
 * Balanceador: cada ~2 s sube la prioridad de los hilos propios que más CPU gastan (Process.setThreadPriority).
 * IMPORTANTE: Android no deja a una app fijar un hilo a un núcleo concreto; el planificador decide. Subir la prioridad
 * solo hace que los hilos pesados tengan preferencia, y el planificador tiende a ponerlos en núcleos potentes.
 *
 * El muestreo corre en un hilo propio con prioridad de fondo (núcleos débiles). El hilo del juego solo hace la consulta
 * de GPU (unas pocas llamadas por frame).
 */
public class SysMonitor{
    public static final String K_MODE = "mo-mon";    // 0 apagado, 1 consola, 2 pantalla + consola
    public static final String K_X = "mo-mon-x";     // % del ancho
    public static final String K_Y = "mo-mon-y";     // % del alto, desde arriba
    public static final String K_BAL = "mo-balance"; // balanceador de prioridades

    private static final int MAX_CORES = 64;

    private int mode = 2, counter;
    private boolean balance = true;
    private float px = 41f, py = 13f;

    private Label label;
    private String shown = "";
    private volatile String text = "";

    private Thread sampler;
    private volatile boolean running;

    // GPU
    private final GpuTimer gpu = new GpuTimer();
    private boolean gpuOn;
    private final AtomicLong gpuNs = new AtomicLong();
    private final AtomicInteger gpuFrames = new AtomicInteger(), frameCount = new AtomicInteger();
    private String hwGpuPath;
    private boolean hwGpuChecked;

    // CPU (solo toca el hilo de muestreo)
    private static final class Th{
        int tid, core, prio = Integer.MAX_VALUE;
        String name = "?";
        long last = -1;
        float pct;
    }

    private final HashMap<Integer, Th> threads = new HashMap<>();
    private final double[] coreApp = new double[MAX_CORES];
    private final long[] prevTotal = new long[MAX_CORES], prevIdle = new long[MAX_CORES];
    private final float[] sysPct = new float[MAX_CORES];
    private boolean sysOk = true, sysPrimed;

    private Method mSetPrio;

    // ------------------------------------------------------------------ instalación

    public void install(){
        if(!Vars.headless){
            buildLabel();
        }
        Core.app.addListener(new ApplicationListener(){
            // Fin de cada frame, en el hilo de GL.
            public void update(){
                frame();
            }
        });
        Log.info("[MO] SysMonitor listo");
    }

    private void buildLabel(){
        label = new Label("");
        label.setFontScale(0.62f);
        label.setAlignment(Align.topLeft);
        label.setWrap(false);
        label.touchable = Touchable.disabled;
        label.setSize(Scl.scl(470f), Scl.scl(170f));
        label.visible(() -> mode == 2 && Vars.state.isGame());
        label.update(() -> {
            String t = text;
            if(t != shown){
                shown = t;
                label.setText(t);
            }
            label.setPosition(Core.graphics.getWidth() * px / 100f,
                Core.graphics.getHeight() * (1f - py / 100f) - label.getHeight());
        });
        Core.scene.add(label);
    }

    // ------------------------------------------------------------------ hilo de juego

    private void frame(){
        frameCount.incrementAndGet();
        if((counter++ & 31) == 0){
            try{
                readSettings();
            }catch(Throwable t){
                Log.err("[MO] SysMonitor.readSettings", t);
            }
        }
        if(gpuOn){
            try{
                gpu.frame(gpuNs, gpuFrames);
            }catch(Throwable t){
                gpuOn = false;
                Log.err("[MO] consulta de GPU falló; se desactiva", t);
            }
        }
    }

    private void readSettings(){
        mode = Core.settings.getInt(K_MODE, 2);
        balance = Core.settings.getBool(K_BAL, true);
        px = Core.settings.getInt(K_X, 41);
        py = Core.settings.getInt(K_Y, 13);

        boolean need = mode > 0 || balance;
        if(need && !running) startSampler();
        else if(!need && running) stopSampler();

        boolean wantGpu = mode > 0;
        if(wantGpu && !gpuOn && !gpu.failed){
            gpuOn = gpu.init();
        }else if(!wantGpu && gpuOn){
            gpu.stop();
            gpuOn = false;
        }
    }

    // ------------------------------------------------------------------ hilo de muestreo

    private void startSampler(){
        running = true;
        sampler = new Thread(this::samplerLoop, "MO-Monitor");
        sampler.setDaemon(true);
        sampler.start();
    }

    private void stopSampler(){
        running = false;
        Thread t = sampler;
        sampler = null;
        if(t != null) t.interrupt();
    }

    private void samplerLoop(){
        setPriority(0, 10); // THREAD_PRIORITY_BACKGROUND: que corra en núcleos débiles y no moleste al juego
        long last = System.nanoTime();
        int round = 0;

        while(running){
            try{
                Thread.sleep(500);
            }catch(InterruptedException e){
                if(!running) break;
            }

            try{
                long now = System.nanoTime();
                double wall = Math.max(0.05, (now - last) / 1e9);
                last = now;

                sampleThreads(wall);
                sampleSystem();
                String t = compose(wall);
                text = t;

                if(mode > 0 && round % 20 == 19){
                    Log.info("[MO] monitor | " + t.replace("\n", " | ").replaceAll("\\[[^\\]]*\\]", ""));
                }
                if(balance && round % 4 == 3){
                    balanceThreads();
                }
                round++;
            }catch(Throwable th){
                Log.err("[MO] muestreo falló", th);
                try{
                    Thread.sleep(2000);
                }catch(InterruptedException ignored){
                }
            }
        }
    }

    private void sampleThreads(double wall){
        String[] list = new File("/proc/self/task").list();
        if(list == null) return;

        Arrays.fill(coreApp, 0.0);
        HashSet<Integer> alive = new HashSet<>();

        for(String s : list){
            int tid;
            try{
                tid = Integer.parseInt(s);
            }catch(NumberFormatException e){
                continue;
            }
            String line = firstLine("/proc/self/task/" + s + "/stat");
            if(line == null) continue;

            int lp = line.indexOf('('), rp = line.lastIndexOf(')');
            if(lp < 0 || rp < lp || rp + 2 > line.length()) continue;
            String[] f = line.substring(rp + 2).split(" ");
            if(f.length < 37) continue;

            long ticks;
            int core;
            try{
                ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime + stime (100 Hz)
                core = Integer.parseInt(f[36]);                          // último núcleo en el que corrió
            }catch(NumberFormatException e){
                continue;
            }

            Th t = threads.get(tid);
            if(t == null){
                t = new Th();
                t.tid = tid;
                threads.put(tid, t);
            }
            t.name = line.substring(lp + 1, rp);
            if(t.last >= 0){
                double sec = Math.max(0, ticks - t.last) / 100.0;
                t.pct = (float)Math.min(100.0, sec / wall * 100.0);
                if(core >= 0 && core < MAX_CORES) coreApp[core] += sec;
            }
            t.last = ticks;
            t.core = core;
            alive.add(tid);
        }
        threads.keySet().retainAll(alive);
    }

    private void sampleSystem(){
        if(!sysOk) return;
        List<String> lines = readLines("/proc/stat", 80);
        if(lines == null){
            sysOk = false;
            return;
        }

        boolean any = false;
        for(String line : lines){
            if(line.length() < 4 || !line.startsWith("cpu") || !Character.isDigit(line.charAt(3))) continue;
            String[] p = line.split("\\s+");
            int idx;
            try{
                idx = Integer.parseInt(p[0].substring(3));
            }catch(NumberFormatException e){
                continue;
            }
            if(idx < 0 || idx >= MAX_CORES || p.length < 8) continue;

            long total = 0;
            long idle;
            try{
                for(int i = 1; i <= 8 && i < p.length; i++) total += Long.parseLong(p[i]);
                idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
            }catch(NumberFormatException e){
                continue;
            }
            if(sysPrimed){
                long dt = total - prevTotal[idx];
                long di = idle - prevIdle[idx];
                sysPct[idx] = dt <= 0 ? 0f : Math.max(0f, Math.min(100f, 100f * (dt - di) / dt));
            }
            prevTotal[idx] = total;
            prevIdle[idx] = idle;
            any = true;
        }
        if(!any) sysOk = false;
        sysPrimed = true;
    }

    // ------------------------------------------------------------------ texto

    private static String col(float pct){
        return pct >= 85f ? "[scarlet]" : pct >= 55f ? "[orange]" : pct >= 25f ? "[yellow]" : "[lightgray]";
    }

    private String compose(double wall){
        int n = Math.max(1, Math.min(MAX_CORES, Cores.get().logical));
        StringBuilder sb = new StringBuilder(256);

        sb.append("[accent]CPU").append(sysOk ? "" : " (app)").append("[]");
        for(int c = 0; c < n; c++){
            if(c % 4 == 0) sb.append('\n');
            float v = sysOk ? sysPct[c] : (float)Math.min(100.0, coreApp[c] / wall * 100.0);
            boolean strong = c < Cores.get().strong.length && Cores.get().strong[c];
            sb.append(strong ? "[white]" : "[gray]").append('c').append(c).append("[] ")
                .append(col(v)).append(pad((int)Math.round(v))).append("%[]  ");
        }

        // GPU
        long ns = gpuNs.getAndSet(0L);
        int gf = gpuFrames.getAndSet(0);
        int frames = frameCount.getAndSet(0);
        sb.append("\n[accent]GPU[] ");
        if(gpuOn && gf > 0){
            float busy = (float)Math.min(100.0, ns / (wall * 1e9) * 100.0);
            sb.append(col(busy)).append(Math.round(busy)).append("%[] ")
                .append(String.format("%.1fms/frame", ns / 1e6 / gf));
        }else{
            sb.append("[gray]sin dato[]");
        }
        int hw = readHwGpu();
        if(hw >= 0) sb.append("  [lightgray]hw ").append(hw).append("%[]");
        sb.append("  [gray]").append(Math.round(frames / wall)).append(" f/s[]");

        // hilos más pesados del proceso
        ArrayList<Th> list = new ArrayList<>(threads.values());
        Collections.sort(list, (a, b) -> Float.compare(b.pct, a.pct));
        sb.append("\n[accent]Hilos[]");
        for(int i = 0; i < Math.min(3, list.size()); i++){
            Th t = list.get(i);
            if(t.pct < 1f) break;
            String nm = t.name.length() > 14 ? t.name.substring(0, 14) : t.name;
            sb.append(' ').append(col(t.pct)).append(nm).append(' ').append(Math.round(t.pct)).append("%@c").append(t.core).append("[] ");
        }
        return sb.toString();
    }

    private static String pad(int v){
        return v < 10 ? "  " + v : v < 100 ? " " + v : "" + v;
    }

    // ------------------------------------------------------------------ balanceador

    private static final int[] RANK_PRIO = {-8, -4, -2};

    private void balanceThreads(){
        ArrayList<Th> list = new ArrayList<>(threads.values());
        Collections.sort(list, (a, b) -> Float.compare(b.pct, a.pct));
        int rank = 0;
        for(Th t : list){
            if(rank >= RANK_PRIO.length || t.pct < 25f) break;
            if(t.name.startsWith("MO-Monitor")) continue;
            int want = RANK_PRIO[rank++];
            if(t.prio != want && setPriority(t.tid, want)){
                Log.info("[MO] balanceador: " + t.name + " (tid " + t.tid + ", " + Math.round(t.pct) + "%) -> prioridad " + want);
                t.prio = want;
            }
        }
    }

    /** tid 0 = hilo actual. */
    private boolean setPriority(int tid, int prio){
        try{
            if(mSetPrio == null){
                mSetPrio = Class.forName("android.os.Process").getMethod("setThreadPriority", int.class, int.class);
            }
            mSetPrio.invoke(null, tid, prio);
            return true;
        }catch(Throwable t){
            return false; // no es Android o el sistema lo rechazó
        }
    }

    // ------------------------------------------------------------------ utilidades de lectura

    private int readHwGpu(){
        if(!hwGpuChecked){
            hwGpuChecked = true;
            String[] cand = {
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/kernel/gpu/gpu_busy",
                "/sys/class/misc/mali0/device/utilisation"
            };
            for(String p : cand){
                if(parseLeadingInt(firstLine(p)) >= 0){
                    hwGpuPath = p;
                    break;
                }
            }
        }
        return hwGpuPath == null ? -1 : parseLeadingInt(firstLine(hwGpuPath));
    }

    private static int parseLeadingInt(String s){
        if(s == null) return -1;
        s = s.trim();
        int i = 0;
        while(i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if(i == 0) return -1;
        try{
            return Integer.parseInt(s.substring(0, i));
        }catch(NumberFormatException e){
            return -1;
        }
    }

    private static String firstLine(String path){
        try(BufferedReader r = new BufferedReader(new FileReader(path))){
            return r.readLine();
        }catch(Throwable t){
            return null;
        }
    }

    private static List<String> readLines(String path, int max){
        try(BufferedReader r = new BufferedReader(new FileReader(path))){
            ArrayList<String> out = new ArrayList<>();
            String l;
            while((l = r.readLine()) != null && out.size() < max) out.add(l);
            return out.isEmpty() ? null : out;
        }catch(Throwable t){
            return null;
        }
    }

    // ------------------------------------------------------------------ GPU: GL_EXT_disjoint_timer_query

    /**
     * Mide cuánto tarda la GPU en ejecutar los comandos de cada frame. La consulta se cierra y se abre al final de
     * cada frame (en el hilo de GL), así que abarca todo el trabajo del frame. Usa android.opengl.GLES30 por reflexión.
     */
    private static final class GpuTimer{
        static final int N = 4, TIME_ELAPSED = 0x88BF, RESULT = 0x8866, AVAILABLE = 0x8867, DISJOINT = 0x8FBB;

        boolean failed, ready;
        private Method mBegin, mEnd, mGet, mInt;
        private final int[] ids = new int[N];
        private final int[] tmp = new int[1];
        private int next, oldest, inFlight, dry;
        private boolean open;

        boolean init(){
            try{
                Class<?> g = Class.forName("android.opengl.GLES30");
                String ext = (String)g.getMethod("glGetString", int.class).invoke(null, 0x1F03);
                if(ext == null || !ext.contains("GL_EXT_disjoint_timer_query")){
                    failed = true;
                    Log.info("[MO] GPU: el driver no expone GL_EXT_disjoint_timer_query; sin medición de GPU");
                    return false;
                }
                g.getMethod("glGenQueries", int.class, int[].class, int.class).invoke(null, N, ids, 0);
                mBegin = g.getMethod("glBeginQuery", int.class, int.class);
                mEnd = g.getMethod("glEndQuery", int.class);
                mGet = g.getMethod("glGetQueryObjectuiv", int.class, int.class, int[].class, int.class);
                mInt = g.getMethod("glGetIntegerv", int.class, int[].class, int.class);
                if(ids[0] == 0){
                    failed = true;
                    Log.info("[MO] GPU: no se pudieron crear consultas; sin medición de GPU");
                    return false;
                }
                ready = true;
                next = oldest = inFlight = dry = 0;
                open = false;
                Log.info("[MO] GPU: medición por timer query activa");
                return true;
            }catch(Throwable t){
                failed = true;
                Log.err("[MO] GPU: no se pudo iniciar la medición", t);
                return false;
            }
        }

        void stop(){
            try{
                if(open && ready){
                    mEnd.invoke(null, TIME_ELAPSED);
                    open = false;
                }
            }catch(Throwable ignored){
            }
            ready = false;
        }

        void frame(AtomicLong acc, AtomicInteger frames) throws Exception{
            if(!ready) return;

            if(open){
                mEnd.invoke(null, TIME_ELAPSED);
                open = false;
                inFlight++;
            }

            mInt.invoke(null, DISJOINT, tmp, 0);
            boolean disjoint = tmp[0] != 0;

            boolean got = false;
            while(inFlight > 0){
                int id = ids[oldest];
                mGet.invoke(null, id, AVAILABLE, tmp, 0);
                if(tmp[0] == 0) break;
                mGet.invoke(null, id, RESULT, tmp, 0);
                if(!disjoint){
                    acc.addAndGet(tmp[0] & 0xFFFFFFFFL);
                    frames.incrementAndGet();
                }
                oldest = (oldest + 1) % N;
                inFlight--;
                got = true;
            }

            // Si en ~4 s nunca llega un resultado, el driver no lo soporta de verdad.
            dry = got ? 0 : dry + 1;
            if(dry > 240){
                failed = true;
                ready = false;
                Log.info("[MO] GPU: la medición no devuelve resultados; desactivada");
                return;
            }

            if(inFlight < N){
                mBegin.invoke(null, TIME_ELAPSED, ids[next]);
                open = true;
                next = (next + 1) % N;
            }
        }
    }
}
