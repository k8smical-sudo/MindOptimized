package template;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.type.UnitType;
import mindustry.world.Block;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Recorte de fuentes de sonido para ahorrar CPU.
 *
 * Contexto (del log): el juego mezcla el audio POR SOFTWARE en la CPU con el motor SoLoud ("Creating audio thread").
 * No hay una mezcla por hardware que activar en un teléfono: cada voz cuesta mezcla y remuestreo, y cada bucle ambiental de
 * edificio cuesta código Java en cada frame. Con miles de edificios y balas, eso se nota.
 *
 * Qué hace: cambia el sonido de cada tipo de contenido por Sounds.none (que el propio juego ya trata como "sin sonido" y
 * descarta antes de hacer ningún trabajo) y lo restaura al instante al volver a activarlo. Los valores originales se guardan.
 * Es un recorte por TIPO (silencia una clase entera de sonidos), no por frame; un limitador de voces por frame necesita
 * conocer la API exacta de Sound y se hará con los datos de la sonda.
 *
 * Todo por reflexión: si un campo no existe en esta versión, ese grupo simplemente tiene menos entradas (se ve en el recuento).
 */
public final class AudioTrim{
    public static final String[] GROUPS = {"ambient", "bullet", "shoot", "unit"};

    private static final class Slot{
        final Object target;
        final String field;
        final Object orig;

        Slot(Object target, String field, Object orig){
            this.target = target;
            this.field = field;
            this.orig = orig;
        }
    }

    private static final HashMap<String, ArrayList<Slot>> slots = new HashMap<>();
    private static final HashMap<String, Boolean> keeping = new HashMap<>();
    private static Object none;
    private static boolean collected;

    private AudioTrim(){
    }

    public static void install(){
        collect();
        for(String g : GROUPS) apply(g, savedKeep(g), false);
    }

    private static boolean savedKeep(String g){
        return arc.Core.settings.getBool("mo-snd-" + g, true);
    }

    /** Llamado por los interruptores de Ajustes: keep = true deja el sonido original, false lo silencia. */
    public static void set(String group, boolean keep){
        apply(group, keep, true);
    }

    private static void add(String group, Object target, String... fields){
        if(target == null) return;
        ArrayList<Slot> list = slots.get(group);
        for(String f : fields){
            if(Refl.field(target.getClass(), f) == null) continue;
            Object v = Refl.get(target, f);
            if(v == null || v == none) continue; // ya es silencio: nada que recortar ni que restaurar
            list.add(new Slot(target, f, v));
        }
    }

    private static void collect(){
        if(collected) return;
        collected = true;
        for(String g : GROUPS){
            slots.put(g, new ArrayList<>());
            keeping.put(g, true);
        }
        Class<?> sounds = Refl.cls("mindustry.gen.Sounds");
        none = sounds == null ? null : Refl.getStatic(sounds, "none");
        if(none == null){
            Log.err("[MO] audio: no se encontró Sounds.none; el recorte de sonidos queda desactivado");
            return;
        }

        try{
            for(Block b : Vars.content.blocks()) add("ambient", b, "ambientSound", "loopSound");
            for(Object bt : Vars.content.bullets()) add("bullet", bt, "hitSound", "despawnSound", "fragSound");
            for(UnitType u : Vars.content.units()){
                add("unit", u, "deathSound", "fallSound", "loopSound", "moveSound");
                Object ws = Refl.get(u, "weapons");
                if(ws instanceof Seq<?> sq){
                    for(int i = 0; i < sq.size; i++) add("shoot", sq.get(i), "shootSound", "chargeSound");
                }
            }
        }catch(Throwable t){
            Refl.once("AudioTrim.collect", t);
        }
        StringBuilder sb = new StringBuilder("[MO] audio: fuentes recortables:");
        for(String g : GROUPS) sb.append(' ').append(g).append('=').append(slots.get(g).size());
        Log.info(sb.toString());
    }

    private static void apply(String group, boolean keep, boolean log){
        collect();
        ArrayList<Slot> list = slots.get(group);
        if(list == null || none == null) return;
        Boolean prev = keeping.get(group);
        if(prev != null && prev == keep && !log && keep) return; // arranque con el valor por defecto: nada que hacer

        int n = 0;
        for(Slot s : list){
            if(Refl.set(s.target, s.field, keep ? s.orig : none)) n++;
        }
        keeping.put(group, keep);
        if(log || !keep) Log.info("[MO] audio: grupo '" + group + "' " + (keep ? "restaurado" : "silenciado") + " (" + n + " campos)");
    }

    public static String summary(){
        if(!collected || none == null) return "no disponible";
        StringBuilder sb = new StringBuilder();
        for(String g : GROUPS){
            ArrayList<Slot> l = slots.get(g);
            sb.append(g).append(' ').append(Boolean.FALSE.equals(keeping.get(g)) ? "silenciado" : "original").append(" (").append(l == null ? 0 : l.size()).append(")  ");
        }
        return sb.toString().trim();
    }
}
