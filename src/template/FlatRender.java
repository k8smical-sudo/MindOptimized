package template;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.gl.FrameBuffer;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.graphics.Shaders;
import mindustry.type.UnitType;
import mindustry.world.Block;

import java.lang.reflect.Array;
import java.util.ArrayList;

/**
 * Port nativo de la parte de render/aspecto del antiguo main.js (Flat Performance).
 *
 * Se engancha a Trigger.preDraw / postDraw, que son exactamente los puntos donde el JS hacía su trabajo, pero sin Rhino:
 * cero reflexión de Rhino por frame y sin la sobrecarga de los wrappers "guarded". Los ajustes conservan las mismas
 * claves ("flat-*") para que tu configuración guardada siga valiendo.
 */
public class FlatRender{
    public static FlatRender inst;

    public static final String K_DIV = "flat-div", K_ANIM = "flat-anim", K_SHADING = "flat-shading", K_NOBLUR = "flat-noblur",
        K_NOTRAIL = "flat-notrail", K_BLACKBG = "flat-blackbg", K_BORDERDARK = "flat-borderdark", K_RAYALPHA = "flat-rayalpha",
        K_NOENGINE = "flat-noengine", K_FOGBLACK = "flat-fogblack", K_NOMIP = "flat-nomip", K_NEAREST = "flat-nearest",
        K_NOENV = "flat-noenv";

    public static final int ANIM_SYNC = 121; // 0 = congelada, 1..120 = fps, 121 = sin límite

    // ---- resolución global del mundo
    private FrameBuffer fb;
    private boolean snapped, fbBound;
    private float savedX, savedY;
    private int div = 1;

    // ---- animación cuantizada
    private boolean animActive;
    private float realT, realG, frozenT, frozenG;
    private int animV = ANIM_SYNC;

    // ---- borde / fondo
    private boolean blackBg = true, borderDark = true;
    private Object[] origRules;

    // ---- varios
    private boolean noEnv = true;
    private float fogSaved = -1f;
    private int tick, borderTick;
    private String animSig = "";
    private boolean engOff;
    private Seq<Object> emptySeq;
    private int rayApplied = 100;
    private Object lightOrig;

    private final ArrayList<Object[]> shadeB = new ArrayList<>(), shadeU = new ArrayList<>();
    private ArrayList<Object[]> trailU, trailB;

    // ------------------------------------------------------------------ instalación

    public void install(){
        inst = this;

        Events.run(Trigger.preDraw, () -> {
            try{
                beginWorld();
            }catch(Throwable t){
                Refl.once("render (inicio)", t);
                abortWorld();
            }
        });
        Events.run(Trigger.postDraw, () -> {
            try{
                endWorld();
            }catch(Throwable t){
                Refl.once("render (fin)", t);
                abortWorld();
            }
        });
        Events.on(WorldLoadEvent.class, e -> onWorldLoad());

        // Primera vez: valores base de rendimiento (los mismos que aplicaba el JS al arrancar por primera vez).
        if(!Core.settings.getBool("flat-init", false)){
            applyPreset();
            Core.settings.put("flat-init", true);
        }

        safe("mipmaps", () -> {
            if(Core.settings.getBool(K_NOMIP, true)) Log.info("[MO] mipmaps desactivados en " + killMipmaps() + " texturas");
        });
        safe("filtro nearest", () -> setNearest(Core.settings.getBool(K_NEAREST, true)));
        safe("sombreado", () -> setShading(Core.settings.getBool(K_SHADING, false)));
        safe("estelas", () -> applyTrails(Core.settings.getBool(K_NOTRAIL, false)));
        safe("borde", () -> {
            blackBg = Core.settings.getBool(K_BLACKBG, true);
            borderDark = Core.settings.getBool(K_BORDERDARK, true);
            applySpace(blackBg);
        });
        safe("overlays de entorno", this::killEnv);
        Log.info("[MO] FlatRender listo");
    }

    private static void safe(String name, Runnable r){
        try{
            r.run();
        }catch(Throwable t){
            Refl.once(name, t);
        }
    }

    /** Valores base de rendimiento. (El límite de FPS y el refresco los gestiona PerfTuner.) */
    public void applyPreset(){
        Core.settings.put("pixelate", false);
        Core.settings.put("linear", false);
        Core.settings.put("bloom", false);
        Core.settings.put("animatedshields", true);
        Core.settings.put("animatedwater", false);
        Core.settings.put("drawlight", false);
        Core.settings.put("showweather", false);
        Log.info("[MO] preset de rendimiento aplicado (pixelate/linear/bloom/agua animada/luces/clima = off)");
    }

