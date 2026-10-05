package template;

import arc.Core;
import arc.Events;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.Block;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/**
 * Índice de texturas (quién usa cada región del atlas y para qué) y reescalado por categoría.
 *
 * Es el port nativo de la parte de texturas del antiguo main.js. Diferencias con la versión JS:
 *  - La indexación recorre el contenido con reflexión de Java en vez de Rhino (muchísimo más rápido).
 *  - El cálculo de píxeles (reducir resolución / opacidad) se reparte entre núcleos con Cores.parallelFor; solo la
 *    subida a la GPU (Texture.draw) ocurre en el hilo de GL.
 *  - Todo acceso a clases/campos del juego pasa por Refl: si algo no existe en esta versión, esa parte se omite.
 */
public class TextureScaler{
    public static TextureScaler inst;

    public static final class Cat{
        public final String id, name, desc;

        Cat(String id, String name, String desc){
            this.id = id;
            this.name = name;
            this.desc = desc;
        }
    }

    public static final Cat[] CATS = {
        new Cat("terrain", "Terreno (pisos)", "Pisos sólidos del mapa (arena, pasto, nieve, piedra...) y sus bordes (-edge). Cada casilla usa una variante al azar. Los líquidos van en su propia categoría; letras, zonas de núcleo y spawn NO se tocan."),
        new Cat("liquid", "Líquidos del terreno", "Pisos líquidos (agua, brea, escoria, criofluido, arkycita...) y sus bordes. Solo cambia el sprite base: el brillo lo dibujan shaders propios."),
        new Cat("wall", "Paredes del terreno", "Paredes estáticas y acantilados del mapa."),
        new Cat("ore", "Minerales", "Menas del suelo y menas de pared (ore-wall-*, Erekir)."),
        new Cat("prop", "Rocas, árboles y decoración", "Bloques del mapa sin función: rocas, arbustos, árboles, algas."),
        new Cat("defense", "Muros / defensa", "Muros y bloques defensivos que construyen los jugadores."),
        new Cat("turret", "Torretas", "Torretas: cañón, partes animadas y contornos. Si la base (block-<tamaño>) la comparten otras categorías va en 'Compartidas'."),
        new Cat("duct", "Ductos (cintas, tuberías)", "Cintas, ductos, tuberías, puentes y enrutadores."),
        new Cat("item", "Ítems (los de las cintas)", "Sprites de ítems tal como se ven sobre cintas, en unidades y bloques. También cambian en los menús."),
        new Cat("power", "Energía", "Nodos, baterías, generadores, paneles."),
        new Cat("production", "Producción / fábricas", "Taladros, bombas y fábricas (crafting)."),
        new Cat("other", "Otras construcciones", "Unidades, efectos, lógica, almacenes y el resto de construcciones."),
        new Cat("ray", "Rayos y láseres", "Rayos de minería, láseres de bloques, rayos de energía y de taladros. Su opacidad se controla con 'Opacidad de rayos'."),
        new Cat("player", "Unidad del jugador", "Alpha, beta, gamma y demás unidades de núcleo (cuerpo, contorno, armas)."),
        new Cat("unit", "Otras unidades", "Resto de unidades: cuerpo, patas, armas, celdas de color de equipo."),
        new Cat("shared", "Compartidas (varias categorías)", "Texturas que usan bloques/unidades de categorías distintas. Afectarlas cambia a todos a la vez.")
    };

    public static final int[] DIVS = {1, 2, 4, 8, 16, 32, 9999};
    public static final String[] DIV_LABEL = {"off", "÷2", "÷4", "÷8", "÷16", "÷32", "1 px"};

    public static final class Info{
        public AtlasRegion r;
        public final LinkedHashSet<String> owners = new LinkedHashSet<>(), cats = new LinkedHashSet<>(), roles = new LinkedHashSet<>();
        public boolean world, ui, blurFlag;
        public int n;
        public String cat;
    }

    // Recogidos durante el índice; los usa FlatRender para apagar el desenfoque, propulsores, etc.
    public static final ArrayList<Object[]> blurSpins = new ArrayList<>();   // {DrawBlurSpin, Float umbral}
    public static final ArrayList<Object[]> burstDrills = new ArrayList<>(); // {bloque, región flechas-blur}
    public static final ArrayList<Object[]> spinners = new ArrayList<>();    // {objeto, Float velocidad, Boolean esBlur}
    public static final ArrayList<Object[]> unitTypes = new ArrayList<>();   // {UnitType, engines}

