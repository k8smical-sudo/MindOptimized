package template;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
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
    public static final String K_ON = "mo-scissor";
    public static final String K_PIN = "mo-win-pin";
    public static final String K_X = "mo-win-x";
    public static final String K_Y = "mo-win-y";
    public static final String K_W = "mo-win-w";
    public static final String K_H = "mo-win-h";

    private static final int MAX_POINTERS = 10;

    /** Posición/tamaño como fracción de la pantalla (origen abajo-izquierda). */
    private float fx = 0.10f, fy = 0.22f, fw = 0.80f, fh = 0.50f;
    private boolean enabled, pinned, dragging;

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
            if(contains(sx, sy)){
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
            float dy = sy - lastY[pointer];
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

        float minW = Math.min(1f, Scl.scl(140f) / sw);
        float minH = Math.min(1f, Scl.scl(140f) / sh);

        fw = Mathf.clamp(fw, minW, 1f);
        fh = Mathf.clamp(fh, minH, 1f);
        fx = Mathf.clamp(fx, 0f, 1f - fw);
        fy = Mathf.clamp(fy, 0f, 1f - fh);

        rw = Math.max(2, Math.min(sw, Math.round(fw * sw)));
        rh = Math.max(2, Math.min(sh, Math.round(fh * sh)));
        rx = Math.max(0, Math.min(sw - rw, Math.round(fx * sw)));
        ry = Math.max(0, Math.min(sh - rh, Math.round(fy * sh)));
    }

    private boolean contains(float sx, float sy){
        return sx >= rx && sx < rx + rw && sy >= ry && sy < ry + rh;
    }

    private void readSettings(){
        enabled = Core.settings.getBool(K_ON, false);
        pinned = Core.settings.getBool(K_PIN, false);
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
        save();
        layout();
    }

    // ------------------------------------------------------------------ instalación

    public void install(){
        if(Vars.headless) return;

        readSettings();
        layout();

        Core.scene.add(new Frame());
        Core.scene.add(new Handle(0));
        Core.scene.add(new Handle(1));
        Core.scene.add(new Handle(2));

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

    /** Borde de la ventana (no recibe toques). */
    private class Frame extends Element{
        Frame(){
            touchable = Touchable.disabled;
        }

        @Override
        public void act(float delta){
            super.act(delta);
            visible = active();
        }

        @Override
        public void draw(){
            if(!active()) return;
            Lines.stroke(Scl.scl(2f));
            Draw.color(pinned ? Color.gray : Color.white, 0.75f);
            Lines.rect(rx, ry, rw, rh);
            Draw.reset();
        }
    }

    /** kind: 0 = fijar, 1 = mover, 2 = redimensionar (esquina inferior derecha). */
    private class Handle extends Element{
        final int kind;
        float startX, startY, sfx, sfy, sfw, sfh;

        Handle(int kind){
            this.kind = kind;
            touchable = Touchable.enabled;

            if(kind == 0){
                clicked(() -> {
                    pinned = !pinned;
                    Core.settings.put(K_PIN, pinned);
                });
            }else{
                addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        if(pinned) return false;
                        startX = event.stageX;
                        startY = event.stageY;
                        sfx = fx;
                        sfy = fy;
                        sfw = fw;
                        sfh = fh;
                        dragging = true;
                        return true;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer){
                        if(!dragging) return;
                        int sw = Math.max(1, Core.graphics.getWidth());
                        int sh = Math.max(1, Core.graphics.getHeight());
                        float dx = (event.stageX - startX) / sw;
                        float dy = (event.stageY - startY) / sh;

                        if(Handle.this.kind == 1){
                            fx = Mathf.clamp(sfx + dx, 0f, 1f - sfw);
                            fy = Mathf.clamp(sfy + dy, 0f, 1f - sfh);
                        }else{
                            float minW = Math.min(1f, Scl.scl(140f) / sw);
                            float minH = Math.min(1f, Scl.scl(140f) / sh);
                            // esquina inferior derecha: izquierda y borde superior fijos
                            float right = Mathf.clamp(sfx + sfw + dx, sfx + minW, 1f);
                            float bottom = Mathf.clamp(sfy + dy, 0f, sfy + sfh - minH);
                            fx = sfx;
                            fw = right - sfx;
                            fy = bottom;
                            fh = sfy + sfh - bottom;
                        }
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                        if(!dragging) return;
                        dragging = false;
                        save();
                    }
                });
            }
        }

        @Override
        public void act(float delta){
            super.act(delta);

            boolean show = active() && (kind == 0 || !pinned);
            visible = show;
            if(!show) return;

            float s = Scl.scl(38f);
            float pad = Scl.scl(6f);
            switch(kind){
                case 0 -> setBounds(rx + pad, ry + rh - s - pad, s, s);
                case 1 -> setBounds(rx + pad * 2f + s, ry + rh - s - pad, s, s);
                default -> setBounds(rx + rw - s - pad, ry + pad, s, s);
            }
        }

        @Override
        public void draw(){
            if(!visible) return;

            Draw.color(0f, 0f, 0f, 0.6f);
            Fill.rect(x + width / 2f, y + height / 2f, width, height);

            Drawable icon = kind == 0 ? (pinned ? Icon.lock : Icon.lockOpen) : kind == 1 ? Icon.move : Icon.resize;
            float p = Scl.scl(6f);
            Draw.color(1f, 1f, 1f, 0.95f);
            icon.draw(x + p, y + p, width - p * 2f, height - p * 2f);
            Draw.reset();
        }
    }
}
