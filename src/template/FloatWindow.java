package template;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.GestureDetector;
import arc.input.GestureDetector.GestureListener;
import arc.input.InputProcessor;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.input.InputHandler;

/**
 * Ventana flotante de render.
 *
 * Modelo "portilla": la ventana muestra EXACTAMENTE lo mismo que mostraría la vista a pantalla completa en esos
 * mismos píxeles. Por eso no hay estiramiento (1 píxel de pantalla = 1 píxel renderizado) y el input de construcción
 * sigue funcionando sin conversiones: tocar dentro de la ventana apunta al mismo punto del mundo que ves.
 *
 * Gestos:
 *  - Que EMPIEZAN dentro de la ventana: se entregan al juego (construir, seleccionar, línea, etc.),
 *    pero se bloquea mover la cámara / hacer zoom.
 *  - Que EMPIEZAN fuera de la ventana: se consumen aquí y solo mueven la cámara (1 dedo) o hacen zoom (2 dedos).
 */
public class FloatWindow{
    public static final String K_ON = "mo-win";
    public static final String K_PIN = "mo-win-pin";
    public static final String K_X = "mo-win-x";
    public static final String K_Y = "mo-win-y";
    public static final String K_W = "mo-win-w";
    public static final String K_H = "mo-win-h";
    public static final String K_CHROME = "mo-win-chrome";
    public static final String K_MIN = "mo-win-min";

    // Medidas en dp (se pasan por Scl.scl)
    private static final float MIN_W = 220f, MIN_H = 160f;
    private static final float BAND = 18f;    // grosor de borde que reacciona a gestos de escala
    private static final float CORNER = 30f;  // lado de la zona de esquina
    private static final float TITLE = 40f;   // alto de la barra de título / de los botones
    private static final float CHIP_W = 96f;  // tamaño de la ficha cuando está minimizada
    private static final long REVEAL_MS = 700L;

    // zonas: máscara de bordes (1|2|4|8) o acciones (>= 16)
    private static final int E_L = 1, E_R = 2, E_B = 4, E_T = 8;
    private static final int Z_NONE = 0, Z_MOVE = 16, Z_PIN = 100, Z_MINIMIZE = 101, Z_HIDE = 102, Z_REVEAL = 103, Z_CHIP = 104;

    private static final int MAX_POINTERS = 10;

    /** Posición/tamaño como fracción de la pantalla (origen abajo-izquierda). */
    private float fx = 0.10f, fy = 0.22f, fw = 0.80f, fh = 0.50f;
    private boolean enabled, pinned, dragging, chrome = true, minimized;

    /** Rect en píxeles (y hacia arriba), recalculado por layout(). */
    private int rx, ry, rw, rh;

    private int poll;

    // ---- estado de gestos fuera de la ventana ----
    private final boolean[] outside = new boolean[MAX_POINTERS];
    private final float[] lastX = new float[MAX_POINTERS];
    private final float[] lastY = new float[MAX_POINTERS];
    private boolean pinching;
    private float pinchStartDist, pinchStartScale;

    private final InputProcessor rawProc = new InputProcessor(){
        @Override
        public boolean touchDown(int sx, int sy, int pointer, KeyCode button){
            if(pointer < 0 || pointer >= MAX_POINTERS || !active()) return false;
            layout();
            if(contains(sx, Core.graphics.getHeight() - sy)){
                outside[pointer] = false;
                return false; // dentro: el juego lo maneja (construir)
            }

            outside[pointer] = true;
            lastX[pointer] = sx;
            lastY[pointer] = sy;

            if(pointer == 1 && outside[0]){
                pinching = true;
                pinchStartDist = Math.max(1f, Mathf.dst(lastX[0], lastY[0], lastX[1], lastY[1]));
                pinchStartScale = Vars.renderer.getScale();
            }
            return true; // fuera: solo cámara
        }

        @Override
        public boolean touchDragged(int sx, int sy, int pointer){
            if(pointer < 0 || pointer >= MAX_POINTERS || !outside[pointer]) return false;

            float dx = sx - lastX[pointer];
            float dy = lastY[pointer] - sy; // a Y hacia arriba
            lastX[pointer] = sx;
            lastY[pointer] = sy;

            InputHandler ih = Vars.control.input;
            if(ih == null || ih.locked()) return true;

            if(pinching && outside[0] && outside[1]){
                float d = Math.max(1f, Mathf.dst(lastX[0], lastY[0], lastX[1], lastY[1]));
                Vars.renderer.setScale(pinchStartScale * d / pinchStartDist);
            }else if(!pinching){
                float scale = Core.camera.width / Core.graphics.getWidth();
                Core.camera.position.x -= dx * scale;
                Core.camera.position.y -= dy * scale;
                ih.spectating = null;
                Core.camera.position.clamp(
                    -Core.camera.width / 4f, -Core.camera.height / 4f,
                    Vars.world.unitWidth() + Core.camera.width / 4f, Vars.world.unitHeight() + Core.camera.height / 4f
                );
            }
            return true;
        }

        @Override
        public boolean touchUp(int sx, int sy, int pointer, KeyCode button){
            if(pointer < 0 || pointer >= MAX_POINTERS || !outside[pointer]) return false;
            outside[pointer] = false;
            if(pointer <= 1) pinching = false;
            return true;
        }
    };

