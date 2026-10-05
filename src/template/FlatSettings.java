package template;

import arc.Core;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.util.Scaling;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;

import java.util.ArrayList;
import java.util.function.IntFunction;

/**
 * Categoría propia "Flat Performance" en Ajustes (antes la creaba main.js) y el panel de vista previa de texturas.
 * Los ajustes de culling/sleep que tenía el JS ya NO están aquí: viven en la categoría MindOptimized (mo-sleep, mo-sleep-hz),
 * que es el único mecanismo de sleep (el del JS estaba duplicado).
 */
public final class FlatSettings{
    private FlatSettings(){
    }

    static void title(String key, String text){
        Core.bundle.getProperties().put("setting." + key + ".name", text);
    }

    static void title(String key, String text, String desc){
        title(key, text);
        if(desc != null) Core.bundle.getProperties().put("setting." + key + ".description", desc);
    }

    // ------------------------------------------------------------------ categoría

    public static void register(){
        if(Vars.headless || Vars.ui == null || Vars.ui.settings == null) return;

        Drawable icon = (Drawable)Refl.getStatic(Icon.class, "layers");
        if(icon == null) icon = (Drawable)Icon.settings;
        Vars.ui.settings.addCategory("Flat Performance", icon, FlatSettings::build);
    }

    private static float uiWidth(){
        return Math.max(280f, Math.min(Core.graphics.getWidth() / Scl.scl(1f) - 120f, 620f));
    }

    static void hdr(SettingsMenuDialog.SettingsTable t, String key, String text){
        t.pref(new SettingsMenuDialog.SettingsTable.Setting("flat-h-" + key){
            @Override
            public void add(SettingsMenuDialog.SettingsTable table){
                Label l = new Label(text);
                l.setColor(Pal.accent);
                table.add(l).left().padTop(18f).padBottom(6f).row();
            }
        });
    }

    static void noteRow(SettingsMenuDialog.SettingsTable t, String key, String text){
        t.pref(new SettingsMenuDialog.SettingsTable.Setting("flat-n-" + key){
            @Override
            public void add(SettingsMenuDialog.SettingsTable table){
                Label l = new Label(text);
                l.setWrap(true);
                table.add(l).left().width(uiWidth()).padBottom(6f).row();
            }
        });
    }

    static void btnRow(SettingsMenuDialog.SettingsTable t, String key, String text, Runnable fn){
        title(key, text);
        t.pref(new SettingsMenuDialog.SettingsTable.Setting(key){
            @Override
            public void add(SettingsMenuDialog.SettingsTable table){
                table.button(text, () -> {
                    try{
                        fn.run();
                    }catch(Throwable e){
                        Refl.once("botón " + key, e);
                    }
                }).margin(14f).width(340f).pad(6f);
                table.row();
            }
        });
    }