    // ------------------------------------------------------------------ frame

    private void abortWorld(){
        WorldWindow.end();
        snapped = false;
        animEnd();
        endFb();
    }

    /** Cierra el framebuffer si quedó abierto (flag propio: no dependemos de isBound()). */
    private void endFb(){
        if(!fbBound) return;
        fbBound = false;
        try{
            fb.end();
        }catch(Throwable ignored){
        }
    }

    private void beginWorld(){
        if(snapped){ // el frame anterior no terminó bien
            snapped = false;
            endFb();
            Core.camera.position.set(savedX, savedY);
        }
        if(animActive) animEnd();

        if((tick++ % 30) == 0) pollSettings();
        if((borderTick++ % 15) == 0) safe("borde del mapa", this::applyBorder);
        if(noEnv) killEnv();
        animBegin();

        int d = div;
        if(d <= 1 || !Vars.state.isGame()){
            WorldWindow.begin(1); // recorte directo sobre la pantalla (si la zona personalizada está activa)
            return;
        }

        int w = Math.max(2, (int)Math.ceil(Core.graphics.getWidth() / (float)d));
        int h = Math.max(2, (int)Math.ceil(Core.graphics.getHeight() / (float)d));
        if(fb == null) fb = new FrameBuffer();
        if(fb.getWidth() != w || fb.getHeight() != h){
            fb.resize(w, h);
            fb.getTexture().setFilter(Texture.TextureFilter.nearest, Texture.TextureFilter.nearest);
        }

        // La cámara se alinea a la cuadrícula de baja resolución: si no, los píxeles "nadan" al mover la cámara.
        savedX = Core.camera.position.x;
        savedY = Core.camera.position.y;
        float tx = Core.camera.width / w, ty = Core.camera.height / h;
        Core.camera.position.set(Math.round(savedX / tx) * tx, Math.round(savedY / ty) * ty);

        fb.begin(Color.clear);
        fbBound = true;
        snapped = true;
        WorldWindow.begin(d); // recorte en píxeles del framebuffer reducido
    }

    private void endWorld(){
        animEnd();
        if(!snapped){
            WorldWindow.end();
            return;
        }
        snapped = false;
        fbBound = false;
        WorldWindow.end();
        fb.end();
        WorldWindow.beginBlit(); // pantalla negra y volcado solo dentro de la zona
        Blending.disabled.apply();
        fb.blit(Shaders.screenspace);
        WorldWindow.end();
        Core.camera.position.set(savedX, savedY);
    }

    private void pollSettings(){
        div = Math.max(1, Core.settings.getInt(K_DIV, 1));
        if(div > 1 && Core.settings.getBool("pixelate")) Core.settings.put("pixelate", false);
        blackBg = Core.settings.getBool(K_BLACKBG, true);
        borderDark = Core.settings.getBool(K_BORDERDARK, true);
        noEnv = Core.settings.getBool(K_NOENV, true);

        int nv = Core.settings.getInt(K_ANIM, ANIM_SYNC);
        if(nv != animV){
            animV = nv;
            if(nv <= 0){
                frozenT = Time.time;
                frozenG = Time.globalTime;
            }
            Log.info("[MO] animación: " + (nv <= 0 ? "congelada" : nv >= ANIM_SYNC ? "sin límite" : nv + " fps"));
        }

        if(TextureScaler.inst != null) TextureScaler.inst.pollCats();
        safe("desenfoque", () -> applyAnimFlags(false));
        safe("propulsores", this::pollEngines);
        safe("opacidad de rayos", this::pollRay);
        safe("niebla negra", () -> applyFogBlack(Core.settings.getBool(K_FOGBLACK, true)));
    }

    private void onWorldLoad(){
        origRules = null;
        fogSaved = -1f;
        safe("mapa", () -> {
            applyBorder();
            if(noEnv) Log.info("[MO] envRenderers eliminados: " + killEnv());
        });
    }

    // ------------------------------------------------------------------ animación (cuantiza Time.time solo al dibujar el mundo)

    private void animBegin(){
        if(animV >= ANIM_SYNC) return;
        realT = Time.time;
        realG = Time.globalTime;
        if(animV <= 0){
            Time.time = frozenT;
            Time.globalTime = frozenG;
        }else{
            float step = 60f / animV;
            Time.time = (float)Math.floor(realT / step) * step;
            Time.globalTime = (float)Math.floor(realG / step) * step;
        }
        animActive = true;
    }

