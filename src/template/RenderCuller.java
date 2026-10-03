package template;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Fill;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.storage.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Reduce los vertices del camino DINAMICO (SortedSpriteBatch / MySpriteBatch de MindustryX) sin parchear el nucleo:
 * justo antes de BlockRenderer.drawBlocks() (Trigger.drawOver) cambia por reflexion el campo privado `tileview`
 * por una copia filtrada, y lo restaura en postDraw. processBlocks() siempre trabaja sobre la lista ORIGINAL.
 *
 * Tres recortes, todos opcionales:
 *  1) niebla: no dibujar edificios "recordados" (wasVisible) que ahora estan bajo niebla.
 *  2) LOD de icono: con la camara lejos, un edificio = 1 quad (Block.fullIcon) en vez de base + tapa + rotor + ...
 *  3) LOD solido + enmallado voraz: mas lejos aun, las casillas 1x1 se pintan con su mapColor y se FUSIONAN en
 *     rectangulos (una cinta de 50 casillas = 1 quad).
 */
public class RenderCuller{
    public static final String K_ON = "mo-on", K_FOG = "mo-fog", K_ICON = "mo-lod-icon", K_SOLID = "mo-lod-solid",
        K_MERGE = "mo-merge", K_STATS = "mo-stats";

    private static final int MAX_GRID = 1 << 21; // 2M celdas = 8 MB como maximo

    private Field viewField;
    private boolean failed, swapped;
    private Seq<Tile> original;
    private final Seq<Tile> scratch = new Seq<>(false, 2048, Tile.class);

    // candidatos a "solido" del frame
    private final Seq<Tile> solidTiles = new Seq<>(false, 2048, Tile.class);
    private final IntSeq solidColors = new IntSeq();
    private int[] grid = new int[0];

    // ajustes en cache (se releen cada 15 frames)
    private boolean on = true, cullFog = true, merge = true;
    private int iconPx = 14, solidPx = 6;
    private int poll;

    // estadisticas
    private int sIn, sFog, sIcon, sSolidTiles, sSolidQuads, sKept, frames;
    private long nanos, worldStart, worldNanos;
    private boolean statsOn = true;

    public RenderCuller(){
        Events.run(Trigger.preDraw, () -> {
            restore();
            worldStart = Time.nanos();
        });
        Events.run(Trigger.drawOver, this::apply);
        Events.run(Trigger.postDraw, () -> {
            restore();
            worldNanos += Time.nanos() - worldStart;
        });
    }

    private void readSettings(){
        on = Core.settings.getBool(K_ON, true);
        cullFog = Core.settings.getBool(K_FOG, true);
        merge = Core.settings.getBool(K_MERGE, true);
        iconPx = Core.settings.getInt(K_ICON, 14);
        solidPx = Core.settings.getInt(K_SOLID, 6);
        statsOn = Core.settings.getBool(K_STATS, true);
    }

    @SuppressWarnings("unchecked")
    private boolean bind(){
        if(viewField != null) return true;
        if(failed) return false;
        try{
            Field f = mindustry.graphics.BlockRenderer.class.getDeclaredField("tileview");
            f.setAccessible(true);
            Object v = f.get(renderer.blocks);
            if(!(v instanceof Seq)) throw new IllegalStateException("tileview no es Seq");
            viewField = f;
            Log.info("[MO] enganchado a BlockRenderer.tileview");
            return true;
        }catch(Throwable t){
            failed = true;
            Log.err("[MO] no pude enganchar BlockRenderer.tileview: " + t);
            for(Field f : mindustry.graphics.BlockRenderer.class.getDeclaredFields())
                Log.info("[MO] BlockRenderer campo: " + f.getType().getSimpleName() + " " + f.getName());
            return false;
        }
    }

    private void restore(){
        if(!swapped) return;
        swapped = false;
        try{ viewField.set(renderer.blocks, original); }catch(Throwable t){ failed = true; Log.err("[MO] restore fallo: " + t); }
        original = null;
    }

    private static boolean eligible(Block block, Building build){
        if(block == Blocks.air || block instanceof ConstructBlock || block instanceof LogicDisplay || block instanceof CoreBlock) return false;
        return block.fullIcon != null && block.fullIcon.found();
    }

