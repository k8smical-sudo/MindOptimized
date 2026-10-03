package template;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Groups;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Simplificador de las colisiones/empuje entre unidades en batallas grandes.
 *
 * El juego resuelve el empuje entre unidades (PhysicsProcess) como un proceso asíncrono que se ejecuta cada frame en
 * otro hilo. Con cientos de unidades juntas es lo que más CPU consume. Aquí se envuelve ese proceso con un proxy que:
 *
 *  1. MIDE cuánto cuesta cada ejecución (en el hilo de trabajo, o sea CPU real, no solo bloqueo del frame).
 *  2. Si cuesta más que el presupuesto en ms por frame, lo ejecuta solo 1 de cada k frames (k adaptativo, 1..máximo),
 *     de forma que el coste medio por frame se acerque al presupuesto.
 *
 * Qué se pierde: en los frames saltados las unidades no se empujan entre sí, así que en multitudes se solapan algo más.
 * No toca balas, IA, colisiones con bloques ni partidas en red (con red activa k vuelve a 1).
 *
 * Todo va por reflexión: no depende de nombres de clases del juego al compilar. Si no encuentra algo, se desactiva y lo
 * dice en el log con el prefijo [MO].
 */
public class PhysicsThrottle{
    public static final String K_BUDGET = "mo-phys-budget"; // ms por frame; 0 = desactivado
    public static final String K_MAX = "mo-phys-max";       // máximo de frames entre ejecuciones

    private Seq<Object> holder;
    private int index = -1;
    private Object original, proxy;
    private boolean failed;

    private volatile long runCostNs;           // media móvil del coste de process() (se escribe desde el hilo de trabajo)
    private volatile boolean run = true;
    private volatile int k = 1;
    private int frame, polls, execs, skips;
    private float budgetMs;
    private int maxK = 4;

    public void install(){
        Events.run(Trigger.update, this::poll);
    }

    // ------------------------------------------------------------------ sondeo (hilo principal)

    private void poll(){
        polls++;
        if((polls & 15) == 0){
            budgetMs = Core.settings.getInt(K_BUDGET, 2);
            maxK = Math.max(1, Math.min(8, Core.settings.getInt(K_MAX, 4)));
            try{
                if(budgetMs > 0 && proxy == null && !failed) attach();
                if((budgetMs <= 0 || failed) && proxy != null) detach();
            }catch(Throwable t){
                failed = true;
                Log.err("[MO] PhysicsThrottle: fallo; se desactiva", t);
                try{
                    detach();
                }catch(Throwable ignored){
                }
            }
        }

        if(proxy == null) return;

        // Ajuste de k cada 30 frames, un paso cada vez para evitar oscilaciones.
        if(polls % 30 == 0){
            if(Vars.net.active() || !Vars.state.isGame()){
                k = 1;
            }else{
                float costMs = runCostNs / 1_000_000f;
                int target = costMs <= 0f ? 1 : (int)Math.ceil(costMs / Math.max(0.1f, budgetMs));
                target = Math.max(1, Math.min(maxK, target));
                int cur = k;
                if(target > cur) k = cur + 1;
                else if(target < cur) k = cur - 1;
            }
        }

        if(polls % 600 == 0){
            Log.info(String.format("[MO] física: unidades=%d coste/ejecución=%.2fms salto=1/%d (ejecutó %d, saltó %d)",
                Groups.unit.size(), runCostNs / 1_000_000f, k, execs, skips));
            execs = skips = 0;
        }
    }

    // ------------------------------------------------------------------ instalación

    @SuppressWarnings("unchecked")
    private void attach() throws Exception{
        Class<?> process = Class.forName("mindustry.async.AsyncProcess");
        Object core = findAsyncCore();
        if(core == null){
            failed = true;
            Log.err("[MO] PhysicsThrottle: no se encontró AsyncCore; función desactivada");
            return;
        }

        for(Class<?> c = core.getClass(); c != null && c != Object.class; c = c.getSuperclass()){
            for(Field f : c.getDeclaredFields()){
                if(Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(core);
                if(!(v instanceof Seq)) continue;

                Seq<Object> seq = (Seq<Object>)v;
                for(int i = 0; i < seq.size; i++){
                    Object o = seq.get(i);
                    if(o != null && o.getClass().getSimpleName().equals("PhysicsProcess")){
                        original = o;
                        holder = seq;
                        index = i;
                        proxy = Proxy.newProxyInstance(process.getClassLoader(), new Class<?>[]{process}, new Handler());
                        seq.set(i, proxy);
                        k = 1;
                        Log.info("[MO] PhysicsThrottle: física de unidades envuelta (presupuesto " + budgetMs + " ms)");
                        return;
                    }
                }
            }
        }

        failed = true;
        Log.err("[MO] PhysicsThrottle: no se encontró PhysicsProcess; función desactivada");
    }

    private void detach(){
        if(proxy == null) return;
        if(holder != null && index >= 0 && index < holder.size && holder.get(index) == proxy){
            holder.set(index, original);
        }
        proxy = null;
        original = null;
        holder = null;
        index = -1;
        run = true;
        k = 1;
        Log.info("[MO] PhysicsThrottle: física de unidades restaurada");
    }

    /** Busca la instancia de AsyncCore en los campos estáticos de Vars y en los del launcher principal. */
    @SuppressWarnings("unchecked")
    private Object findAsyncCore(){
        try{
            for(Field f : Vars.class.getDeclaredFields()){
                if(!Modifier.isStatic(f.getModifiers())) continue;
                if(f.getType().getSimpleName().equals("AsyncCore")){
                    f.setAccessible(true);
                    Object o = f.get(null);
                    if(o != null) return o;
                }
            }
        }catch(Throwable ignored){
        }

        try{
            Method gl = Core.app.getClass().getMethod("getListeners");
            Seq<Object> roots = (Seq<Object>)gl.invoke(Core.app);
            for(Object root : roots){
                for(Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass()){
                    for(Field f : c.getDeclaredFields()){
                        if(Modifier.isStatic(f.getModifiers())) continue;
                        if(f.getType().getSimpleName().equals("AsyncCore")){
                            f.setAccessible(true);
                            Object o = f.get(root);
                            if(o != null) return o;
                        }
                    }
                }
            }
        }catch(Throwable ignored){
        }
        return null;
    }

    // ------------------------------------------------------------------ proxy

    /** Reenvía todo al PhysicsProcess original, salvo begin/process/end en los frames que se saltan. */
    private class Handler implements InvocationHandler{
        @Override
        public Object invoke(Object self, Method m, Object[] args) throws Throwable{
            String name = m.getName();

            if(m.getDeclaringClass() == Object.class){
                switch(name){
                    case "equals": return self == args[0];
                    case "hashCode": return System.identityHashCode(self);
                    default: return "MO-ThrottledPhysics";
                }
            }

            try{
                switch(name){
                    case "begin" -> {
                        frame++;
                        int kk = k;
                        run = kk <= 1 || (frame % kk) == 0;
                        if(run) execs++;
                        else skips++;
                        if(!run) return null;
                    }
                    case "process" -> {
                        if(!run) return null;
                        long t0 = Time.nanos();
                        try{
                            return m.invoke(original, args);
                        }finally{
                            long dt = Time.nanos() - t0;
                            long prev = runCostNs;
                            runCostNs = prev == 0L ? dt : (prev * 7L + dt) / 8L;
                        }
                    }
                    case "end" -> {
                        if(!run) return null;
                    }
                    default -> {
                    }
                }
                return m.invoke(original, args);
            }catch(InvocationTargetException e){
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