    private void animEnd(){
        if(!animActive) return;
        animActive = false;
        Time.time = realT;
        Time.globalTime = realG;
    }

    // ------------------------------------------------------------------ borde del mapa

    private static final String[] RULE_BG = {"backgroundTexture", "planetBackground", "customBackgroundCallback"};

    private void applyBorder(){
        Object r = Vars.state.rules;
        if(r == null) return;
        if(origRules == null){
            origRules = new Object[RULE_BG.length];
            for(int i = 0; i < RULE_BG.length; i++) origRules[i] = Refl.get(r, RULE_BG[i]);
        }
        for(int i = 0; i < RULE_BG.length; i++){
            Object cur = Refl.get(r, RULE_BG[i]);
            Object wantV = blackBg ? null : origRules[i];
            if(cur != wantV) Refl.set(r, RULE_BG[i], wantV);
        }
        if(Refl.getB(r, "borderDarkness", borderDark) != borderDark) Refl.setBool(r, "borderDarkness", borderDark);
        if(Refl.getStaticBool(Vars.class, "enableDarkness", borderDark) != borderDark) Refl.setStaticBool(Vars.class, "enableDarkness", borderDark);
    }

    private Object spaceOrig;

    public void applySpace(boolean black){
        Object regions = Refl.get(Blocks.space, "variantRegions");
        if(regions != null && regions.getClass().isArray() && Array.getLength(regions) > 0){
            if(spaceOrig == null) spaceOrig = Array.get(regions, 0);
            Array.set(regions, 0, black ? Core.atlas.find("clear") : spaceOrig);
        }
        Object floor = Vars.renderer.blocks.floor;
        if(!Refl.call(floor, "clearTiles") && Vars.state.isGame() && Vars.world.tiles != null) Refl.call(floor, "reload");
    }

    public void onBlackBg(boolean v){
        blackBg = v;
        safe("espacio", () -> applySpace(v));
    }

    public void onBorderDark(boolean v){
        borderDark = v;
    }

    private int killEnv(){
        Object list = Refl.get(Vars.renderer, "envRenderers");
        if(list instanceof Seq<?> s && s.size > 0){
            int n = s.size;
            s.clear();
            return n;
        }
        return 0;
    }

    // ------------------------------------------------------------------ sombreado, estelas, texturas

    public void setShading(boolean on){
        if(shadeB.isEmpty() && shadeU.isEmpty()){
            for(Block b : Vars.content.blocks()){
                shadeB.add(new Object[]{b, Refl.getB(b, "hasShadow", true), Refl.getB(b, "emitLight", false), Refl.getF(b, "lightRadius", 0f)});
            }
            for(UnitType u : Vars.content.units()){
                shadeU.add(new Object[]{u, Refl.getF(u, "shadowElevation", 0f), Refl.getF(u, "shadowElevationScl", 0f),
                    Refl.get(u, "shadowRegion"), Refl.get(u, "softShadowRegion")});
            }
        }
        Object clear = Core.atlas.find("clear");
        for(Object[] o : shadeB){
            Refl.setBool(o[0], "hasShadow", on ? (Boolean)o[1] : false);
            Refl.setBool(o[0], "emitLight", on ? (Boolean)o[2] : false);
            Refl.setNum(o[0], "lightRadius", on ? (Float)o[3] : 0f);
        }
        for(Object[] o : shadeU){
            Refl.setNum(o[0], "shadowElevation", on ? (Float)o[1] : 0f);
            Refl.setNum(o[0], "shadowElevationScl", on ? (Float)o[2] : 0f);
            Refl.set(o[0], "shadowRegion", on ? o[3] : clear);
            Refl.set(o[0], "softShadowRegion", on ? o[4] : clear);
        }
        Core.settings.put("drawlight", on);
        Log.info("[MO] sombreado " + (on ? "activado" : "desactivado") + " (las sombras de bloques se refrescan al recargar el mapa)");
    }

    public void applyTrails(boolean off){
        if(trailU == null){
            trailU = new ArrayList<>();
            trailB = new ArrayList<>();
            for(UnitType u : Vars.content.units()) trailU.add(new Object[]{u, Refl.getF(u, "trailLength", 0f)});
            for(Object b : Vars.content.bullets()) trailB.add(new Object[]{b, Refl.getF(b, "trailLength", -1f)});
        }
        for(Object[] s : trailU) Refl.setNum(s[0], "trailLength", off ? 0f : (Float)s[1]);
        for(Object[] s : trailB) Refl.setNum(s[0], "trailLength", off ? -1f : (Float)s[1]);
        Log.info("[MO] estelas " + (off ? "desactivadas" : "activadas") + " (afecta unidades/balas nuevas)");
    }

