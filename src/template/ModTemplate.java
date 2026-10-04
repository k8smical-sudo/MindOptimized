package template;

import arc.Core;
import arc.Events;
import arc.scene.style.Drawable;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.mod.Mod;

public class ModTemplate extends Mod{
    /**
     * Se crea de forma perezosa (en ClientLoadEvent) y NO en el constructor.
     * El constructor del mod se ejecuta durante Mods.load() al iniciar el juego; si algo lanza
     * una excepción ahí, Mindustry descarta el mod en silencio ("Failed to load mod file. Skipping")
     * y simplemente desaparece de la lista tras reiniciar, sin crash ni aviso en pantalla.
     */
    private RenderCuller culler;
    private PerfTuner tuner;
    private PhysicsThrottle physics;
    private CameraTuner camera;
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
                try{
                    physics = new PhysicsThrottle();
                    physics.install();
                }catch(Throwable t){
                    physics = null;
                    Log.err("[MO] no se pudo iniciar PhysicsThrottle", t);
                }
                try{
                    camera = new CameraTuner();
                    camera.install();
                }catch(Throwable t){
                    camera = null;
                    Log.err("[MO] no se pudo iniciar CameraTuner", t);
                }
                // Limpieza de ajustes de la ventana flotante eliminada.
                for(String k : new String[]{"mo-win", "mo-win-pin", "mo-win-x", "mo-win-y", "mo-win-w", "mo-win-h", "mo-win-chrome", "mo-win-min", "mo-lod-icon", "mo-lod-solid", "mo-merge", "mo-unit-icon", "mo-unit-solid", "mo-redraw"}){
                    Core.settings.remove(k);
                }
                try{
                    culler = new RenderCuller();
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
        t("mo-cam-linear", "Cámara lineal (sin suavizado)", "Quita el suavizado de zoom, el seguimiento suave y la inercia al arrastrar. La cámara va directo al destino.");
        t("mo-cam-snap", "Alinear cámara a píxeles", "Dibuja con la cámara alineada a la cuadrícula de píxeles de la pantalla (sin temblor de texturas). Solo con cámara lineal.");
        t("mo-sleep", "Sleep de fábricas fuera de vista", "Fábricas (taladros, crafters) fuera de cámara se duermen. Producción conservada con catch-up.");
        t("mo-sleep-hz", "Tickrate fuera de vista (hz)", "Hz efectivos de las fábricas dormidas. 10 = 1 tick cada 6 ticks reales.");
        t("mo-phys-budget", "Simplificar colisiones entre unidades (ms)", "Presupuesto de CPU por frame para el empuje entre unidades. Si se pasa, la física corre 1 de cada k frames (k adaptativo). 0 = desactivado. En multitudes las unidades se solapan algo más.");
        t("mo-phys-max", "Máximo de frames entre cálculos de empuje", "Tope de k. Más alto ahorra más CPU y solapa más.");
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
            t.checkPref("mo-cam-linear", true);
            t.checkPref("mo-cam-snap", true);
            t.checkPref("mo-sleep", true);
            t.sliderPref("mo-sleep-hz", 10, 1, 60, 1, i -> i >= 60 ? "sync (60 hz)" : i + " hz");
            t.sliderPref("mo-phys-budget", 2, 0, 10, 1, i -> i <= 0 ? "off" : i + " ms");
            t.sliderPref("mo-phys-max", 4, 1, 8, 1, i -> "1 de cada " + i);
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