    public final HashMap<String, Info> info = new HashMap<>();
    public final HashMap<String, ArrayList<Info>> byCat = new HashMap<>();
    public volatile boolean ready;

    private static final HashMap<String, String> ROLE = new HashMap<>();

    static{
        String[][] r = {
            {"region", "sprite principal"}, {"baseRegion", "base"}, {"base", "base"}, {"topRegion", "tapa superior"}, {"top", "tapa superior"},
            {"heatRegion", "brillo de calor"}, {"heat", "brillo de calor"}, {"liquidRegion", "líquido interno"}, {"liquid", "líquido interno"},
            {"outlineRegion", "contorno"}, {"outline", "contorno"}, {"outlines", "contorno"}, {"cellRegion", "celda de color de equipo"},
            {"teamRegions", "color de equipo"}, {"teamRegion", "color de equipo"}, {"variantRegions", "variantes aleatorias"},
            {"legRegion", "patas"}, {"legBaseRegion", "base de patas"}, {"jointRegion", "articulación"}, {"footRegion", "pie"},
            {"previewRegion", "vista previa"}, {"preview", "vista previa"}, {"shadowRegion", "sombra"}, {"softShadowRegion", "sombra suave"},
            {"regions", "piezas animadas"}, {"light", "luz"}, {"glowRegion", "brillo"}, {"arrowRegion", "flechas"}, {"arrowBlurRegion", "flechas (desenfoque)"},
            {"blurRegion", "desenfoque de giro"}, {"uiIcon", "icono de menú"}, {"fullIcon", "icono de menú"}, {"wreckRegions", "restos"},
            {"segmentRegions", "segmentos"}, {"treadRegions", "orugas"}, {"itemCircleRegion", "círculo de ítem"}
        };
        for(String[] p : r) ROLE.put(p[0], p[1]);
    }

