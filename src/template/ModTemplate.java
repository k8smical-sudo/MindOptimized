package template;

import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;

/** MindOptimized: recorte agresivo de vertices del render dinamico (ver RenderCuller). */
public class ModTemplate extends Mod{
    private RenderCuller culler;

    public ModTemplate(){
        culler = new RenderCuller();

        Events.on(ClientLoadEvent.class, e -> {
            Log.info("[MO] cargado. build=" + Version.build + " type=" + Version.type + " number=" + Version.number);
            try{
                buildSettings();
            }catch(Throwable t){
                Log.err("[MO] ajustes fallaron: " + t);
            }
        });
    }

    private static void title(String key, String text, String desc){
        Core.bundle.getProperties().put("setting." + key + ".name", text);
        if(desc != null) Core.bundle.getProperties().put("setting." + key + ".description", desc);
    }

    private void buildSettings(){
        title(RenderCuller.K_ON, "MindOptimized activo", "Interruptor general. Apagado = render 100% vanilla (sirve para comparar ms).");
        title(RenderCuller.K_FOG, "Ocultar edificios recordados bajo niebla", "No dibuja edificios que viste antes pero ahora estan bajo niebla.");
        title(RenderCuller.K_ICON, "LOD de icono (px por casilla)", "Cuando una casilla mide menos de N pixeles en pantalla, cada edificio se dibuja con 1 solo sprite. 0 = off.");
        title(RenderCuller.K_SOLID, "LOD solido (px por casilla)", "Mas lejos aun: casillas 1x1 como color plano y fusionadas en rectangulos (menos vertices). 0 = off.");
        title(RenderCuller.K_MERGE, "Fusionar rectangulos (enmallado)", "Une casillas vecinas del mismo color en un solo quad.");
        title(RenderCuller.K_STATS, "Estadisticas en el log", "Cada 600 frames imprime vertices ahorrados y coste en ms.");

        Vars.ui.settings.addCategory("MindOptimized", Icon.settings, t -> {
            t.checkPref(RenderCuller.K_ON, true);
            t.checkPref(RenderCuller.K_FOG, true);
            t.sliderPref(RenderCuller.K_ICON, 14, 0, 40, 1, i -> i <= 0 ? "off" : i + " px");
            t.sliderPref(RenderCuller.K_SOLID, 6, 0, 24, 1, i -> i <= 0 ? "off" : i + " px");
            t.checkPref(RenderCuller.K_MERGE, true);
            t.checkPref(RenderCuller.K_STATS, true);
        });
    }
}