    /** Filtra los gestos que el detector entrega al juego: dentro de la ventana no se mueve ni se hace zoom a la cámara. */
    private class Wrapper implements GestureListener{
        final InputHandler target;
        final GestureListener d;

        Wrapper(InputHandler target){
            this.target = target;
            this.d = target;
        }

        @Override public boolean touchDown(float x, float y, int pointer, KeyCode button){ return d.touchDown(x, y, pointer, button); }
        @Override public boolean tap(float x, float y, int count, KeyCode button){ return d.tap(x, y, count, button); }
        @Override public boolean longPress(float x, float y){ return d.longPress(x, y); }
        @Override public boolean fling(float vx, float vy, KeyCode button){ return d.fling(vx, vy, button); }
        @Override public boolean panStop(float x, float y, int pointer, KeyCode button){ return d.panStop(x, y, pointer, button); }

        @Override
        public boolean pan(float x, float y, float deltaX, float deltaY){
            if(!active()) return d.pan(x, y, deltaX, deltaY);

            // Se deja que el juego procese el gesto (mover planos seleccionados, etc.), pero se deshace el movimiento de cámara.
            float cx = Core.camera.position.x, cy = Core.camera.position.y;
            var spectating = target.spectating;
            boolean r = d.pan(x, y, deltaX, deltaY);
            Core.camera.position.set(cx, cy);
            target.spectating = spectating;
            return r;
        }

        @Override
        public boolean zoom(float initialDistance, float distance){
            if(!active()) return d.zoom(initialDistance, distance);
            return false;
        }

        @Override
        public boolean pinch(Vec2 a, Vec2 b, Vec2 c, Vec2 e){
            if(!active()) return d.pinch(a, b, c, e);
            return false;
        }

        @Override
        public void pinchStop(){
            d.pinchStop();
        }
    }

    // ------------------------------------------------------------------ estado

    public boolean active(){
        return enabled && !Vars.headless && Vars.state != null && Vars.state.isGame();
    }

    public int x(){ return rx; }
    public int y(){ return ry; }
    public int w(){ return rw; }
    public int h(){ return rh; }

    /** Recalcula el rect en píxeles a partir de las fracciones y la pantalla actual. */
    public void layout(){
        int sw = Math.max(1, Core.graphics.getWidth());
        int sh = Math.max(1, Core.graphics.getHeight());

        float minW = Math.min(1f, Scl.scl(MIN_W) / sw);
        float minH = Math.min(1f, Scl.scl(MIN_H) / sh);

        fw = Mathf.clamp(fw, minW, 1f);
        fh = Mathf.clamp(fh, minH, 1f);
        fx = Mathf.clamp(fx, 0f, 1f - fw);
        fy = Mathf.clamp(fy, 0f, 1f - fh);

        rw = Math.max(2, Math.min(sw, Math.round(fw * sw)));
        rh = Math.max(2, Math.min(sh, Math.round(fh * sh)));
        rx = Math.max(0, Math.min(sw - rw, Math.round(fx * sw)));
        ry = Math.max(0, Math.min(sh - rh, Math.round(fy * sh)));
    }

    // ------------------------------------------------------------------ recorte de render

    private boolean clipping;
    private long rectFrame = -1;
    private float wx0, wy0, wx1, wy1;

    /** true mientras el mundo se está dibujando recortado a la ventana. */
    public boolean clipping(){ return clipping; }

    /** Llamar en Trigger.preDraw: deja todo negro y limita el dibujado al rect de la ventana. */
    public void beginClip(){
        clipping = false;
        if(!active()) return;
        layout();
        if(!minimized && rw >= Core.graphics.getWidth() && rh >= Core.graphics.getHeight()) return; // pantalla completa: nada que recortar

        Draw.flush();
        Gl.clearColor(0f, 0f, 0f, 1f);
        Gl.clear(Gl.colorBufferBit);
        Gl.enable(Gl.scissorTest);
        if(minimized) Gl.scissor(0, 0, 0, 0); // minimizada: no se pinta nada del mundo
        else Gl.scissor(rx, ry, rw, rh);
        clipping = true;
    }