    private static void build(SettingsMenuDialog.SettingsTable t){
        FlatRender fr = FlatRender.inst;

        hdr(t, "gen", "Rendimiento general");
        title("flat-div", "Resolución global del mundo");
        t.sliderPref("flat-div", 1, 1, 16, 1, s -> s <= 1 ? "off" : "÷" + s);
        title("flat-anim", "Animación del mundo");
        t.sliderPref("flat-anim", FlatRender.ANIM_SYNC, 0, FlatRender.ANIM_SYNC, 1,
            s -> s <= 0 ? "off" : (s >= FlatRender.ANIM_SYNC ? "sin límite" : s + " fps"));
        title("flat-nomip", "Sin mipmaps");
        t.checkPref("flat-nomip", true, v -> {
            if(fr == null) return;
            if(v) Core.app.post(() -> arc.util.Log.info("[MO] mipmaps desactivados en " + fr.killMipmaps() + " texturas"));
            else Vars.ui.showInfoToast("Reinicia el juego para restaurar los mipmaps", 4f);
        });
        title("flat-nearest", "Filtro nearest (píxeles nítidos)");
        t.checkPref("flat-nearest", true, v -> {
            if(fr == null) return;
            if(v) fr.setNearest(true);
            else Vars.ui.showInfoToast("Reinicia el juego para restaurar el filtro de texturas", 4f);
        });
        title("flat-noenv", "Sin overlays de entorno");
        t.checkPref("flat-noenv", true);
        btnRow(t, "flat-preset", "Aplicar preset de rendimiento (bloom, luces, clima, agua animada = off)", () -> {
            if(fr != null) fr.applyPreset();
        });

        hdr(t, "look", "Aspecto");
        title("flat-shading", "Sombreado: sombras y luces");
        t.checkPref("flat-shading", false, v -> {
            if(fr != null) fr.setShading(v);
        });
        title("flat-noblur", "Sin desenfoque (global): anula todo sprite/efecto de motion blur");
        t.checkPref("flat-noblur", true, v -> {
            if(fr != null) fr.applyAnimFlags(true);
        });
        title("flat-notrail", "Sin estelas");
        t.checkPref("flat-notrail", false, v -> {
            if(fr != null) fr.applyTrails(v);
        });
        title("flat-blackbg", "Fondo exterior negro");
        t.checkPref("flat-blackbg", true, v -> {
            if(fr != null) fr.onBlackBg(v);
        });
        title("flat-borderdark", "Oscuridad fuera del mapa");
        t.checkPref("flat-borderdark", true, v -> {
            if(fr != null) fr.onBorderDark(v);
        });
        title("flat-fogblack", "Niebla de guerra totalmente negra");
        t.checkPref("flat-fogblack", true);

        hdr(t, "ray", "Rayos y láseres");
        title("flat-rayalpha", "Opacidad de rayos");
        t.sliderPref("flat-rayalpha", 100, 0, 100, 5, s -> s + "%");

        hdr(t, "eng", "Unidades");
        title("flat-noengine", "Sin propulsores de unidades (llamas de aéreos y jugador)");
        t.checkPref("flat-noengine", false);

        hdr(t, "tex", "Resolución de texturas por categoría");
        noteRow(t, "tex", "Reduce la resolución de los sprites de cada categoría. Letras del mapa, zonas de núcleo y spawn nunca se tocan.");
        for(TextureScaler.Cat c : TextureScaler.CATS){
            title("flat-cat-" + c.id, "Resolución: " + c.name);
            t.sliderPref("flat-cat-" + c.id, 1, 1, TextureScaler.DIVS.length, 1, s -> TextureScaler.DIV_LABEL[s - 1]);
        }

        hdr(t, "sleep", "Culling de fábricas");
        noteRow(t, "sleep", "El culling agresivo del antiguo script vive ahora en la categoría MindOptimized (Hibernar fábricas fuera de vista): un solo mecanismo, sin duplicados.");

        hdr(t, "prev", "Vista previa");
        btnRow(t, "flat-panel", "Vista previa de texturas afectadas", FlatSettings::openPanel);
    }

    // ------------------------------------------------------------------ panel de vista previa

    private static final class Row{
        final String key, title, type, desc;
        final int def;
        final IntFunction<String> fmt;

        Row(String key, String title, String type, int def, String desc, IntFunction<String> fmt){
            this.key = key;
            this.title = title;
            this.type = type;
            this.def = def;
            this.desc = desc;
            this.fmt = fmt;
        }
    }

