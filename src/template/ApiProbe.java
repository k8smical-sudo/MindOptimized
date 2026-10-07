package template;

import arc.Core;
import arc.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

/**
 * Sonda de APIs: vuelca al log las firmas REALES de las clases de audio y gráficos de tu build de MindustryX/Arc.
 *
 * Para qué: antes de escribir un gestor de voces o un renderizado instanciado hay que saber exactamente qué métodos
 * existen y qué devuelven (p. ej. si Sound.at devuelve int o void, si Gl expone instancing o texturas 3D). Adivinar
 * esas firmas rompe el build. Con este volcado se escribe contra la API verdadera.
 *
 * Es solo lectura: no toca ningún estado del juego. Se ejecuta sola la primera vez y bajo demanda desde Ajustes.
 */
public final class ApiProbe{
    private static final StringBuilder buf = new StringBuilder();

    private ApiProbe(){
    }

    private static void out(String line){
        Log.info(line);
        buf.append(line).append('\n');
    }

    public static void runOnce(){
        // Clave nueva: la anterior pudo quedar marcada sin que el volcado llegara a verse.
        if(Core.settings.getBool("mo-probe-v2", false)) return;
        Core.settings.put("mo-probe-v2", true);
        run();
    }

    public static void run(){
        buf.setLength(0);
        try{
            out("[MO] sonda: ===== INICIO =====");
            out("[MO] sonda: Core.graphics = " + (Core.graphics == null ? "null" : Core.graphics.getClass().getName()));
            Object audio = Refl.getStatic(Core.class, "audio");
            out("[MO] sonda: Core.audio = " + (audio == null ? "null" : audio.getClass().getName()));

            for(String c : new String[]{
                "arc.backend.android.AndroidApplication", "arc.backend.android.AndroidGraphics", "arc.backend.android.AndroidAudio",
                "arc.audio.Audio", "arc.audio.Sound", "arc.audio.Music", "arc.audio.AudioBus", "arc.audio.Soloud",
                "arc.graphics.Gl", "arc.graphics.Mesh", "arc.graphics.gl.GLVersion", "arc.graphics.g2d.SpriteBatch",
                "arc.graphics.g2d.SortedSpriteBatch", "android.media.SoundPool"
            }){
                out("[MO] sonda: clase " + c + " -> " + (Refl.cls(c) != null ? "existe" : "NO existe"));
            }

            // Audio
            dump("arc.audio.Sound", null, 80);
            dump("arc.audio.Audio", null, 60);
            dump("arc.audio.AudioBus", null, 30);
            fields("mindustry.gen.Sounds", null, 6);
            fields("mindustry.type.Weapon", "sound", 12);
            fields("mindustry.entities.bullet.BulletType", "sound", 12);

            // Gráficos
            dump("arc.graphics.Gl", "instanc|3d|array|invalidate|divisor|texImage|texSubImage|drawElements|drawArrays|bufferSub|mapBuffer", 60);
            dump("arc.graphics.Mesh", "instanc|render|bind|setVertices|getVertices|setIndices", 40);
            dump("arc.graphics.g2d.SpriteBatch", "flush|vertex|draw|switch|setShader|blend", 30);
            out("[MO] sonda: ===== FIN =====");
        }catch(Throwable t){
            Refl.once("ApiProbe", t);
        }
        try{
            // Además del log (que adb recorta), se guarda en un archivo de la carpeta de datos del juego.
            arc.files.Fi f = Core.files.local("mo-probe.txt");
            f.writeString(buf.toString(), false);
            Log.info("[MO] sonda: guardada en " + f.absolutePath());
        }catch(Throwable t){
            Refl.once("ApiProbe (archivo)", t);
        }
    }

    private static void dump(String cn, String regex, int max){
        Class<?> c = Refl.cls(cn);
        if(c == null){
            out("[MO] sonda: " + cn + " -> NO existe");
            return;
        }
        StringBuilder h = new StringBuilder("[MO] sonda: ").append(cn).append(" extends ")
            .append(c.getSuperclass() == null ? "-" : c.getSuperclass().getName());
        for(Class<?> i : c.getInterfaces()) h.append(" implements ").append(i.getSimpleName());
        out(h.toString());

        Pattern p = regex == null ? null : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        int n = 0;
        for(Method m : c.getMethods()){
            if(m.getDeclaringClass() == Object.class) continue;
            StringBuilder sig = new StringBuilder(Modifier.toString(m.getModifiers())).append(' ')
                .append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for(int i = 0; i < ps.length; i++) sig.append(i > 0 ? ", " : "").append(ps[i].getSimpleName());
            sig.append(')');
            String s = sig.toString();
            if(p != null && !p.matcher(s).find()) continue;
            out("[MO] sonda:    " + s);
            if(++n >= max){
                out("[MO] sonda:    ... (recortado)");
                break;
            }
        }
    }

    /** Campos públicos de una clase, opcionalmente solo los que contienen "contains" en el nombre. */
    private static void fields(String cn, String contains, int max){
        Class<?> c = Refl.cls(cn);
        if(c == null){
            out("[MO] sonda: " + cn + " -> NO existe");
            return;
        }
        Field[] all = c.getFields();
        int n = 0, total = 0;
        for(Field f : all){
            if(contains != null && !f.getName().toLowerCase().contains(contains)) continue;
            total++;
            if(n < max){
                out("[MO] sonda: " + cn + "." + f.getName() + " : " + f.getType().getSimpleName() + (Modifier.isStatic(f.getModifiers()) ? " (static)" : ""));
                n++;
            }
        }
        out("[MO] sonda: " + cn + " -> " + total + " campos" + (contains != null ? " con '" + contains + "'" : ""));
    }
}
