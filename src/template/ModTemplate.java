package template;

import arc.Core;
import arc.Events;
import arc.scene.style.Drawable;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Unit;
import mindustry.mod.Mod;

public class ModTemplate extends Mod{
    /**
     * Se crea de forma perezosa (en ClientLoadEvent) y NO en el constructor.
     * El constructor del mod se ejecuta durante Mods.load() al iniciar el juego; si algo lanza
     * una excepción ahí, Mindustry descarta el mod en silencio ("Failed to load mod file. Skipping")
     * y simplemente desaparece de la lista tras reiniciar, sin crash ni aviso en pantalla.
     */
    private RenderCuller culler;
    private FloatWindow win;
    private PerfTuner tuner;
    private boolean failed;

    public ModTemplate(){
        // Constructor deliberadamente mínimo: solo registra listeners, no toca GL ni reflexión.
        try{
            Events.on(ClientLoadEvent.class, e -> {
                // Núcleos: se detectan una vez al iniciar y el pool se dimensiona según el procesador.
                try{
                    Cores.get().detect();
                    Cores.get().configure(Core.settings.getInt(RenderCuller.K_THREADS, 0));
                }catch(Throwable t){
                    Log.err("[MO] no se pudo iniciar Cores", t);
                }
                try{
                    tuner = new PerfTuner();
                    tuner.install();
                }catch(Throwable t){
                    tuner = null;
                    Log.err("[MO] no se pudo iniciar PerfTuner", t);
                }
                // La ventana va primero: RenderCuller la usa para recortar. Si falla, el mod sigue sin ella.
                try{
                    win = new FloatWindow();
                    win.install();
                }catch(Throwable t){
                    win = null;
                    Log.err("[MO] no se pudo iniciar FloatWindow", t);
                }
                try{
                    culler = new RenderCuller(win);
                    Log.info("[MO] v3 cargado. build=" + Version.build);
                }catch(Throwable t){
                    failed = true;
                    Log.err("[MO] no se pudo iniciar RenderCuller", t);
                }
                try{
                    buildSettings();
                }catch(Throwable t){
                    Log.err("[MO] no se pudieron crear los ajustes", t);
                }
            });

            Events.run(Trigger.draw, () -> {
                if(failed || culler == null || !Vars.state.isGame()) return;
                try{
                    Groups.draw.draw(d -> {
                        if(d instanceof Unit){
                            Unit u = (Unit)d;
                            if(!culler.drawUnit(u)){
                                u.draw();
                            }
                        }else{
                            d.draw();
                        }
                    });
                }catch(Throwable t){
                    failed = true;
                    Log.err("[MO] draw falló; se desactiva el hook", t);
                }
            });
        }catch(Throwable t){
            Log.err("[MO] constructor de ModTemplate falló", t);
        }
    }

    private static void t(String key, String name, String desc){
        Core.bundle.getProperties().put("setting." + key + ".name", name);
        if(desc != null){
            Core.bundle.getProperties().put("setting." + key + ".description", desc);
        }
    }