    private static final Row[] ROWS = {
        new Row("flat-div", "Resolución global del mundo", "int", 1, "Renderiza TODO el mundo a menor resolución (incluye texto dentro del mundo).", v -> v <= 1 ? "off" : "÷" + v),
        new Row("flat-anim", "Animación del mundo", "int", FlatRender.ANIM_SYNC, "off = congela todo lo que usa Time.time; 1–120 = limita los fps de las animaciones. Solo gráfico.", v -> v <= 0 ? "off" : (v >= FlatRender.ANIM_SYNC ? "sin límite" : v + " fps")),
        new Row("flat-shading", "Sombreado (sombras y luces)", "bool", 0, "ON = sombras de bloques/unidades, emisión de luz y luz dinámica. OFF = plano.", null),
        new Row("flat-noblur", "Sin desenfoque (global)", "bool", 1, "ON = anula el motion blur de TODO: -blur de giratorios, taladros de ráfaga, rotor de taladros y cualquier sprite '-blur'.", null),
        new Row("flat-notrail", "Sin estelas (trails)", "bool", 0, "ON = unidades y balas nuevas sin estela.", null),
        new Row("flat-blackbg", "Fondo exterior negro", "bool", 1, "ON = anula la textura de fondo/planeta del mapa y el piso 'space'.", null),
        new Row("flat-borderdark", "Oscuridad fuera del mapa", "bool", 1, "ON = el borde se funde a negro. OFF = puedes salirte y ver el exterior.", null),
        new Row("flat-rayalpha", "Opacidad de rayos", "int", 100, "Escala el alpha de los sprites de láser/rayo y del efecto lightning.", v -> v + "%"),
        new Row("flat-noengine", "Sin propulsores de unidades", "bool", 0, "Oculta las llamas de motor de aéreos y de la unidad del jugador.", null),
        new Row("flat-fogblack", "Niebla totalmente negra", "bool", 1, "La niebla dinámica pasa a negro opaco: solo se ve lo que tus unidades/edificios ven ahora.", null),
        new Row("flat-nomip", "Sin mipmaps", "bool", 1, "Las texturas dejan de muestrearse con mipmaps. Al desactivar hay que reiniciar.", null)
    };

    private static String infoText(TextureScaler.Info e){
        TextureScaler ts = TextureScaler.inst;
        StringBuilder sb = new StringBuilder();
        sb.append("Tamaño: ").append(e.r.width).append("×").append(e.r.height).append(" px\n");
        ArrayList<String> cn = new ArrayList<>();
        for(String c : e.cats) cn.add(catName(c));
        sb.append("Categoría asignada: ").append(catName(e.cat)).append('\n');
        if(e.cats.size() > 1) sb.append("Usada por categorías: ").append(String.join(", ", cn)).append('\n');
        sb.append("Se usa como: ").append(String.join(", ", e.roles)).append('\n');
        ArrayList<String> ow = new ArrayList<>(e.owners);
        sb.append("Usada por ").append(ow.size()).append(" contenido(s): ")
            .append(String.join(", ", ow.subList(0, Math.min(10, ow.size())))).append(ow.size() > 10 ? "..." : "").append('\n');
        if(e.ui) sb.append("También se ve en menús: ahí también cambia (no se puede separar).\n");
        if("protected".equals(e.cat)) sb.append("PROTEGIDA: nunca se modifica (zona de colocación de núcleo / spawn).\n");
        sb.append("Divisor aplicado ahora: ").append(ts == null ? 1 : ts.appliedDiv(e.r.name));
        return sb.toString();
    }

    private static String catName(String id){
        for(TextureScaler.Cat c : TextureScaler.CATS) if(c.id.equals(id)) return c.name;
        return String.valueOf(id);
    }

