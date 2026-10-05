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
    private SysMonitor monitor;
    private TextureScaler textures;
    private boolean convOk;
    private FlatRender flat;
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
                    Diagnostics.physics = physics;
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
                try{
                    monitor = new SysMonitor();
                    monitor.install();
                }catch(Throwable t){
                    monitor = null;
                    Log.err("[MO] no se pudo iniciar SysMonitor", t);
                }
                try{
                    MemGuard.install();
                }catch(Throwable t){
                    Log.err("[MO] no se pudo iniciar MemGuard", t);
                }
                try{
                    AndroidBoost.install();
                }catch(Throwable t){
                    Log.err("[MO] no se pudo iniciar AndroidBoost", t);
                }
                try{
                    ConveyorLOD.install();
                    convOk = true;
                }catch(Throwable t){
                    Log.err("[MO] no se pudo iniciar ConveyorLOD", t);
                }
                // Migración desde el antiguo main.js: su culling agresivo era un duplicado del sleep de RenderCuller.
                try{
                    if(Core.settings.has("flat-cull") && !Core.settings.has(RenderCuller.K_SLEEP)){
                        Core.settings.put(RenderCuller.K_SLEEP, Core.settings.getBool("flat-cull", false));
                        Core.settings.put(RenderCuller.K_SLEEP_HZ, Core.settings.getInt("flat-cull-hz", 10));
                        Log.info("[MO] ajustes de culling migrados desde flat-cull a mo-sleep");
                    }
                    Core.settings.remove("flat-cull");
                    Core.settings.remove("flat-cull-hz");
                }catch(Throwable t){
                    Log.err("[MO] migración de ajustes falló", t);
                }
                // Port nativo del antiguo main.js (Flat Performance): texturas primero, luego render/aspecto.
                try{
                    textures = new TextureScaler();
                    textures.install();
                }catch(Throwable t){
                    textures = null;
                    Log.err("[MO] no se pudo iniciar TextureScaler", t);
                }
                try{
                    flat = new FlatRender();
                    flat.install();
                }catch(Throwable t){
                    flat = null;
                    Log.err("[MO] no se pudo iniciar FlatRender", t);
                }
                try{
                    FlatSettings.register();
                }catch(Throwable t){
                    Log.err("[MO] no se pudo crear la categoría Flat Performance", t);
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
        t("mo-mon", "Monitor de CPU/GPU", "0 = apagado, 1 = solo consola (cada ~10 s), 2 = en pantalla + consola. CPU por núcleo, GPU por frame y los hilos que más gastan.");
        t("mo-mon-x", "Monitor: posición X (%)", "Posición horizontal del monitor en pantalla.");
        t("mo-mon-y", "Monitor: posición Y (%)", "Posición vertical desde arriba. Por defecto queda bajo el contador de FPS.");
        t("mo-balance", "Balanceador de hilos", "Sube la prioridad de los hilos propios que más CPU usan. Android no permite fijar hilos a núcleos concretos; solo influye en cuál tiene preferencia.");
        t("mo-conv-online", "Cintas simplificadas también como cliente online", "Como cliente de una partida en red, las cintas lejanas se simulan por niveles SOLO en tu dispositivo (el servidor manda). Nunca actúa si eres host. Apágalo si ves desajustes en cintas lejanas.");
        t("mo-boost", "Más recursos al cargar/jugar mapas enormes", "Pantalla siempre encendida, rendimiento sostenido y aviso a Android (ADPF, Android 12+) de que el juego necesita más CPU durante la carga y con mapas de más de 100 000 edificios. No sube el límite de memoria de la app.");
        t("mo-memlog", "Registrar memoria al cargar mapas", "Durante la carga de un mapa escribe en el log, cada segundo, la memoria Java, nativa y del sistema. Si el juego se cae, las últimas líneas dicen qué memoria se agotó.");
        t("mo-conv-max", "Cintas lejanas: periodo máximo", "Las cintas lejos de la cámara actualizan 1 de cada k ticks (movimiento compensado, mismo flujo). 1 = apagado. El nivel real lo decide el controlador según la carga de la lógica.");
        t("mo-conv-budget", "Cintas: presupuesto de lógica (ms)", "El controlador sube la simplificación cuando la lógica pasa de este valor y la baja cuando sobra. 0 = siempre al máximo.");
        t("mo-conv-near", "Cintas: radio cercano", "Radio (en pantallas) donde las cintas se simulan a tick completo. Más pequeño = más ahorro, más cintas a saltos cerca del borde.");
        t("mo-conv-items", "Ocultar ítems de cintas al alejar (px/casilla)", "Si la casilla mide menos píxeles que este valor, las cintas no dibujan sus ítems. 0 = nunca.");
        t("mo-cam-linear", "Cámara lineal (sin suavizado)", "Quita el suavizado de zoom, el seguimiento suave y la inercia al arrastrar. La cámara va directo al destino.");
        t("mo-cam-snap", "Alinear cámara a píxeles", "Solo actúa con zoom de escala entera. Puede hacer vibrar levemente lo que la cámara sigue; apagado por defecto.");
        t("mo-sleep", "Sleep de fábricas fuera de vista", "Fábricas (taladros, crafters) fuera de cámara se duermen. Producción conservada con catch-up.");
        t("mo-sleep-hz", "Tickrate fuera de vista (hz)", "Hz efectivos de las fábricas dormidas. 10 = 1 tick cada 6 ticks reales.");
        t("mo-phys-budget", "Simplificar colisiones entre unidades (ms)", "Presupuesto de CPU por frame para el empuje entre unidades. Si se pasa, la física corre 1 de cada k frames (k adaptativo). 0 = desactivado. En multitudes las unidades se solapan algo más.");
        t("mo-phys-max", "Máximo de frames entre cálculos de empuje", "Tope de k. Más alto ahorra más CPU y solapa más.");
        t("mo-tick-hz", "Tickrate local de la simulación", "Solo partidas locales. 60 = normal. Más bajo ahorra CPU pero la simulación se ve a saltos y balas/unidades pueden atravesar cosas. Se redondea a frames enteros.");
        t("mo-vsync-off", "Desactivar VSync", "Apaga la sincronización con la pantalla. El contador de FPS puede superar la tasa de refresco; la pantalla solo muestra hasta su tasa. Más calor y batería si no pones límite.");
        t("mo-fps-cap", "Límite de FPS", "Pausa estable entre frames. 0 = sin límite.");
        t("mo-refresh", "Frecuencia de pantalla preferida", "0 = decide el sistema. 1-29 = el máximo que soporte la pantalla. 30 o más = esa tasa (o la más cercana). Se pide el modo de pantalla real; el sistema puede ignorarlo.");
        t("mo-threads", "Hilos de trabajo", "0 = automático según los núcleos rápidos de tu CPU. Incluye el hilo principal.");
        t("mo-par", "Clasificación de bloques en paralelo", "Reparte el recorte de bloques entre núcleos cuando hay muchos tiles en pantalla.");
        t("mo-stats", "Estadísticas en el log", "Cada 600 frames imprime métricas de vértices y tiempos.");

        Vars.ui.settings.addCategory("MindOptimized", (Drawable)Icon.settings, t -> {
            t.checkPref("mo-on", true);
            t.checkPref("mo-fog", true);
            t.sliderPref("mo-mon", 2, 0, 2, 1, i -> i == 0 ? "apagado" : i == 1 ? "consola" : "pantalla");
            t.sliderPref("mo-mon-x", 41, 0, 90, 1, i -> i + "%");
            t.sliderPref("mo-mon-y", 13, 0, 90, 1, i -> i + "%");
            t.checkPref("mo-balance", true);
            FlatSettings.hdr(t, "prof", "Perfiles de un toque");
            FlatSettings.noteRow(t, "prof", "Cambian varios ajustes de golpe. La simulación (cintas, física, sleep) solo se toca en partidas locales y, las cintas, también como cliente; el host nunca se ve alterado.");
            FlatSettings.btnRow(t, "mo-prof-balanced", "Perfil: Equilibrado", () -> Profiles.apply("balanced"));
            FlatSettings.btnRow(t, "mo-prof-battle", "Perfil: Batalla grande", () -> Profiles.apply("battle"));
            FlatSettings.btnRow(t, "mo-prof-extreme", "Perfil: Extremo (mapas/batallas enormes)", () -> Profiles.apply("extreme"));
            FlatSettings.btnRow(t, "mo-diag", "Diagnóstico: estado de todos los módulos", Diagnostics::report);
            FlatSettings.hdr(t, "adv", "Ajustes individuales");
            t.checkPref("mo-boost", true);
            t.checkPref("mo-memlog", true);
            t.sliderPref("mo-conv-max", 4, 1, 16, 1, i -> i <= 1 ? "apagado" : "1 de cada " + i);
            t.sliderPref("mo-conv-budget", 10, 0, 40, 1, i -> i <= 0 ? "máximo fijo" : i + " ms");
            t.sliderPref("mo-conv-near", 10, 5, 30, 1, i -> (i / 10f) + " pantallas");
            t.checkPref("mo-conv-online", true);
            t.sliderPref("mo-conv-items", 0, 0, 16, 1, i -> i <= 0 ? "nunca" : "< " + i + " px");
            t.checkPref("mo-cam-linear", true);
            t.checkPref("mo-cam-snap", false);
            t.checkPref("mo-sleep", true);
            t.sliderPref("mo-sleep-hz", 10, 1, 60, 1, i -> i >= 60 ? "sync (60 hz)" : i + " hz");
            t.sliderPref("mo-phys-budget", 2, 0, 10, 1, i -> i <= 0 ? "off" : i + " ms");
            t.sliderPref("mo-phys-max", 4, 1, 8, 1, i -> "1 de cada " + i);
            t.sliderPref("mo-tick-hz", 60, 1, 60, 1, i -> i >= 60 ? "normal (60 hz)" : i + " hz");
            t.checkPref("mo-vsync-off", false);
            t.sliderPref("mo-fps-cap", 0, 0, 360, 5, i -> i <= 0 ? "sin límite" : i + " fps");
            t.sliderPref("mo-refresh", 1, 0, 165, 1, i -> i == 0 ? "sistema" : i < 30 ? "máximo" : i + " hz");
            t.add("[lightgray]" + Cores.get().summary + "[]").pad(6f).left().wrap().width(480f).row();
            t.sliderPref("mo-threads", 0, 0, 16, 1, i -> i <= 0 ? "auto (" + Cores.get().autoThreads() + ")" : String.valueOf(i));
            t.checkPref("mo-par", true);
            t.checkPref("mo-stats", true);
        });
    }
}