    @SuppressWarnings("unchecked")
    private void apply(){
        if((poll++ & 15) == 0) readSettings();
        if(!on || headless || !state.isGame() || !bind()) return;

        long t0 = Time.nanos();
        try{
            Seq<Tile> src = (Seq<Tile>)viewField.get(renderer.blocks);
            if(src == null || src.size == 0) return;

            Team pteam = player.team();
            boolean fogOn = cullFog && state.rules.fog;
            float ppt = Core.graphics.getWidth() / Core.camera.width * tilesize; // pixeles por casilla
            boolean solidLod = solidPx > 0 && ppt <= solidPx;
            boolean iconLod = iconPx > 0 && ppt <= iconPx;

            scratch.clear();
            solidTiles.clear();
            solidColors.clear();
            int fog = 0, icon = 0;

            for(int i = 0; i < src.size; i++){
                Tile tile = src.items[i];
                Block block = tile.block();
                Building build = tile.build;

                if(block == Blocks.air){ scratch.add(tile); continue; }

                boolean fogged = build != null && build.inFogTo(pteam);
                if(fogged){
                    if(fogOn){ fog++; continue; } // recordado bajo niebla: no se dibuja
                    scratch.add(tile);            // comportamiento vanilla
                    continue;
                }

                // visible: si nunca se marco como visto, que lo procese vanilla (visibleFlags, minimapa, sombras)
                if((solidLod || iconLod) && eligible(block, build) && (build == null || build.wasVisible)){
                    if(solidLod){
                        Color c = Tmp.c1.set(block.mapColor);
                        if(build != null && build.team != pteam) c.lerp(build.team.color, 0.45f);
                        c.a = 1f;
                        solidTiles.add(tile);
                        solidColors.add(c.rgba());
                    }else{
                        Draw.z(Layer.block);
                        Draw.rect(block.fullIcon, tile.drawx(), tile.drawy(), build != null ? build.drawrot() : 0f);
                        Draw.reset();
                        icon++;
                    }
                    continue;
                }
                scratch.add(tile);
            }

            int quads = solidTiles.size > 0 ? emitSolid() : 0;

            sIn += src.size; sFog += fog; sIcon += icon; sSolidTiles += solidTiles.size; sSolidQuads += quads; sKept += scratch.size;

            original = src;
            viewField.set(renderer.blocks, scratch);
            swapped = true;
        }catch(Throwable t){
            restore();
            failed = true;
            Log.err("[MO] apply fallo, culling desactivado: " + t);
        }
        nanos += Time.nanos() - t0;
        if(++frames >= 600) flushStats();
    }

    /** Pinta las casillas "solidas": 1x1 se fusionan (enmallado voraz por filas y columnas); las grandes van sueltas. */
    private int emitSolid(){
        int quads = 0, n = solidTiles.size;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for(int i = 0; i < n; i++){
            Tile t = solidTiles.items[i];
            if(t.block().size != 1) continue;
            minX = Math.min(minX, t.x); maxX = Math.max(maxX, t.x);
            minY = Math.min(minY, t.y); maxY = Math.max(maxY, t.y);
        }
        int w = maxX - minX + 1, h = maxY - minY + 1;
        boolean useGrid = merge && maxX >= 0 && (long)w * h <= MAX_GRID;

        if(useGrid){
            if(grid.length < w * h) grid = new int[w * h];
            else Arrays.fill(grid, 0, w * h, 0);
        }

        Draw.z(Layer.block);
        for(int i = 0; i < n; i++){
            Tile t = solidTiles.items[i];
            int c = solidColors.items[i];
            if(useGrid && t.block().size == 1){
                grid[(t.y - minY) * w + (t.x - minX)] = c;
            }else{
                int s = t.block().size * tilesize;
                Draw.color(Tmp.c2.set(c));
                Fill.rect(t.drawx(), t.drawy(), s, s);
                quads++;
            }
        }

        if(useGrid){
            final int fminX = minX, fminY = minY;
            quads += mergeGrid(grid, w, h, (x, y, rw, rh, c) -> {
                Draw.color(Tmp.c2.set(c));
                Fill.rect((fminX + x + (rw - 1) / 2f) * tilesize, (fminY + y + (rh - 1) / 2f) * tilesize, rw * tilesize, rh * tilesize);
            });
        }
        Draw.reset();
        return quads;
    }


    public interface RectSink{ void rect(int x, int y, int w, int h, int color); }

    /** Enmallado voraz: fusiona celdas contiguas del mismo color (!=0) en rectangulos. Consume (pone a 0) la rejilla. */
    public static int mergeGrid(int[] grid, int w, int h, RectSink sink){
        int quads = 0;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                int c = grid[y * w + x];
                if(c == 0) continue;
                int rw = 1;
                while(x + rw < w && grid[y * w + x + rw] == c) rw++;
                int rh = 1;
                boolean ok = true;
                while(ok && y + rh < h){
                    int row = (y + rh) * w + x;
                    for(int k = 0; k < rw; k++){
                        if(grid[row + k] != c){ ok = false; break; }
                    }
                    if(ok) rh++;
                }
                for(int yy = 0; yy < rh; yy++){
                    int row = (y + yy) * w + x;
                    for(int k = 0; k < rw; k++) grid[row + k] = 0;
                }
                sink.rect(x, y, rw, rh, c);
                quads++;
            }
        }
        return quads;
    }

    private void flushStats(){
        if(statsOn && frames > 0){
            Log.info("[MO] por frame: vista=" + (sIn / frames) + " mantenidos=" + (sKept / frames)
                + " niebla=" + (sFog / frames) + " icono=" + (sIcon / frames)
                + " solidas=" + (sSolidTiles / frames) + "->" + (sSolidQuads / frames) + " quads"
                + " | coste MO " + String.format("%.3f", nanos / frames / 1e6) + " ms"
                + " | mundo (pre->postDraw) " + String.format("%.2f", worldNanos / frames / 1e6) + " ms");
        }
        sIn = sFog = sIcon = sSolidTiles = sSolidQuads = sKept = frames = 0;
        nanos = worldNanos = 0;
    }
}
