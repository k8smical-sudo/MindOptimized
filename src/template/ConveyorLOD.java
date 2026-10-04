package template;

import arc.Core;
import arc.Events;
import arc.func.Prov;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Conveyor;

/**
 * LOD de simulación para cintas transportadoras (mapas enormes llenos de cintas con ítems en movimiento).
 *
 * Qué hace: las cintas "lejos" de lo que mira el jugador ejecutan su update() solo 1 de cada k ticks, y cuando lo
 * ejecutan se mueven k veces más (edelta() * k). Los ítems avanzan a la misma velocidad media; lo único que cambia es
 * que lejos se mueven a saltos, y que el reparto entre cintas se resuelve cada k ticks.
 *
 * Por qué es seguro para el flujo: una cinta solo puede pasar UN ítem por update. Se limita k a
 * floor(0.4 / velocidad) - 1 (itemSpace = 0.4): con k igual a eso, aún puede pasar más ítems por segundo de los que su
 * velocidad nominal permite, así que el rendimiento de la cinta no baja.
 *
 * Controlador adaptativo: el nivel de simplificación sube solo cuando la lógica (medida con LogicTimer) supera el
 * presupuesto, y baja cuando hay holgura. Con la lógica ligera no cambia nada: nivel 0 = comportamiento vanilla.
 *
 * Alcance: solo cintas de la clase exacta Conveyor (básica, titanio...). No toca blindadas, apiladoras, puentes ni
 * juntas. Solo partidas locales (con red activa, todo vuelve a vanilla).
 *
 * Límite honesto: el juego sigue recorriendo TODAS las entidades cada frame y llamando a update(); una cinta saltada
 * cuesta una llamada y un par de comparaciones (decenas de ns), no cero. Con cientos de miles de cintas ese suelo
 * sigue contando.
 */
public final class ConveyorLOD{
    public static final String K_MAX = "mo-conv-max";       // periodo máximo lejano (1 = apagado)
    public static final String K_BUDGET = "mo-conv-budget"; // presupuesto de lógica en ms (0 = nivel fijo al máximo)
    public static final String K_NEAR = "mo-conv-near";     // radio cercano, en décimas de "pantalla"
    public static final String K_ITEMS = "mo-conv-items";   // ocultar ítems si px por casilla < valor (0 = nunca)

    private static final int LEVELS = 8;

    // Estado global (solo hilo principal). Lo leen las cintas en cada update().
    static boolean active;
    static int frame;
    static float cx, cy, near2, mid2;
    static int pMid = 1, pFar = 1;
    static boolean hideItems;
    static float pxTile = 99f, itemsMinPx;

    private static int maxFar = 4, level;
    private static float budgetMs = 10f, nearK = 1f;
    private static int registered;
    private static volatile String status = "";

    private ConveyorLOD(){
    }

    public static String status(){
        return status;
    }

    // ------------------------------------------------------------------ instalación

    public static void install(){
        LogicTimer.install();

        int n = 0;
        for(Block b : Vars.content.blocks()){
            if(b.getClass() != Conveyor.class) continue; // solo la clase exacta: blindadas y demás conservan su lógica
            final Conveyor c = (Conveyor)b;
            Prov<Building> prov = () -> new Build(c);
            if(Refl.set(c, "buildType", prov)) n++;
        }
        registered = n;
        Log.info("[MO] ConveyorLOD: " + n + " tipos de cinta con simulación por niveles");
        if(n == 0) Log.err("[MO] ConveyorLOD: no se pudo asignar buildType; función inactiva");

        Events.run(Trigger.update, ConveyorLOD::tick);
    }

    // ------------------------------------------------------------------ control (una vez por frame)

