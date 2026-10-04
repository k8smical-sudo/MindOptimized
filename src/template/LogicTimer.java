package template;

import arc.ApplicationListener;
import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Mide cuánto tarda Logic.update() cada frame (incluye todas las entidades y edificios). Se coloca en la lista de
 * módulos del juego en lugar de Logic y reenvía todo a "inner". Lo usa ConveyorLOD para ajustarse a la carga real.
 *
 * Si el gobernador de tickrate (PerfTuner) se instala después, se coloca DENTRO de este envoltorio (inner = gobernador),
 * así que ambos conviven.
 */
public final class LogicTimer implements ApplicationListener{
    public static LogicTimer inst;

    public ApplicationListener inner;
    private long ema; // ns, media móvil

    private LogicTimer(ApplicationListener inner){
        this.inner = inner;
    }

    /** Media móvil de lo que tarda la lógica por frame, en ms. */
    public static float ms(){
        return inst == null ? 0f : inst.ema / 1_000_000f;
    }

    @SuppressWarnings("unchecked")
    public static boolean install(){
        if(inst != null) return true;
        try{
            if(Vars.logic == null) return false;
            Method gl = Core.app.getClass().getMethod("getListeners");
            Seq<ApplicationListener> roots = (Seq<ApplicationListener>)gl.invoke(Core.app);

            for(ApplicationListener root : roots){
                Field f = null;
                for(Class<?> c = root.getClass(); c != null && f == null; c = c.getSuperclass()){
                    try{
                        f = c.getDeclaredField("modules");
                    }catch(NoSuchFieldException ignored){
                    }
                }
                if(f == null) continue;
                f.setAccessible(true);
                if(!(f.get(root) instanceof ApplicationListener[] arr)) continue;

                for(int i = 0; i < arr.length; i++){
                    if(arr[i] == Vars.logic){
                        LogicTimer t = new LogicTimer(Vars.logic);
                        arr[i] = t;
                        inst = t;
                        Log.info("[MO] medidor de lógica instalado");
                        return true;
                    }
                }
            }
            Log.err("[MO] medidor de lógica: no se encontró Logic en los módulos (¿ya envuelto?); el control adaptativo usará nivel fijo");
        }catch(Throwable t){
            Log.err("[MO] medidor de lógica: instalación falló", t);
        }
        return false;
    }

    @Override
    public void update(){
        long t0 = Time.nanos();
        try{
            inner.update();
        }finally{
            long dt = Time.nanos() - t0;
            ema = ema == 0L ? dt : (ema * 15L + dt) / 16L;
        }
    }

    @Override
    public void resize(int w, int h){
        inner.resize(w, h);
    }

    @Override
    public void pause(){
        inner.pause();
    }

    @Override
    public void resume(){
        inner.resume();
    }

    @Override
    public void dispose(){
        inner.dispose();
    }
}
