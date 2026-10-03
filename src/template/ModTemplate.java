package template;

import arc.*;
import arc.func.*;
import arc.graphics.g2d.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.ui.*;

/**
 * MindOptimized — arranque y registro de ajustes.
 * El LOD de unidades se inyecta intercalando drawUnit() antes de unit.draw().
 */
public class ModTemplate extends Mod{
    private RenderCuller culler;

    public ModTemplate(){
        culler = new RenderCuller();

        // Interceptar el dibujo de unidades: Groups.draw.draw() acepta Cons<Drawc>.
        // Lo reemplazamos con una lambda que decide si usa LOD o el draw normal.
        Events.run(Trigger.draw, () -> {
            if(!Vars.state.isGame()) return;
            Groups.draw.draw(d -> {
                if(d instanceof Unit u){
                    if(!culler.drawUnit(u)) u.draw();
                }else{
                    d.draw();
                }
            });
        });

        Events.on(ClientLoadEvent.class, e -> {
            Log.info("[MO] v2 cargado. build=" + Version.build);
            buildSettings();
        });
    }

    private static void t(String key, String name, String desc){
        Core.bundle.getProperties().put("setting." + key + ".name", name);
        if(desc != null) Core.bundle.getProperties().put("setting." + key + ".description", desc);
    }

    private void buildSettings(){
        t(RenderCuller.K_ON,      "MindOptimized activo",              "Interruptor general. Apagado = vanilla puro.");
        t(RenderCuller.K_FOG,     "Omitir edificios bajo niebla",      "Edificios 'recordados' pero bajo niebla = 0 vértices.");
        t(RenderCuller.K_ICON,    "LOD icono bloques (px/casilla)",     "≤N px: 1 quad con el sprite completo del bloque. 0 = off.");
        t(RenderCuller.K_SOLID,   "LOD sólido bloques (px/casilla)",    "≤N px: color plano del bloque, fusionado en rectángulos. 0 = off.");
        t(RenderCuller.K_MERGE,   "Enmallado voraz (bloques)",          "Une casillas contiguas del mismo color en 1 quad.");
        t(RenderCuller.K_UICON,   "LOD icono unidades (px)",            "Unidades < N px en pantalla: 1 quad con su sprite base. 0 = off.");
        t(RenderCuller.K_USOLID,  "LOD sólido unidades (px)",           "Unidades < N px: rect de color de equipo. 0 = off.");
        t(RenderCuller.K_SLEEP,   "Sleep de fábricas fuera de vista",   "Fábricas (taladros, crafters) fuera de cámara se duermen. Producción conservada con catch-up.");
        t(RenderCuller.K_SLEEP_HZ,"Tickrate fuera de vista (hz)",       "Hz efectivos de las fábricas dormidas. 10 = 1 tick cada 6 ticks reales.");
        t(RenderCuller.K_SCISSOR, "Recorte de cámara activo",           "Solo renderiza el porcentaje de pantalla configurado; el resto es negro.");
        t(RenderCuller.K_SCIS_W,  "Recorte: ancho (%)",                 "Porcentaje del ancho de pantalla que se renderiza.");
        t(RenderCuller.K_SCIS_H,  "Recorte: alto (%)",                  "Porcentaje del alto de pantalla que se renderiza.");
        t(RenderCuller.K_STATS,   "Estadísticas en el log",             "Cada 600 frames imprime métricas de vértices y tiempos.");

        Vars.ui.settings.addCategory("MindOptimized", Icon.settings, t -> {
            t.checkPref(RenderCuller.K_ON,     true);
            t.checkPref(RenderCuller.K_FOG,    true);
            t.sliderPref(RenderCuller.K_ICON,  14, 0, 40, 1, i -> i <= 0 ? "off" : i + " px");
            t.sliderPref(RenderCuller.K_SOLID,  6, 0, 24, 1, i -> i <= 0 ? "off" : i + " px");
            t.checkPref(RenderCuller.K_MERGE,  true);
            t.sliderPref(RenderCuller.K_UICON, 12, 0, 60, 1, i -> i <= 0 ? "off" : i + " px");
            t.sliderPref(RenderCuller.K_USOLID, 5, 0, 30, 1, i -> i <= 0 ? "off" : i + " px");
            t.checkPref(RenderCuller.K_SLEEP,  true);
            t.sliderPref(RenderCuller.K_SLEEP_HZ, 10, 1, 60, 1, i -> i >= 60 ? "sync (60 hz)" : i + " hz");
            t.checkPref(RenderCuller.K_SCISSOR, false);
            t.sliderPref(RenderCuller.K_SCIS_W, 100, 20, 100, 5, i -> i + "%");
            t.sliderPref(RenderCuller.K_SCIS_H, 100, 20, 100, 5, i -> i + "%");
            t.checkPref(RenderCuller.K_STATS,  true);
        });
    }
}