    public static void openPanel(){
        TextureScaler ts = TextureScaler.inst;
        BaseDialog dlg = new BaseDialog("Flat Performance");
        dlg.addCloseButton();

        Table root = new Table();
        root.top().left();
        final float W = Math.max(280f, Math.min(Core.graphics.getWidth() / Scl.scl(1f) - 150f, 760f));

        if(ts == null || !ts.ready){
            Label l = new Label("Indexando texturas... (" + (ts == null ? 0 : ts.indexedJobs()) + "/" + (ts == null ? 0 : ts.totalJobs())
                + "). Cierra y vuelve a abrir en unos segundos.");
            l.setWrap(true);
            root.add(l).left().width(W).padBottom(4f).row();
        }

        header(root, "Ajustes del mod");
        Table tbl = new Table();
        for(Row s : ROWS){
            String v = s.type.equals("bool") ? (Core.settings.getBool(s.key, s.def != 0) ? "ON" : "OFF") : s.fmt.apply(Core.settings.getInt(s.key, s.def));
            settingRow(tbl, W, s.title, v, s.desc);
        }
        for(TextureScaler.Cat c : TextureScaler.CATS){
            int idx = Core.settings.getInt("flat-cat-" + c.id, 1);
            ArrayList<TextureScaler.Info> l = ts == null ? null : ts.byCat.get(c.id);
            settingRow(tbl, W, "Resolución: " + c.name, TextureScaler.DIV_LABEL[Math.max(1, Math.min(7, idx)) - 1],
                (l == null ? 0 : l.size()) + " texturas. " + c.desc);
        }
        root.add(tbl).left().row();

        Label tip = new Label("Toca una miniatura para ver cuándo/cómo se usa y qué contenidos la comparten. Muestran la textura tal como está AHORA en el atlas.");
        tip.setWrap(true);
        root.add(tip).left().width(W).padTop(8f).padBottom(4f).row();

        if(ts != null){
            section(root, W, "Sprites de desenfoque anulados",
                "Todo sprite cuyo nombre contiene 'blur'. Con 'Sin desenfoque (global)' activo se vuelven transparentes.", ts.byCat.get("blur"));
            for(TextureScaler.Cat c : TextureScaler.CATS){
                int idx = Core.settings.getInt("flat-cat-" + c.id, 1);
                section(root, W, c.name + "  [" + TextureScaler.DIV_LABEL[Math.max(1, Math.min(7, idx)) - 1] + "]", c.desc, ts.byCat.get(c.id));
            }
            section(root, W, "Protegidas (nunca se modifican)",
                "Letras del mapa, pisos donde se puede colocar un núcleo y el spawn. Se dejan intactos.", ts.byCat.get("protected"));
        }

        ScrollPane pane = new ScrollPane(root);
        pane.setScrollingDisabled(false, false);
        pane.setFadeScrollBars(false);
        dlg.cont.add(pane).grow();
        dlg.show();
    }

    private static void header(Table root, String text){
        Label l = new Label(text);
        l.setColor(Pal.accent);
        root.add(l).left().padTop(16f).padBottom(4f).row();
    }

    private static void settingRow(Table tbl, float W, String titleText, String valText, String descText){
        Label a = new Label(titleText);
        a.setWrap(true);
        Label b = new Label(valText);
        b.setColor(Pal.accent);
        Label c = new Label(descText);
        c.setWrap(true);
        tbl.add(a).left().width(W * 0.28f).padRight(8f).padBottom(6f);
        tbl.add(b).left().width(W * 0.12f).padRight(8f).padBottom(6f);
        tbl.add(c).left().width(W * 0.6f).padBottom(6f);
        tbl.row();
    }

    private static Table thumb(TextureScaler.Info e){
        Table t = new Table();
        Image img = new Image(new TextureRegionDrawable(e.r));
        img.setScaling(Scaling.fit);
        img.touchable = Touchable.enabled;
        img.clicked(() -> Vars.ui.showInfoText(String.valueOf(e.r.name), infoText(e)));
        t.add(img).size(48f).row();
        String nm = String.valueOf(e.r.name);
        if(nm.length() > 11) nm = nm.substring(0, 10) + "…";
        Label l = new Label(nm);
        l.setFontScale(0.6f);
        t.add(l).row();
        return t;
    }

    private static void section(Table root, float W, String name, String desc, ArrayList<TextureScaler.Info> list){
        int size = list == null ? 0 : list.size();
        header(root, name + "  ·  " + size + " texturas");
        Label d = new Label(desc);
        d.setWrap(true);
        root.add(d).left().width(W).padBottom(4f).row();
        if(size == 0) return;

        Table row = new Table();
        row.left();
        int shown = Math.min(size, 80);
        for(int k = 0; k < shown; k++) row.add(thumb(list.get(k))).pad(3f);
        if(size > shown) row.add(new Label("+" + (size - shown) + " más")).pad(8f);

        ScrollPane sp = new ScrollPane(row);
        sp.setScrollingDisabled(false, true); // fila con scroll horizontal
        sp.setFadeScrollBars(false);
        root.add(sp).left().width(W).height(90f).row();
    }
}