    private void buildSettings(){
        if(Vars.headless || Vars.ui == null || Vars.ui.settings == null) return;

        t("mo-on", "MindOptimized activo", "Interruptor general. Apagado = vanilla puro.");
        t("mo-fog", "Omitir edificios bajo niebla", "Edificios 'recordados' pero bajo niebla = 0 vértices.");
        t("mo-lod-icon", "LOD icono bloques (px/casilla)", "≤N px: 1 quad con el sprite completo del bloque. 0 = off.");
        t("mo-lod-solid", "LOD sólido bloques (px/casilla)", "≤N px: color plano del bloque, fusionado en rectángulos. 0 = off.");
        t("mo-merge", "Enmallado voraz (bloques)", "Une casillas contiguas del mismo color en 1 quad.");
        t("mo-unit-icon", "LOD icono unidades (px)", "Unidades < N px en pantalla: 1 quad con su sprite base. 0 = off.");
        t("mo-unit-solid", "LOD sólido unidades (px)", "Unidades < N px: rect de color de equipo. 0 = off.");
        t("mo-sleep", "Sleep de fábricas fuera de vista", "Fábricas (taladros, crafters) fuera de cámara se duermen. Producción conservada con catch-up.");
        t("mo-sleep-hz", "Tickrate fuera de vista (hz)", "Hz efectivos de las fábricas dormidas. 10 = 1 tick cada 6 ticks reales.");
        t("mo-win", "Ventana flotante de render", "Renderiza solo dentro de una ventana que puedes mover, redimensionar y fijar. Sin estirar la imagen.");
        t("mo-win-chrome", "Mostrar borde y controles de la ventana", "Apagado = sin borde ni botones (ventana bloqueada). Para volver a verlos: mantén pulsada 0.7 s la esquina superior izquierda de la ventana, o actívalo aquí.");
        t("mo-tick-hz", "Tickrate local de la simulación", "Solo partidas locales. 60 = normal. Más bajo ahorra CPU pero la simulación se ve a saltos y balas/unidades pueden atravesar cosas. Se redondea a frames enteros.");
        t("mo-vsync-off", "Desactivar VSync", "Apaga la sincronización con la pantalla. El contador de FPS puede superar la tasa de refresco; la pantalla solo muestra hasta su tasa. Más calor y batería si no pones límite.");
        t("mo-fps-cap", "Límite de FPS", "Pausa estable entre frames. 0 = sin límite.");
        t("mo-refresh", "Frecuencia de pantalla preferida", "Pide a Android esa tasa (Hz). Menos de 30 = automático. El sistema puede ignorarlo.");
        t("mo-threads", "Hilos de trabajo", "0 = automático según los núcleos rápidos de tu CPU. Incluye el hilo principal.");
        t("mo-par", "Clasificación de bloques en paralelo", "Reparte el recorte de bloques entre núcleos cuando hay muchos tiles en pantalla.");
        t("mo-stats", "Estadísticas en el log", "Cada 600 frames imprime métricas de vértices y tiempos.");

        Vars.ui.settings.addCategory("MindOptimized", (Drawable)Icon.settings, t -> {
            t.checkPref("mo-on", true);
            t.checkPref("mo-fog", true);
            t.sliderPref("mo-lod-icon", 14, 0, 40, 1, i -> i <= 0 ? "off" : i + " px");
            t.sliderPref("mo-lod-solid", 6, 0, 24, 1, i -> i <= 0 ? "off" : i + " px");
            t.checkPref("mo-merge", true);
            t.sliderPref("mo-unit-icon", 12, 0, 60, 1, i -> i <= 0 ? "off" : i + " px");
            t.sliderPref("mo-unit-solid", 5, 0, 30, 1, i -> i <= 0 ? "off" : i + " px");
            t.checkPref("mo-sleep", true);
            t.sliderPref("mo-sleep-hz", 10, 1, 60, 1, i -> i >= 60 ? "sync (60 hz)" : i + " hz");
            t.checkPref("mo-win", false);
            t.checkPref("mo-win-chrome", true);
            t.row();
            t.button("Restablecer ventana", () -> { if(win != null) win.reset(); }).size(260f, 50f).pad(6f);
            t.row();
            t.sliderPref("mo-tick-hz", 60, 1, 60, 1, i -> i >= 60 ? "normal (60 hz)" : i + " hz");
            t.checkPref("mo-vsync-off", false);
            t.sliderPref("mo-fps-cap", 0, 0, 360, 5, i -> i <= 0 ? "sin límite" : i + " fps");
            t.sliderPref("mo-refresh", 0, 0, 165, 1, i -> i < 30 ? "auto" : i + " hz");
            t.add("[lightgray]" + Cores.get().summary + "[]").pad(6f).left().wrap().width(480f).row();
            t.sliderPref("mo-threads", 0, 0, 16, 1, i -> i <= 0 ? "auto (" + Cores.get().autoThreads() + ")" : String.valueOf(i));
            t.checkPref("mo-par", true);
            t.checkPref("mo-stats", true);
        });
    }
}
