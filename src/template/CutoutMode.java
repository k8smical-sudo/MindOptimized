package template;

import arc.ApplicationListener;
import arc.Core;
import arc.util.Log;

import java.lang.reflect.Method;

/**
 * Ignorar la muesca (notch / recorte de pantalla): el juego dibuja en TODA la pantalla, también bajo la cámara.
 *
 * Qué hace: pide a Android que la ventana se extienda por la zona del recorte
 * (WindowManager.LayoutParams.layoutInDisplayCutoutMode = ALWAYS en Android 11+, SHORT_EDGES en 9 y 10), desactiva el ajuste
 * de la ventana a las barras del sistema (Window.setDecorFitsSystemWindows, Android 11+) y mantiene el modo inmersivo.
 * Al apagarlo restaura los valores que tenía la ventana antes de tocarla.
 *
 * Qué NO hace: no cambia la lógica de márgenes de la interfaz del juego. Si el juego aplica por su cuenta márgenes seguros
 * alrededor del recorte, los conserva; si no, los botones cercanos a la cámara pueden quedar parcialmente tapados.
 *
 * Todo por reflexión y en el hilo de interfaz; si algo no existe en el equipo, se anota en el log y no pasa nada más.
 */
public final class CutoutMode{
    public static final String K_ON = "mo-notch";

    private static boolean installed, applied, first = true;
    private static int origMode = -1, origUi = Integer.MIN_VALUE, counter;
    private static volatile String status = "sin tocar";

    private CutoutMode(){
    }

    public static String status(){
        return status;
    }

    public static void install(){
        if(installed || !Core.app.isAndroid()) return;
        installed = true;
        Core.app.addListener(new ApplicationListener(){
            public void update(){
                if((counter++ & 63) == 0) poll();
            }
        });
        Log.info("[MO] CutoutMode listo");
    }

    private static void poll(){
        boolean want = Core.settings.getBool(K_ON, false);
        if(!first && want == applied) return;
        if(first && !want){ // primer arranque con el ajuste apagado: no se toca la ventana
            first = false;
            return;
        }
        first = false;
        applied = want;
        apply(want);
    }

    private static void apply(final boolean on){
        try{
            final Object act = Core.app;
            final Class<?> actC = Class.forName("android.app.Activity");
            final Class<?> windowC = Class.forName("android.view.Window");
            final Class<?> paramsC = Class.forName("android.view.WindowManager$LayoutParams");
            final Class<?> viewC = Class.forName("android.view.View");
            final int sdk = Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
            final int hBefore = Core.graphics.getHeight();

            Runnable job = () -> {
                try{
                    Object window = actC.getMethod("getWindow").invoke(act);
                    Object decor = windowC.getMethod("getDecorView").invoke(window);

                    // 1) Modo del recorte: 0 = por defecto, 1 = bordes cortos, 3 = siempre.
                    if(sdk >= 28){
                        Object lp = windowC.getMethod("getAttributes").invoke(window);
                        if(origMode < 0) origMode = paramsC.getField("layoutInDisplayCutoutMode").getInt(lp);
                        int mode = on ? (sdk >= 30 ? 3 : 1) : Math.max(0, origMode);
                        paramsC.getField("layoutInDisplayCutoutMode").setInt(lp, mode);
                        windowC.getMethod("setAttributes", paramsC).invoke(window, lp);
                    }

                    // 2) La ventana no se ajusta a las barras del sistema (Android 11+).
                    if(sdk >= 30){
                        try{
                            windowC.getMethod("setDecorFitsSystemWindows", boolean.class).invoke(window, !on);
                        }catch(Throwable ignored){
                        }
                    }

                    // 3) Modo inmersivo; se guarda lo que había para devolverlo al apagar.
                    Method getUi = viewC.getMethod("getSystemUiVisibility"), setUi = viewC.getMethod("setSystemUiVisibility", int.class);
                    if(on){
                        if(origUi == Integer.MIN_VALUE) origUi = (Integer)getUi.invoke(decor);
                        // LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN | IMMERSIVE_STICKY
                        setUi.invoke(decor, 0x100 | 0x200 | 0x400 | 0x2 | 0x4 | 0x1000);
                    }else if(origUi != Integer.MIN_VALUE){
                        setUi.invoke(decor, origUi);
                        origUi = Integer.MIN_VALUE;
                    }

                    String cut = cutoutInsets(decor);
                    status = (on ? "ignorada (modo " + (sdk >= 30 ? "ALWAYS" : "SHORT_EDGES") + ")" : "restaurada") + (cut.isEmpty() ? "" : ", " + cut);
                    Log.info("[MO] muesca: " + status + " | alto de superficie antes " + hBefore + " px");
                }catch(Throwable t){
                    status = "falló: " + t.getClass().getSimpleName();
                    Refl.once("muesca", t);
                }
            };
            actC.getMethod("runOnUiThread", Runnable.class).invoke(act, job);

            // Un segundo después la superficie ya cambió de tamaño: se deja constancia para saber si surtió efecto.
            arc.util.Time.run(90f, () -> Log.info("[MO] muesca: alto de superficie ahora " + Core.graphics.getHeight() + " px (antes " + hBefore + ")"));
        }catch(Throwable t){
            status = "no disponible";
            Refl.once("muesca (preparación)", t);
        }
    }

    /** Insets del recorte según Android (API 28+): informativo, para el log y el diagnóstico. */
    private static String cutoutInsets(Object decor){
        try{
            Object ins = Class.forName("android.view.View").getMethod("getRootWindowInsets").invoke(decor);
            if(ins == null) return "";
            Object cut = Class.forName("android.view.WindowInsets").getMethod("getDisplayCutout").invoke(ins);
            if(cut == null) return "este equipo no declara recorte";
            Class<?> cc = Class.forName("android.view.DisplayCutout");
            return "recorte arriba " + cc.getMethod("getSafeInsetTop").invoke(cut) + " px, izq. " + cc.getMethod("getSafeInsetLeft").invoke(cut)
                + ", der. " + cc.getMethod("getSafeInsetRight").invoke(cut) + ", abajo " + cc.getMethod("getSafeInsetBottom").invoke(cut);
        }catch(Throwable t){
            return "";
        }
    }
}
