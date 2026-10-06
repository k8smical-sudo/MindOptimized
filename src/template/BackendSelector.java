package template;

import arc.ApplicationListener;
import arc.Core;
import arc.util.Log;

/**
 * Selector de backend gráfico preferido: Automático / Vulkan 1.1+ / OpenGL ES.
 *
 * Qué hace de verdad: guarda la preferencia en Core.settings, calcula qué backend quedaría EFECTIVO, se lo explica al
 * usuario y, si ese backend fuera distinto del que está corriendo, ofrece cerrar el juego para aplicarlo.
 *
 * Qué NO hace, y por qué: el contexto gráfico lo crea el AndroidLauncher del APK antes de que cargue ningún mod, y Arc
 * solo trae backend de OpenGL ES. Hoy no existe un backend Vulkan que activar, así que el efectivo es SIEMPRE OpenGL ES.
 * Cuando un APK incluya un backend Vulkan, basta con que vulkanBackendPresent() lo detecte y la preferencia pasará a
 * aplicarse al reiniciar; el resto del flujo (guardar, avisar, reiniciar) ya está hecho.
 */
public final class BackendSelector{
    public static final String K_BACKEND = "mo-backend"; // 0 = automático, 1 = Vulkan 1.1+, 2 = OpenGL ES

    public static final int GLES = 0, VULKAN = 1;
    /** Backend que está corriendo ahora mismo en este proceso. */
    private static final int RUNNING = GLES;

    private static int lastPref = -1;

    private BackendSelector(){
    }

    public static void install(){
        lastPref = Core.settings.getInt(K_BACKEND, 0);
        Core.app.addListener(new ApplicationListener(){
            int counter;

            public void update(){
                if((counter++ & 63) == 0) poll();
            }
        });
    }

    /** ¿Hay un backend Vulkan cargable en este APK? Hoy ninguno (Arc/MindustryX no lo incluyen). */
    static boolean vulkanBackendPresent(){
        return false;
    }

    public static String prefLabel(int pref){
        return pref == 0 ? "Automático" : pref == 1 ? "Vulkan 1.1+" : "OpenGL ES";
    }

    public static int effective(){
        int pref = Core.settings.getInt(K_BACKEND, 0);
        boolean wantVulkan = pref == 1 || (pref == 0 && HardwareProfile.vulkan11());
        return wantVulkan && vulkanBackendPresent() ? VULKAN : GLES;
    }

    public static String effectiveText(){
        return effective() == VULKAN ? "Vulkan" : "OpenGL ES";
    }

    private static void poll(){
        int p = Core.settings.getInt(K_BACKEND, 0);
        if(p == lastPref) return;
        lastPref = p;

        int eff = effective();
        Log.info("[MO] backend preferido: " + prefLabel(p) + " | efectivo: " + effectiveText());

        String base = "Preferencia guardada: " + prefLabel(p) + ".\nGPU: " + HardwareProfile.oneLine() + ".\n\n";
        if(eff != RUNNING){
            HardwareProfile.dialog("Backend gráfico", base + "Para aplicar el cambio hay que cerrar el juego y volver a abrirlo.",
                "Cerrar el juego ahora", BackendSelector::restartNow);
        }else{
            HardwareProfile.dialog("Backend gráfico", base + "Efectivo ahora: " + effectiveText() + ". "
                + (p == 1 || (p == 0 && HardwareProfile.vulkan11())
                ? "Tu equipo declara Vulkan " + HardwareProfile.vulkanText() + ", pero este APK solo incluye el backend OpenGL ES (Arc no trae uno de Vulkan), "
                + "así que se sigue usando OpenGL ES. Cuando exista un APK con backend Vulkan, esta preferencia se aplicará al reiniciar."
                : "No hace falta reiniciar."), null, null);
        }
    }

    /** Guarda los ajustes y cierra la actividad. Android no permite reabrir la app sin un permiso especial: se abre a mano. */
    public static void restartNow(){
        Refl.call(Core.settings, "forceSave");
        Refl.call(Core.app, "exit");
    }
}