    public int killMipmaps(){
        Seq<Texture> list = new Seq<>();
        Core.assets.getAll(Texture.class, list);
        Texture.TextureFilter f = Core.settings.getBool(K_NEAREST, true) ? Texture.TextureFilter.nearest : Texture.TextureFilter.linear;
        int n = 0;
        for(int i = 0; i < list.size; i++){
            Texture t = list.get(i);
            if(t.getMinFilter().isMipMap()){
                t.setFilter(f, t.getMagFilter());
                n++;
            }
        }
        return n;
    }

    public void setNearest(boolean on){
        if(!on) return; // restaurar el filtro original requiere reiniciar
        Core.atlas.getTextures().each(t -> t.setFilter(Texture.TextureFilter.nearest, Texture.TextureFilter.nearest));
    }

    // ------------------------------------------------------------------ desenfoque de giro, propulsores, rayos, niebla

    public void applyAnimFlags(boolean force){
        if(TextureScaler.inst == null || !TextureScaler.inst.ready) return;
        boolean blur = Core.settings.getBool(K_NOBLUR, true);
        String sig = String.valueOf(blur);
        if(sig.equals(animSig) && !force) return;
        animSig = sig;

        // (1) regiones -blur de giratorios (DrawBlurSpin) y taladros de ráfaga
        for(Object[] s : TextureScaler.blurSpins) Refl.setNum(s[0], "blurThresh", blur ? 99f : (Float)s[1]);
        Object none = Core.atlas.find("flat-none"); // no existe -> found() = false: el taladro la omite
        for(Object[] s : TextureScaler.burstDrills) Refl.set(s[0], "arrowBlurRegion", blur ? none : s[1]);
        // (2) giratorios: Drawf.spinSprite mezcla 2 giros con alpha y no se puede apagar; solo queda dejar el rotor quieto
        for(Object[] s : TextureScaler.spinners) Refl.setNum(s[0], "rotateSpeed", ((Boolean)s[2] && blur) ? 0f : (Float)s[1]);
        Log.info("[MO] desenfoque " + (blur ? "off" : "on") + " (" + TextureScaler.blurSpins.size() + " giratorios, "
            + TextureScaler.burstDrills.size() + " taladros, " + TextureScaler.spinners.size() + " rotores)");
    }

    private void pollEngines(){
        boolean off = Core.settings.getBool(K_NOENGINE, false);
        if(off == engOff) return;
        engOff = off;
        if(emptySeq == null) emptySeq = new Seq<>();
        for(Object[] ut : TextureScaler.unitTypes) Refl.set(ut[0], "engines", off ? emptySeq : ut[1]);
        Log.info("[MO] propulsores " + (off ? "ocultos" : "visibles"));
    }

    @SuppressWarnings("unchecked")
    private void pollRay(){
        int op = Math.max(0, Math.min(100, Core.settings.getInt(K_RAYALPHA, 100)));
        if(op == rayApplied) return;
        rayApplied = op;

        Object fx = Fx.lightning;
        if(lightOrig == null) lightOrig = Refl.get(fx, "renderer");
        if(lightOrig == null) return;
        if(op >= 100){
            Refl.set(fx, "renderer", lightOrig);
            return;
        }

        final Cons<Object> orig = (Cons<Object>)lightOrig;
        final float k = op / 100f;
        Cons<Object> wrapped = e -> {
            Object cc = Refl.get(e, "color");
            if(cc instanceof Color c){
                float a = c.a;
                c.a = a * k;
                try{
                    orig.get(e);
                }finally{
                    c.a = a;
                }
            }else{
                orig.get(e);
            }
        };
        Refl.set(fx, "renderer", wrapped);
        Log.info("[MO] opacidad de rayos: " + op + "%");
    }

    /** Niebla dinámica (explorada pero sin visión actual) a negro opaco. Solo visual. */
    private void applyFogBlack(boolean on){
        Object r = Vars.state.rules;
        if(r == null || !Vars.state.rules.fog) return;
        Object dc = Refl.get(r, "dynamicColor");
        if(!(dc instanceof Color c)) return;
        if(fogSaved < 0f) fogSaved = c.a;
        float w = on ? 1f : fogSaved;
        if(c.a != w) c.a = w;
    }
}