    private static void tick(){
        frame++;
        if((frame & 15) == 0){
            maxFar = Math.max(1, Math.min(16, Core.settings.getInt(K_MAX, 4)));
            budgetMs = Core.settings.getInt(K_BUDGET, 10);
            nearK = Core.settings.getInt(K_NEAR, 10) / 10f;
            int px = Core.settings.getInt(K_ITEMS, 0);
            hideItems = px > 0;
            itemsMinPx = px;
        }

        if(Vars.state.isGame()) pxTile = Core.graphics.getWidth() / Math.max(1f, Core.camera.width) * 8f;

        active = registered > 0 && maxFar > 1 && Vars.state.isGame() && !Vars.net.active();
        if(!active){
            pMid = pFar = 1;
            level = 0;
            return;
        }

        // Centro de interés: la cámara (cuando sigue a tu unidad es lo mismo, y si exploras lejos, lo que miras).
        cx = Core.camera.position.x;
        cy = Core.camera.position.y;
        float hw = Core.camera.width * 0.5f, hh = Core.camera.height * 0.5f;
        float near = (float)Math.sqrt(hw * hw + hh * hh) * Math.max(0.5f, nearK);
        near2 = near * near;
        float mid = near * 2.5f;
        mid2 = mid * mid;

        // Controlador adaptativo: cada 30 frames, un paso.
        if((frame % 30) == 0){
            if(budgetMs <= 0f || LogicTimer.inst == null){
                level = LEVELS; // sin medidor o sin presupuesto: simplificación fija al máximo configurado
            }else{
                float ms = LogicTimer.ms();
                if(ms > budgetMs * 1.15f && level < LEVELS) level++;
                else if(ms < budgetMs * 0.7f && level > 0) level--;
            }
            pFar = 1 + Math.round(level * (maxFar - 1) / (float)LEVELS);
            pMid = 1 + (pFar - 1) / 2;
            status = "[accent]Cintas[] nivel " + level + "/" + LEVELS + "  lejos 1/" + pFar + "  lógica " + String.format("%.1f", LogicTimer.ms()) + " ms";
        }

        if((frame % 600) == 0){
            Log.info("[MO] cintas: nivel=" + level + "/" + LEVELS + " cerca=1 medio=1/" + pMid + " lejos=1/" + pFar
                + " lógica=" + String.format("%.2f", LogicTimer.ms()) + "ms entidades=" + mindustry.gen.Groups.build.size());
        }
    }

    /** Periodo (1 = cada tick) para una cinta en (x, y). */
    static int periodFor(float x, float y){
        float dx = x - cx, dy = y - cy;
        float d2 = dx * dx + dy * dy;
        return d2 <= near2 ? 1 : d2 <= mid2 ? pMid : pFar;
    }

    // ------------------------------------------------------------------ el edificio

    /**
     * Edificio de cinta con simulación por niveles. Es una subclase del ConveyorBuild vanilla (clase interna de
     * Conveyor), por eso el constructor usa la sintaxis "bloque.super()".
     */
    public static class Build extends Conveyor.ConveyorBuild{
        private int period = 1;
        private float lastTime = -1f, stepScale = 1f;
        private final int cap;

        public Build(Conveyor block){
            block.super();
            // Tope de k que conserva el rendimiento: floor(itemSpace / velocidad) - 1, con itemSpace = 0.4
            cap = Math.max(1, (int)(0.4f / Math.max(0.001f, block.speed)) - 1);
        }

        @Override
        public void update(){
            if(!active){
                lastTime = -1f;
                super.update();
                return;
            }

            int f = frame + id;
            if((f & 31) == 0) period = Math.min(periodFor(x, y), cap); // se reevalúa escalonado: 1 de cada 32 frames
            if(period > 1 && (f % period) != 0) return;                 // tick saltado: coste mínimo

            float now = Time.time;
            float d = Time.delta;
            if(period > 1 && lastTime >= 0f && d > 0.0001f){
                stepScale = Math.max(1f, Math.min(cap * 2f, (now - lastTime) / d)); // tiempo real transcurrido desde el último update
            }else{
                stepScale = 1f;
            }
            lastTime = now;
            try{
                super.update();
            }finally{
                stepScale = 1f;
            }
        }

        /** El movimiento de los ítems usa edelta(): se escala con el tiempo acumulado de los ticks saltados. */
        @Override
        public float edelta(){
            return super.edelta() * stepScale;
        }

        @Override
        public void draw(){
            // A cierta distancia de cámara los ítems miden pocos píxeles: no se dibujan (la base de la cinta sí).
            if(hideItems && pxTile < itemsMinPx && len > 0){
                int saved = len;
                len = 0;
                try{
                    super.draw();
                }finally{
                    len = saved;
                }
            }else{
                super.draw();
            }
        }
    }
}