    private static final Pattern RAY_RE = Pattern.compile("^(laser(-top|-white)?|minelaser|parallax-laser|drill-laser(-boost)?|point-laser|power-beam)(-center|-end)?$");
    private static final Pattern BLUR_RE = Pattern.compile("(^|-)blur(-|[0-9]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORE_WALL_RE = Pattern.compile("(^|-)ore(-|$)|graphitic", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIQUID_RE = Pattern.compile("(water|tar|slag|cryofluid|arkycite|oil|pooled|magma|lava|brine)", Pattern.CASE_INSENSITIVE);

    private static final String ENV = "mindustry.world.blocks.environment.";
    private static final Class<?> C_DRAWBLOCK = Refl.cls("mindustry.world.draw.DrawBlock");
    private static final Class<?> C_BLURSPIN = Refl.cls("mindustry.world.draw.DrawBlurSpin");
    private static final Class<?> C_DRAWPART = Refl.cls("mindustry.entities.part.DrawPart");
    private static final Class<?> C_WEAPON = Refl.cls("mindustry.type.Weapon");
    private static final Class<?> C_BURST = Refl.cls("mindustry.world.blocks.production.BurstDrill");
    private static final Class<?> C_DRILL = Refl.cls("mindustry.world.blocks.production.Drill");
    private static final Class<?> C_CORE = Refl.cls("mindustry.world.blocks.storage.CoreBlock");
    private static final Class<?> C_CHAR = Refl.cls(ENV + "CharacterOverlay");
    private static final Class<?> C_FLOOR = Refl.cls(ENV + "Floor");
    private static final Class<?> C_SPAWN = Refl.cls(ENV + "SpawnBlock");
    private static final Class<?> C_ORE = Refl.cls(ENV + "OreBlock");
    private static final Class<?> C_WALL = Refl.cls(ENV + "StaticWall");
    private static final Class<?> C_CLIFF = Refl.cls(ENV + "Cliff");
    private static final Class<?> C_PROP = Refl.cls(ENV + "Prop");

    private HashMap<String, String> owner;
    private final ArrayList<Object[]> jobs = new ArrayList<>(); // {contenido, nombre, categoría}
    private int ji;
    private boolean indexing;
    private final ArrayList<String> liquidLog = new ArrayList<>();

    // escalado
    private final HashMap<String, Integer> appliedRegion = new HashMap<>(), appliedAlpha = new HashMap<>();
    private final HashMap<String, Integer> want = new HashMap<>(), wantA = new HashMap<>();
    private final HashMap<String, String> applied = new HashMap<>();
    private final ArrayList<Object[]> queue = new ArrayList<>(); // {Info, categoría}
    private final HashSet<String> inQueue = new HashSet<>();      // sin duplicados en la cola
    private final HashMap<String, String> pendingSig = new HashMap<>();
    private final HashMap<String, Long> pendingAt = new HashMap<>();
    private int qi;
    private boolean blurOn;

    // ------------------------------------------------------------------ instalación

    public void install(){
        inst = this;
        Events.run(Trigger.update, this::frame);
        startIndex();
    }

    private void frame(){
        if(MemGuard.loading) return; // durante la carga de un mapa no se compite por memoria ni CPU
        try{
            if(pumpIndex()) return;
            pumpQueue();
        }catch(Throwable t){
            Refl.once("TextureScaler.frame", t);
        }
    }

    public int indexedJobs(){
        return ji;
    }

    public int totalJobs(){
        return jobs.size();
    }

    public int appliedDiv(String regionName){
        Integer v = appliedRegion.get(regionName);
        return v == null ? 1 : v;
    }

    // ------------------------------------------------------------------ clasificación

    private boolean isLiquidFloor(Block b){
        if(!Refl.getB(b, "isLiquid", false)) return false;
        if(Refl.get(b, "liquidDrop") != null) return true;
        return LIQUID_RE.matcher(String.valueOf(b.name)).find();
    }

    private String catOfBlock(Block b){
        if(Refl.is(b, C_CHAR)) return "protected"; // letras del mapa: no se tocan
        if(Refl.is(b, C_FLOOR)){
            if(Refl.getB(b, "allowCorePlacement", false) || Refl.is(b, C_SPAWN)) return "protected";
            if(Refl.getB(b, "wallOre", false) || Refl.is(b, C_ORE)) return "ore";
            if(isLiquidFloor(b)){
                liquidLog.add(String.valueOf(b.name));
                return "liquid";
            }
            return "terrain";
        }
        if(Refl.is(b, C_WALL) || Refl.is(b, C_CLIFF)){
            if(Refl.get(b, "itemDrop") != null || ORE_WALL_RE.matcher(String.valueOf(b.name)).find()) return "ore";
            return "wall";
        }
        if(Refl.is(b, C_PROP) || !b.synthetic()) return "prop";
        switch(String.valueOf(b.category)){
            case "turret": return "turret";
            case "distribution":
            case "liquid": return "duct";
            case "power": return "power";
            case "production":
            case "crafting": return "production";
            case "defense": return "defense";
            default: return "other";
        }
    }

    // ------------------------------------------------------------------ índice por reflexión

    private void note(TextureRegion r, String fname, String ownerName, String cat){
        if(!(r instanceof AtlasRegion ar)) return;
        String nm = ar.name;
        if(nm == null || !r.found()) return;

        Info e = info.get(nm);
        if(e == null){
            e = new Info();
            e.r = ar;
            info.put(nm, e);
        }
        if(e.owners.add(ownerName)) e.n++;
        e.cats.add(cat);
        String role = ROLE.get(fname);
        e.roles.add(role != null ? role : fname);
        if(cat.equals("item")){
            e.world = true;
            e.ui = true;
        }else if(fname.equals("uiIcon") || fname.equals("fullIcon")){
            e.ui = true;
        }else{
            e.world = true;
        }
    }

    private void handle(Object v, String fname, String ownerName, String cat, int depth){
        if(v == null) return;
        if(v instanceof TextureRegion tr){
            note(tr, fname, ownerName, cat);
            return;
        }
        if(depth > 3) return;

        if(Refl.is(v, C_DRAWBLOCK)){
            if(Refl.is(v, C_BLURSPIN)) blurSpins.add(new Object[]{v, Refl.getF(v, "blurThresh", 0.7f)});
            if(Refl.get(v, "rotateSpeed") instanceof Number n){
                spinners.add(new Object[]{v, n.floatValue(), Refl.is(v, C_BLURSPIN)});
            }
            walk(v, ownerName, cat, depth + 1);
            return;
        }
        if(Refl.is(v, C_DRAWPART) || Refl.is(v, C_WEAPON)){
            walk(v, ownerName, cat, depth + 1);
            return;
        }
        if(v instanceof Seq<?> sq){
            int n = Math.min(sq.size, 64);
            for(int i = 0; i < n; i++) handle(sq.get(i), fname, ownerName, cat, depth + 1);
            return;
        }
        Class<?> c = v.getClass();
        if(c.isArray() && !c.getComponentType().isPrimitive()){
            int n = Math.min(Array.getLength(v), 64);
            for(int i = 0; i < n; i++) handle(Array.get(v, i), fname, ownerName, cat, depth + 1);
        }
    }

    private void walk(Object obj, String ownerName, String cat, int depth){
        for(Field f : obj.getClass().getFields()){
            if(Modifier.isStatic(f.getModifiers())) continue;
            Object v;
            try{
                v = f.get(obj);
            }catch(Throwable t){
                continue;
            }
            handle(v, f.getName(), ownerName, cat, depth);
        }
    }

    private String ownerOf(String rname){
        String n = rname;
        while(true){
            if(owner.containsKey(n)) return n;
            String m = n.replaceAll("[0-9]+$", ""); // pisos/menas/muros: nombre1, nombre2...
            if(!m.equals(n) && m.length() > 0 && owner.containsKey(m)) return m;
            int i = n.lastIndexOf('-');
            if(i <= 0) return null;
            n = n.substring(0, i);
        }
    }

    private void startIndex(){
        owner = new HashMap<>();
        HashSet<String> core = new HashSet<>();

        for(Block b : Vars.content.blocks()){
            String n = String.valueOf(b.name);
            String c = catOfBlock(b);
            owner.put(n, c);
            if(Refl.is(b, C_CORE)){
                Object ut = Refl.get(b, "unitType");
                if(ut != null) core.add(String.valueOf(Refl.get(ut, "name")));
            }
            jobs.add(new Object[]{b, n, c});
            if(Refl.is(b, C_BURST)) burstDrills.add(new Object[]{b, Refl.get(b, "arrowBlurRegion")});
            // Drill.draw usa Drawf.spinSprite(rotatorRegion, ... * rotateSpeed): es el "motion blur" de los taladros normales
            if(Refl.is(b, C_DRILL) && Refl.get(b, "rotateSpeed") instanceof Number sp){
                spinners.add(new Object[]{b, sp.floatValue(), Boolean.TRUE});
            }
        }
        for(Item it : Vars.content.items()){
            String n = "item-" + it.name;
            owner.putIfAbsent(n, "item");
            jobs.add(new Object[]{it, n, "item"});
        }
        for(UnitType u : Vars.content.units()){
            String n = String.valueOf(u.name);
            String c = core.contains(n) ? "player" : "unit";
            owner.put(n, c);
            jobs.add(new Object[]{u, n, c});
            unitTypes.add(new Object[]{u, Refl.get(u, "engines")});
        }
        ji = 0;
        indexing = true;
        Log.info("[MO] texturas: indexando " + jobs.size() + " contenidos");
    }

    private boolean pumpIndex(){
        if(!indexing) return false;
        long t0 = Time.millis();
        while(ji < jobs.size() && Time.millis() - t0 < 5){
            Object[] j = jobs.get(ji++);
            try{
                walk(j[0], (String)j[1], (String)j[2], 0);
            }catch(Throwable t){
                Refl.once("índice " + j[1], t);
            }
        }
        if(ji >= jobs.size()){
            indexing = false;
            try{
                finishIndex();
            }catch(Throwable t){
                Refl.once("finishIndex", t);
            }
        }
        return true;
    }

    private ArrayList<AtlasRegion> allRegions(){
        ArrayList<AtlasRegion> out = new ArrayList<>();
        try{
            Seq<AtlasRegion> vals = Core.atlas.getRegionMap().values().toSeq();
            for(int i = 0; i < vals.size; i++) out.add(vals.get(i));
            if(!out.isEmpty()) return out;
        }catch(Throwable t){
            Refl.once("getRegionMap", t);
        }
        Seq<AtlasRegion> rs = Core.atlas.getRegions();
        for(int i = 0; i < rs.size; i++) out.add(rs.get(i));
        return out;
    }

    private void finishIndex(){
        ArrayList<AtlasRegion> all = allRegions();

        // Respaldo por nombre (bordes -edge, frames de cinta... que ningún campo público referencia)
        for(AtlasRegion r : all){
            if(r == null || r.name == null) continue;
            String name = r.name;
            if(info.containsKey(name) || name.endsWith("-icon") || name.endsWith("-full")) continue;
            String on = ownerOf(name);
            if(on == null) continue;
            Info e = new Info();
            e.r = r;
            e.world = true;
            e.n = 1;
            e.owners.add(on);
            e.cats.add(owner.get(on));
            e.roles.add("sprite (por nombre)");
            info.put(name, e);
        }

        // Rayos/láseres que el juego busca por nombre
        for(AtlasRegion r : all){
            if(r == null || r.name == null) continue;
            String rn = r.name;
            if(!info.containsKey(rn) && RAY_RE.matcher(rn).matches()){
                Info e = new Info();
                e.r = r;
                e.world = true;
                e.n = 1;
                e.owners.add("(efecto de rayo)");
                e.cats.add("ray");
                e.roles.add("rayo / láser");
                info.put(rn, e);
            }
        }

        // Sprites de desenfoque por nombre: se anulan con el toggle global, vengan de donde vengan
        for(AtlasRegion r : all){
            if(r == null || r.name == null) continue;
            String bn = r.name;
            if(!BLUR_RE.matcher(bn).find() || bn.endsWith("-icon") || bn.endsWith("-full")) continue;
            Info e = info.get(bn);
            if(e == null){
                e = new Info();
                e.r = r;
                e.n = 1;
                e.cats.add("other");
                info.put(bn, e);
            }
            e.world = true;
            e.blurFlag = true;
            e.owners.add("(sprite de desenfoque)");
            e.roles.add("desenfoque de giro");
        }

        for(Cat c : CATS) byCat.put(c.id, new ArrayList<>());
        byCat.put("protected", new ArrayList<>());
        ArrayList<Info> blurList = new ArrayList<>(), sharedList = new ArrayList<>();

        for(Info en : info.values()){
            if(en.blurFlag) blurList.add(en);
            if(!en.world) continue; // solo-icono de menú: no se toca
            String cat;
            if(en.cats.contains("protected")) cat = "protected";
            else if(RAY_RE.matcher(en.r.name).matches()) cat = "ray";
            else if(en.cats.size() == 1) cat = en.cats.iterator().next();
            else cat = "shared";
            en.cat = cat;
            byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(en);
            if(en.n >= 3 || en.cats.size() > 1) sharedList.add(en);
        }
        byCat.put("blur", blurList);
        for(ArrayList<Info> l : byCat.values()) Collections.sort(l, (a, b) -> a.r.name.compareTo(b.r.name));

        StringBuilder msg = new StringBuilder();
        for(Cat c : CATS) msg.append(c.id).append('=').append(byCat.get(c.id).size()).append(' ');
        Log.info("[MO] texturas indexadas (" + all.size() + " en atlas): " + msg + "protegidas=" + byCat.get("protected").size() + " blur=" + blurList.size());
        Log.info("[MO] pisos clasificados como LÍQUIDO (" + liquidLog.size() + "): " + String.join(", ", liquidLog));

        ready = true;
        blurOn = false; // fuerza que pollCats re-procese los '-blur' en el primer ciclo si el toggle está activo
        if(FlatRender.inst != null) FlatRender.inst.applyAnimFlags(true);
        if(Vars.ui != null) Vars.ui.showInfoToast("Texturas: índice listo", 3f);
    }

    // ------------------------------------------------------------------ escalado por categoría

    /** Se llama cada ~30 frames desde FlatRender. */
    public void pollCats(){
        if(!ready) return;

        boolean bo = Core.settings.getBool("flat-noblur", true);
        boolean blurChanged = bo != blurOn;
        blurOn = bo;

        for(Cat c : CATS){
            int idx = Core.settings.getInt("flat-cat-" + c.id, 1);
            int d = DIVS[Math.max(1, Math.min(DIVS.length, idx)) - 1];
            int al = c.id.equals("ray") ? Math.max(0, Math.min(100, Core.settings.getInt("flat-rayalpha", 100))) : 100;
            want.put(c.id, d);
            wantA.put(c.id, al);
            String sig = d + "|" + al;
            if(sig.equals(applied.get(c.id))){
                pendingSig.remove(c.id);
                continue;
            }
            // Mientras arrastras un slider cada valor intermedio sería una cola entera de texturas: se espera a que se asiente.
            if(!sig.equals(pendingSig.get(c.id))){
                pendingSig.put(c.id, sig);
                pendingAt.put(c.id, Time.millis());
            }else if(Time.millis() - pendingAt.get(c.id) >= 700L){
                applied.put(c.id, sig);
                pendingSig.remove(c.id);
                enqueueCat(c.id);
            }
        }
        if(blurChanged){
            ArrayList<Info> bl = byCat.get("blur");
            if(bl != null) for(Info e : bl) enqueue(e, e.cat);
            Log.info("[MO] desenfoque " + (bo ? "anulado" : "restaurado") + " en " + (bl == null ? 0 : bl.size()) + " sprites");
        }
    }

    private void enqueue(Info e, String cat){
        if(inQueue.add(e.r.name)) queue.add(new Object[]{e, cat});
    }

    private void enqueueCat(String id){
        ArrayList<Info> list = byCat.get(id);
        if(list == null) return;
        for(Info e : list) enqueue(e, id);
        Log.info("[MO] categoría " + id + " -> " + list.size() + " texturas en cola (div " + want.get(id) + ")");
    }

    /** Lectura de píxeles de la región del atlas. Se usa en vez del tipo concreto que devuelve Core.atlas.getPixmap. */
    private interface PixSrc{
        int get(int x, int y);
    }

    private static final class Job{
        Info e;
        int div, alpha, w, h;
        PixSrc src;
        Pixmap out;

        /** Corre en cualquier hilo: solo lee src y escribe en su propio Pixmap. */
        void compute(){
            out = div <= 1 ? copyPix(src, w, h) : downsample(src, w, h, div);
            if(alpha < 100) scaleAlpha(out, alpha);
        }
    }

    private Job prepare(Info e, String cat){
        AtlasRegion r = e.r;
        String key = r.name;
        Integer c1 = appliedRegion.get(key), c2 = appliedAlpha.get(key);
        int cur = c1 == null ? 1 : c1, curA = c2 == null ? 100 : c2;

        Integer dv = want.get(cat), av0 = wantA.get(cat);
        int div = dv == null ? 1 : dv;
        int alpha = av0 == null ? 100 : av0;
        if(e.blurFlag && blurOn) alpha = 0;
        if(cur == div && curA == alpha) return null;

        Job j = new Job();
        j.e = e;
        j.div = div;
        j.alpha = alpha;
        j.w = r.width;
        j.h = r.height;
        var pr = Core.atlas.getPixmap(r); // página original en memoria (nunca se modifica)
        j.src = (x, y) -> pr.get(x, y);
        return j;
    }

    private void pumpQueue(){
        if(qi >= queue.size()){
            if(qi > 0){
                queue.clear();
                inQueue.clear();
                qi = 0;
                Log.info("[MO] texturas procesadas");
            }
            return;
        }

        // ~15% de lo que dura un frame (0,5 a 3 ms): procesar texturas nunca debe notarse en los FPS.
        float frameMs = Core.graphics.getDeltaTime() * 1000f;
        long budgetNs = (long)(Math.max(0.5f, Math.min(3f, frameMs * 0.15f)) * 1_000_000f);
        long t0 = Time.nanos();
        while(qi < queue.size() && Time.nanos() - t0 < budgetNs){
            int batch = Math.min(8, queue.size() - qi);
            final Job[] js = new Job[batch];
            int cnt = 0;
            for(int i = 0; i < batch; i++){
                Object[] q = queue.get(qi++);
                inQueue.remove(((Info)q[0]).r.name);
                try{
                    Job j = prepare((Info)q[0], (String)q[1]);
                    if(j != null) js[cnt++] = j;
                }catch(Throwable t){
                    Refl.once("textura " + ((Info)q[0]).r.name, t);
                }
            }
            if(cnt == 0) continue;

            final int total = cnt;
            try{
                Cores.get().parallelFor(total, 3, (a, b) -> {
                    for(int i = a; i < b; i++) js[i].compute();
                });
            }catch(Throwable t){
                for(int i = 0; i < total; i++) if(js[i].out == null) js[i].compute();
            }

            for(int i = 0; i < total; i++){
                Job j = js[i];
                try{
                    upload(j.e.r, j.out);
                    appliedRegion.put(j.e.r.name, j.div);
                    appliedAlpha.put(j.e.r.name, j.alpha);
                }catch(Throwable t){
                    Refl.once("subir textura " + j.e.r.name, t);
                }finally{
                    if(j.out != null) j.out.dispose();
                }
            }
        }
    }

    // ------------------------------------------------------------------ píxeles

    private static Pixmap copyPix(PixSrc src, int w, int h){
        Pixmap out = new Pixmap(w, h);
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++) out.setRaw(x, y, src.get(x, y));
        return out;
    }

