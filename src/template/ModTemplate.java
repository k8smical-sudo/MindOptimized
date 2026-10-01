package template;

import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;

import java.lang.reflect.*;

/** Fase 0: solo observa. No cambia el render. */
public class ModTemplate extends Mod{
    private long drawStart, drawAccum;
    private int drawFrames;

    public ModTemplate(){
        // Tiempo entre preDraw y postDraw (mide el dibujo del mundo, no todo el frame)
        Events.run(Trigger.preDraw, () -> drawStart = Time.nanos());
        Events.run(Trigger.postDraw, () -> {
            drawAccum += Time.nanos() - drawStart;
            if(++drawFrames >= 300){
                Log.info("[probe] mundo medio: " + (drawAccum / drawFrames / 1_000_000.0) + " ms/frame");
                drawAccum = 0;
                drawFrames = 0;
            }
        });

        Events.on(ClientLoadEvent.class, e -> {
            Log.info("[probe] cargado. Version: build=" + Version.build
                + " type=" + Version.type + " number=" + Version.number
                + " revision=" + Version.revision);
            dump("BlockRenderer", Vars.renderer.blocks.getClass());
            dump("Renderer", Vars.renderer.getClass());
        });
    }

    /** Lista campos y metodos declarados para localizar donde engancharnos. */
    private void dump(String label, Class<?> c){
        for(Field f : c.getDeclaredFields())
            Log.info("[probe] " + label + " campo: " + Modifier.toString(f.getModifiers()) + " " + f.getType().getSimpleName() + " " + f.getName());
        for(Method m : c.getDeclaredMethods())
            Log.info("[probe] " + label + " metodo: " + Modifier.toString(m.getModifiers()) + " " + m.getReturnType().getSimpleName() + " " + m.getName() + "(" + m.getParameterCount() + ")");
    }
}