    /** Llamar en Trigger.postDraw: el HUD (Scene2D) se dibuja después y debe verse completo. */
    public void endClip(){
        if(!clipping) return;
        clipping = false;
        Draw.flush();
        Gl.disable(Gl.scissorTest);
    }

    /** Calcula el rect de la ventana en coordenadas de mundo (llamar desde el hilo principal antes de usar worldVisible en paralelo). */
    public void prepareWorldRect(){
        ensureWorldRect();
    }

    private void ensureWorldRect(){
        long f = Core.graphics.getFrameId();
        if(f == rectFrame) return;
        rectFrame = f;
        float sw = Math.max(1, Core.graphics.getWidth()), sh = Math.max(1, Core.graphics.getHeight());
        float l = Core.camera.position.x - Core.camera.width / 2f;
        float b = Core.camera.position.y - Core.camera.height / 2f;
        wx0 = l + rx / sw * Core.camera.width;
        wx1 = l + (rx + rw) / sw * Core.camera.width;
        wy0 = b + ry / sh * Core.camera.height;
        wy1 = b + (ry + rh) / sh * Core.camera.height;
    }

    /** @return true si el punto (mundo) cae dentro de la ventana, con margen pad. Sin recorte activo siempre es true. */
    public boolean worldVisible(float x, float y, float pad){
        if(!clipping) return true;
        if(minimized) return false;
        ensureWorldRect();
        return x >= wx0 - pad && x <= wx1 + pad && y >= wy0 - pad && y <= wy1 + pad;
    }

    private boolean contains(float sx, float sy){
        if(minimized) return false;
        return sx >= rx && sx < rx + rw && sy >= ry && sy < ry + rh;
    }

    private void readSettings(){
        enabled = Core.settings.getBool(K_ON, false);
        pinned = Core.settings.getBool(K_PIN, false);
        chrome = Core.settings.getBool(K_CHROME, true);
        minimized = Core.settings.getBool(K_MIN, false);
        if(!dragging){
            fx = Core.settings.getInt(K_X, Math.round(fx * 1000f)) / 1000f;
            fy = Core.settings.getInt(K_Y, Math.round(fy * 1000f)) / 1000f;
            fw = Core.settings.getInt(K_W, Math.round(fw * 1000f)) / 1000f;
            fh = Core.settings.getInt(K_H, Math.round(fh * 1000f)) / 1000f;
        }
    }

    private void save(){
        Core.settings.put(K_X, Math.round(fx * 1000f));
        Core.settings.put(K_Y, Math.round(fy * 1000f));
        Core.settings.put(K_W, Math.round(fw * 1000f));
        Core.settings.put(K_H, Math.round(fh * 1000f));
    }

    public void reset(){
        fx = 0.10f;
        fy = 0.22f;
        fw = 0.80f;
        fh = 0.50f;
        pinned = false;
        chrome = true;
        minimized = false;
        Core.settings.put(K_PIN, false);
        Core.settings.put(K_CHROME, true);
        Core.settings.put(K_MIN, false);
        save();
        layout();
    }

    // ------------------------------------------------------------------ instalación

    public void install(){
        if(Vars.headless) return;

        readSettings();
        layout();

        Core.scene.add(new Chrome()); // Scene2D: borde, barra de título y botones

        Events.run(Trigger.update, this::onUpdate);
        ensureInput();
        Log.info("[MO] ventana flotante lista");
    }

    private void onUpdate(){
        if((poll++ & 15) == 0){
            readSettings();
            ensureInput();
        }
        if(!active()){
            java.util.Arrays.fill(outside, false);
            pinching = false;
        }
        layout();
    }

    /**
     * Orden de procesadores requerido: [scene, ..., rawProc, detector(Wrapper), inputHandler].
     * El juego re-registra su detector al cambiar de input, así que se revisa periódicamente.
     */
    private void ensureInput(){
        InputHandler ih = Vars.control == null ? null : Vars.control.input;
        if(ih == null) return;

        Seq<InputProcessor> procs = Core.input.getInputProcessors();

        // detectores propios huérfanos (de un InputHandler anterior)
        procs.remove(p -> p instanceof GestureDetector g && g.getListener() instanceof Wrapper w && w.target != ih);

        GestureDetector cur = ih.detector;
        boolean wrapped = cur != null && cur.getListener() instanceof Wrapper w && w.target == ih;

        if(!wrapped){
            GestureDetector nd = new GestureDetector(20, 0.5f, 0.3f, 0.15f, new Wrapper(ih));
            int at = cur == null ? -1 : procs.indexOf(cur);
            if(at >= 0){
                procs.set(at, nd);
            }else{
                int hi = procs.indexOf(ih);
                if(hi >= 0) procs.insert(hi, nd);
                else procs.add(nd);
            }
            ih.detector = nd;
            cur = nd;
        }

        int di = procs.indexOf(cur);
        int ri = procs.indexOf(rawProc);
        if(di >= 0 && ri != di - 1){
            if(ri >= 0){
                procs.remove(ri);
                di = procs.indexOf(cur);
            }
            procs.insert(di, rawProc);
        }
    }