    private static Pixmap downsample(PixSrc src, int w, int h, int d){
        int cw = Math.min(d, w), ch = Math.min(d, h);
        Pixmap out = new Pixmap(w, h);
        for(int by = 0; by < h; by += ch){
            for(int bx = 0; bx < w; bx += cw){
                int ex = Math.min(bx + cw, w), ey = Math.min(by + ch, h);
                long sr = 0, sg = 0, sb = 0, sa = 0;
                int n = 0;
                for(int y = by; y < ey; y++){
                    for(int x = bx; x < ex; x++){
                        int c = src.get(x, y), a = c & 255;
                        sa += a;
                        n++;
                        sr += (long)((c >>> 24) & 255) * a;
                        sg += (long)((c >>> 16) & 255) * a;
                        sb += (long)((c >>> 8) & 255) * a;
                    }
                }
                int col = 0;
                if(sa > 0 && (double)sa / (n * 255.0) >= 0.5){
                    col = (Math.round((float)sr / sa) << 24) | (Math.round((float)sg / sa) << 16) | (Math.round((float)sb / sa) << 8) | 255;
                }
                for(int y = by; y < ey; y++) for(int x = bx; x < ex; x++) out.setRaw(x, y, col);
            }
        }
        return out;
    }

    private static void scaleAlpha(Pixmap pm, int pct){
        int w = pm.getWidth(), h = pm.getHeight();
        float k = pct / 100f;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                int c = pm.get(x, y), a = c & 255;
                if(a == 0) continue;
                pm.setRaw(x, y, (c & -256) | Math.round(a * k));
            }
        }
    }

    private static int texDim(Texture t, String field, String getter){
        Object v = Refl.get(t, field);
        if(v instanceof Number n) return n.intValue();
        try{
            return ((Number)t.getClass().getMethod(getter).invoke(t)).intValue();
        }catch(Throwable e){
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Sube el sprite Y reescribe el anillo de 1 px que lo rodea (el packer duplica el borde): si no, al mover la cámara
     * el muestreo en las costuras lee el borde ORIGINAL y se ven líneas con la textura vieja.
     */
    private static void upload(AtlasRegion r, Pixmap pm){
        int W = pm.getWidth(), H = pm.getHeight(), x0 = r.getX(), y0 = r.getY();
        Texture t = r.texture;
        t.draw(pm, x0, y0);

        int tw = texDim(t, "width", "getWidth"), th = texDim(t, "height", "getHeight");
        if(x0 < 1 || y0 < 1 || x0 + W + 1 > tw || y0 + H + 1 > th) return;

        Pixmap top = new Pixmap(W + 2, 1), bot = new Pixmap(W + 2, 1), lef = new Pixmap(1, H), rig = new Pixmap(1, H);
        for(int i = 0; i < W + 2; i++){
            int sx = Math.min(W - 1, Math.max(0, i - 1));
            top.setRaw(i, 0, pm.get(sx, 0));
            bot.setRaw(i, 0, pm.get(sx, H - 1));
        }
        for(int i = 0; i < H; i++){
            lef.setRaw(0, i, pm.get(0, i));
            rig.setRaw(0, i, pm.get(W - 1, i));
        }
        t.draw(top, x0 - 1, y0 - 1);
        t.draw(bot, x0 - 1, y0 + H);
        t.draw(lef, x0 - 1, y0);
        t.draw(rig, x0 + W, y0);
        top.dispose();
        bot.dispose();
        lef.dispose();
        rig.dispose();
    }
}
