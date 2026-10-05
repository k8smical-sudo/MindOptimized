package template;

import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Groups;

/** Un informe de una pantalla con el estado de cada módulo. Va al log y a un diálogo (para mandar captura o texto). */
public final class Diagnostics{
    private Diagnostics(){
    }

    public static PhysicsThrottle physics;

    public static String build(){
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(Cores.get().summary).append('\n');
        sb.append("Memoria: ").append(MemGuard.snapshot()).append('\n');
        sb.append("Medidor de lógica: ").append(LogicTimer.inst != null ? "activo, " + String.format("%.2f", LogicTimer.ms()) + " ms" : "NO instalado").append('\n');
        sb.append("Cintas (LOD): ").append(ConveyorLOD.registeredTypes()).append(" tipos | ").append(ConveyorLOD.status().replaceAll("\\[[^\\]]*\\]", "")).append('\n');
        sb.append("Física de unidades: ").append(physics == null ? "no iniciada" : physics.status()).append('\n');
        sb.append("Texturas: ").append(TextureScaler.inst == null ? "no iniciado" : TextureScaler.inst.ready ? "índice listo (" + TextureScaler.inst.info.size() + " regiones)" : "indexando").append('\n');
        sb.append("Android: ").append(AndroidBoost.status()).append('\n');
        sb.append("Partida: ").append(Vars.state.isGame() ? Groups.build.size() + " edificios, " + Groups.unit.size() + " unidades" : "en menú");
        sb.append(" | red: ").append(Vars.net.server() ? "host" : Vars.net.client() ? "cliente" : "local");
        return sb.toString();
    }

    public static void report(){
        String s = build();
        Log.info("[MO] DIAGNÓSTICO\n" + s);
        if(Vars.ui != null) Vars.ui.showInfoText("Diagnóstico MindOptimized", s);
    }
}
