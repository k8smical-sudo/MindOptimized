package template;

import arc.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detecta los núcleos del procesador y reparte trabajo entre ellos.
 *
 * IMPORTANTE: solo es seguro usar esto para trabajo de SOLO LECTURA sobre el mundo mientras el hilo principal espera
 * (por ejemplo, clasificar tiles para el render). La simulación (Building.updateTile, Groups.update...) muta estado
 * compartido y NO es thread-safe: no se paraleliza aquí.
 */
public final class Cores{
    /** Trabajo sobre el rango [from, to). */
    public interface Range{
        void run(int from, int to);
    }

    private static final Cores inst = new Cores();

    public static Cores get(){
        return inst;
    }

    public int logical = 1, fast = 1, little = 0, maxMHz;
    /** strong[i] = el núcleo i es de los potentes (según cpu_capacity o frecuencia máxima). */
    public boolean[] strong = new boolean[0];
    public String summary = "CPU sin detectar";

    /** Hilos que participan en un reparto, contando el hilo que llama. 1 = todo en serie. */
    private int threads = 1;
    private ExecutorService pool;
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private Method setPriority;

    private Cores(){
    }

    /** Lee cantidad de núcleos y, si el sistema lo permite, la frecuencia máxima de cada uno (big.LITTLE). */
    public void detect(){
        try{
            logical = Math.max(1, Runtime.getRuntime().availableProcessors());
            int[] freq = new int[logical];
            int[] cap = new int[logical];
            int max = 0, maxCap = 0;
            for(int i = 0; i < logical; i++){
                freq[i] = readInt("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                cap[i] = readInt("/sys/devices/system/cpu/cpu" + i + "/cpu_capacity"); // 1024 = núcleo más potente
                max = Math.max(max, freq[i]);
                maxCap = Math.max(maxCap, cap[i]);
            }

            // cpu_capacity distingue núcleos aunque reporten la misma frecuencia máxima; si no existe, se usa la frecuencia.
            int[] metric = maxCap > 0 ? cap : freq;
            int mMax = maxCap > 0 ? maxCap : max;
            strong = new boolean[logical];

            int f = 0, l = 0;
            for(int i = 0; i < logical; i++){
                // Sin dato legible: se cuenta como rápido para no infravalorar el equipo.
                boolean weak = mMax > 0 && metric[i] > 0 && metric[i] < mMax * 0.7f;
                strong[i] = !weak;
                if(weak) l++;
                else f++;
            }
            fast = Math.max(1, f);
            little = l;
            maxMHz = max / 1000;
            summary = logical + " núcleos (" + fast + " rápidos, " + little + " de eficiencia)"
                + (maxMHz > 0 ? ", máx " + maxMHz + " MHz" : "") + " → hilos auto: " + autoThreads();
        }catch(Throwable t){
            Log.err("[MO] detect CPU falló", t);
        }
        Log.info("[MO] " + summary);
    }

    /** Hilos recomendados: núcleos rápidos (el hilo principal cuenta como uno), máximo 8. */
    public int autoThreads(){
        return Math.max(1, Math.min(8, fast));
    }

    public int threads(){
        return threads;
    }

    /** @param wanted 0 = automático; otro valor = hilos totales (incluye el principal). No hace nada si no cambia. */
    public void configure(int wanted){
        int target = wanted > 0 ? Math.min(wanted, 16) : autoThreads();
        if(target == threads && (pool != null || target == 1)) return;

        shutdown();
        threads = target;
        if(target > 1){
            final AtomicInteger n = new AtomicInteger();
            pool = Executors.newFixedThreadPool(target - 1, new ThreadFactory(){
                @Override
                public Thread newThread(Runnable r){
                    Thread t = new Thread(() -> {
                        boostPriority();
                        r.run();
                    }, "MO-Worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        Log.info("[MO] reparto: " + threads + " hilo(s) (" + (threads - 1) + " trabajador(es) + principal)");
    }

    public void shutdown(){
        if(pool != null){
            pool.shutdownNow();
            pool = null;
        }
        threads = 1;
    }

    private void boostPriority(){
        // android.os.Process.setThreadPriority(THREAD_PRIORITY_DISPLAY = -4). Por reflexión: no existe al compilar.
        try{
            if(setPriority == null){
                setPriority = Class.forName("android.os.Process").getMethod("setThreadPriority", int.class);
            }
            setPriority.invoke(null, -4);
        }catch(Throwable ignored){
        }
    }

    /**
     * Reparte [0, n) en trozos y espera a que todos terminen. El hilo que llama también trabaja.
     * Si n es pequeño (menos de 2*minChunk) o hay un solo hilo, corre todo en serie.
     * Si algún trozo lanza una excepción, la relanza tras esperar a los demás.
     *
     * @return true si de verdad se repartió entre hilos.
     */
    public boolean parallelFor(int n, int minChunk, Range body){
        ExecutorService ex = pool;
        int chunks = ex == null ? 1 : Math.min(threads, n / Math.max(1, minChunk));
        if(chunks <= 1){
            body.run(0, n);
            return false;
        }

        int per = (n + chunks - 1) / chunks;
        CountDownLatch latch = new CountDownLatch(chunks - 1);
        error.set(null);

        for(int c = 1; c < chunks; c++){
            final int a = c * per, b = Math.min(n, a + per);
            if(a >= b){
                latch.countDown();
                continue;
            }
            ex.execute(() -> {
                try{
                    body.run(a, b);
                }catch(Throwable th){
                    error.compareAndSet(null, th);
                }finally{
                    latch.countDown();
                }
            });
        }

        try{
            body.run(0, Math.min(n, per));
        }catch(Throwable th){
            error.compareAndSet(null, th);
        }

        boolean interrupted = false;
        while(true){
            try{
                latch.await();
                break;
            }catch(InterruptedException e){
                interrupted = true;
            }
        }
        if(interrupted) Thread.currentThread().interrupt();

        Throwable th = error.getAndSet(null);
        if(th != null) throw new RuntimeException("parallelFor", th);
        return true;
    }

    private static int readInt(String path){
        try(BufferedReader r = new BufferedReader(new FileReader(path))){
            String line = r.readLine();
            return line == null ? 0 : Integer.parseInt(line.trim());
        }catch(Throwable t){
            return 0;
        }
    }
}