    // ------------------------------------------------------------------ interfaz

    private float px(float dp){
        return Scl.scl(dp);
    }

    private static void box(float x, float y, float w, float h){
        Fill.rect(x + w / 2f, y + h / 2f, w, h);
    }

    /**
     * Borde, barra de título y botones, en un solo elemento. Solo "existe" para toques en las zonas que usa
     * (borde, barra, ficha o la esquina de revelado); el resto de la ventana pasa los toques al juego.
     */
    private class Chrome extends Element{
        int zone = Z_NONE, capturing = -1;
        float startX, startY, sfx, sfy, sfw, sfh;
        boolean revealing;
        long revealStart;

        Chrome(){
            touchable = Touchable.enabled;

            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(capturing >= 0) return false;
                    int z = zoneAt(x, y);
                    if(z == Z_NONE) return false;

                    zone = z;
                    capturing = pointer;
                    startX = event.stageX;
                    startY = event.stageY;
                    sfx = fx;
                    sfy = fy;
                    sfw = fw;
                    sfh = fh;

                    if(z == Z_REVEAL){
                        revealing = true;
                        revealStart = Time.millis();
                    }
                    dragging = !pinned && (z == Z_MOVE || (z > 0 && z < Z_MOVE));
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer){
                    if(pointer != capturing || !dragging) return;

                    int sw = Math.max(1, Core.graphics.getWidth());
                    int sh = Math.max(1, Core.graphics.getHeight());
                    float dx = (event.stageX - startX) / sw;
                    float dy = (event.stageY - startY) / sh;
                    float minW = Math.min(1f, Scl.scl(MIN_W) / sw);
                    float minH = Math.min(1f, Scl.scl(MIN_H) / sh);

                    if(zone == Z_MOVE){
                        fx = Mathf.clamp(sfx + dx, 0f, 1f - sfw);
                        fy = Mathf.clamp(sfy + dy, 0f, 1f - sfh);
                        return;
                    }

                    // Escala tipo ventana: cada borde/esquina mueve solo su(s) lado(s); el opuesto queda fijo.
                    float l = sfx, r = sfx + sfw, b = sfy, t = sfy + sfh;
                    if((zone & E_L) != 0) l = Mathf.clamp(sfx + dx, 0f, r - minW);
                    if((zone & E_R) != 0) r = Mathf.clamp(r + dx, l + minW, 1f);
                    if((zone & E_B) != 0) b = Mathf.clamp(sfy + dy, 0f, t - minH);
                    if((zone & E_T) != 0) t = Mathf.clamp(t + dy, b + minH, 1f);
                    fx = l;
                    fw = r - l;
                    fy = b;
                    fh = t - b;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(pointer != capturing) return;
                    capturing = -1;
                    revealing = false;

                    if(dragging){
                        dragging = false;
                        save();
                    }else if(zone >= Z_PIN && zone != Z_REVEAL && zoneAt(x, y) == zone){
                        // botón: se activa al soltar dentro del mismo botón
                        switch(zone){
                            case Z_PIN -> {
                                pinned = !pinned;
                                Core.settings.put(K_PIN, pinned);
                            }
                            case Z_MINIMIZE -> {
                                minimized = true;
                                Core.settings.put(K_MIN, true);
                            }
                            case Z_HIDE -> {
                                chrome = false;
                                Core.settings.put(K_CHROME, false);
                            }
                            case Z_CHIP -> {
                                minimized = false;
                                Core.settings.put(K_MIN, false);
                            }
                            default -> {
                            }
                        }
                    }
                    zone = Z_NONE;
                }
            });
        }

        /** Solo captura toques en las zonas útiles; si no, el toque sigue hacia el juego. */
        @Override
        public Element hit(float x, float y, boolean touchable){
            if(!visible) return null;
            if(x < 0 || y < 0 || x >= width || y >= height) return null;
            return zoneAt(x, y) == Z_NONE ? null : this;
        }

        /** x, y en coordenadas locales (origen abajo-izquierda del elemento). */
        int zoneAt(float lx, float ly){
            if(minimized) return Z_CHIP;

            float c = px(CORNER);
            if(!chrome){
                return (lx < c && ly > height - c) ? Z_REVEAL : Z_NONE;
            }

            float bw = px(BAND), th = px(TITLE);

            // esquinas
            boolean nl = lx < c, nr = lx > width - c, nb = ly < c, nt = ly > height - c;
            if((nl || nr) && (nb || nt)){
                return (nl ? E_L : E_R) | (nb ? E_B : E_T);
            }

            // bordes
            int m = 0;
            if(lx < bw) m |= E_L;
            if(lx > width - bw) m |= E_R;
            if(ly < bw) m |= E_B;
            if(ly > height - bw) m |= E_T;
            if(m != 0) return m;

            // barra de título
            if(ly > height - bw - th){
                float sx = width - c - th * 3f;
                if(lx >= sx && lx < sx + th * 3f){
                    return Z_PIN + Math.min(2, (int)((lx - sx) / th));
                }
                return Z_MOVE;
            }
            return Z_NONE;
        }

        @Override
        public void act(float delta){
            super.act(delta);

            visible = active();
            if(!visible) return;

            if(minimized){
                float w = px(CHIP_W), h = px(TITLE);
                setBounds(rx, ry + rh - h, w, h);
            }else{
                setBounds(rx, ry, rw, rh);
            }

            if(revealing && Time.millis() - revealStart >= REVEAL_MS){
                revealing = false;
                chrome = true;
                Core.settings.put(K_CHROME, true);
            }
        }

        @Override
        public void draw(){
            if(!visible) return;

            if(minimized){
                drawChip();
                return;
            }
            if(!chrome) return;

            float bw = px(BAND), th = px(TITLE), c = px(CORNER);
            Color base = pinned ? Color.gray : Color.white;

            // banda de borde (zona de gestos de escala)
            Draw.color(base, 0.16f);
            box(x, y, width, bw);
            box(x, y + height - bw, width, bw);
            box(x, y + bw, bw, height - bw * 2f);
            box(x + width - bw, y + bw, bw, height - bw * 2f);

            // línea exterior
            Lines.stroke(px(2f));
            Draw.color(base, 0.8f);
            Lines.rect(x, y, width, height);

            // marcas de esquina
            float k = px(10f);
            Draw.color(base, 0.9f);
            box(x, y, k, k);
            box(x + width - k, y, k, k);
            box(x, y + height - k, k, k);
            box(x + width - k, y + height - k, k, k);

            // barra de título
            float ty = y + height - bw - th;
            Draw.color(0f, 0f, 0f, 0.6f);
            box(x + bw, ty, width - bw * 2f, th);

            // asa de arrastre (tres rayas)
            float gx = x + (width - c - th * 3f) / 2f + bw / 2f;
            Draw.color(base, 0.8f);
            for(int i = -1; i <= 1; i++){
                box(gx - px(18f), ty + th / 2f + i * px(6f) - px(1.5f), px(36f), px(3f));
            }

            // botones: fijar, minimizar, ocultar controles
            float sx = x + width - c - th * 3f;
            Draw.color(1f, 1f, 1f, 0.95f);
            Drawable lock = pinned ? Icon.lock : Icon.lockOpen;
            float p = px(8f);
            lock.draw(sx + p, ty + p, th - p * 2f, th - p * 2f);

            float cx = sx + th * 1.5f, cy = ty + th / 2f;
            box(cx - px(9f), cy - px(7f), px(18f), px(3f)); // "_"

            cx = sx + th * 2.5f;
            Lines.stroke(px(2.5f));
            Lines.circle(cx, cy, px(9f)); // "prohibido": ocultar
            Lines.line(cx - px(6.4f), cy - px(6.4f), cx + px(6.4f), cy + px(6.4f));

            Draw.reset();
        }

        private void drawChip(){
            Draw.color(0f, 0f, 0f, 0.65f);
            box(x, y, width, height);
            Lines.stroke(px(2f));
            Draw.color(1f, 1f, 1f, 0.85f);
            Lines.rect(x, y, width, height);

            // símbolo de "restaurar": cuadrado con barra superior
            float cx = x + width / 2f, cy = y + height / 2f;
            Lines.stroke(px(2.5f));
            Lines.rect(cx - px(9f), cy - px(9f), px(18f), px(18f));
            box(cx - px(9f), cy + px(5f), px(18f), px(4f));
            Draw.reset();
        }
    }
}
