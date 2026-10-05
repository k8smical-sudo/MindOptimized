package template;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.util.Log;
import mindustry.Vars;

/**
 * Resolución personalizada: una zona de juego centrada, de ancho y alto configurables (tope = resolución base), con el
 * resto de la pantalla en negro. NO estira nada.
 *
 * Cómo funciona (y por qué no descuadra el táctil): la cámara, el zoom y todas las coordenadas del juego quedan
 * EXACTAMENTE igual que a pantalla completa. Solo se recorta lo que se ve a un rectángulo centrado (scissor de GL) y se
 * pinta el resto de negro. Un toque dentro del rectángulo cae en el mismo punto del mundo que sin recorte, así que
 * movimiento, zoom, construcción y demás gestos funcionan sin remapear nada. El zoom con dos dedos escala desde el centro
 * de la pantalla, que es el centro del rectángulo.
 *
 * Los menús (Scene2D) se dibujan después y a pantalla completa: no se ven afectados. Un elemento invisible, colocado POR
 * DEBAJO de toda la interfaz, absorbe los toques en las barras negras, para que no se pueda construir ni seleccionar en
 * una zona que no se ve (los botones del HUD que caigan sobre las barras siguen funcionando).
 *
 * Los optimizadores del mod (descarte de bloques, sleep, cintas) usan este rectángulo en vez de la pantalla entera.
 *
 * Limitación: el recorte deja de aplicarse en lo que el juego dibuje después de Trigger.postDraw (p. ej. el pixelado).
 */
public final class WorldWindow{
    public static final String K_ON = "mo-res-on", K_W = "mo-res-w", K_H = "mo-res-h";

    /** Hay zona personalizada activa (activada, en partida y menor que la pantalla). */
    public static boolean active;
    /** rw / W y rh / H: fracción de la pantalla que ocupa la zona (1 si no hay zona). */
    public static float fracX = 1f, fracY = 1f;
    /** Rectángulo en píxeles, origen abajo-izquierda (igual que GL y Scene2D). */
    public static int rx, ry, rw, rh;
    /** Rectángulo visible en coordenadas de mundo; se refresca con prepare(). */
    public static float wx0, wy0, wx1, wy1;

    private static boolean on, scissorOn;
    private static int wPct = 100, hPct = 100, tick;

    private WorldWindow(){
    }

    public static void install(){
        if(Vars.headless || Core.scene == null) return;
        Core.scene.root.addChildAt(0, new Blocker()); // índice 0 = por debajo de toda la interfaz
        Log.info("[MO] WorldWindow listo");
    }

    // ------------------------------------------------------------------ cálculo del rectángulo

    private static void refresh(){
        if((tick++ & 7) == 0){
            on = Core.settings.getBool(K_ON, false);
            wPct = Math.max(10, Math.min(100, Core.settings.getInt(K_W, 100)));
            hPct = Math.max(10, Math.min(100, Core.settings.getInt(K_H, 100)));
        }
        int W = Core.graphics.getWidth(), H = Core.graphics.getHeight();
        rw = Math.max(Math.min(64, W), Math.min(W, Math.round(W * wPct / 100f)));
        rh = Math.max(Math.min(64, H), Math.min(H, Math.round(H * hPct / 100f)));
        rx = (W - rw) / 2;
        ry = (H - rh) / 2;
        active = on && Vars.state.isGame() && (rw < W || rh < H);
        fracX = active ? rw / (float)W : 1f;
        fracY = active ? rh / (float)H : 1f;
    }

    /** Rectángulo visible en mundo. Llamar desde el hilo principal antes de usar visible() (incluso en paralelo). */
    public static void prepare(){
        float hx = Core.camera.width * 0.5f * fracX, hy = Core.camera.height * 0.5f * fracY;
        wx0 = Core.camera.position.x - hx;
        wx1 = Core.camera.position.x + hx;
        wy0 = Core.camera.position.y - hy;
        wy1 = Core.camera.position.y + hy;
    }

    /** ¿Cae el punto (mundo), con margen pad, dentro de la zona visible? Solo lee: seguro entre hilos tras prepare(). */
    public static boolean visible(float x, float y, float pad){
        return x >= wx0 - pad && x <= wx1 + pad && y >= wy0 - pad && y <= wy1 + pad;
    }

    // ------------------------------------------------------------------ recorte (Trigger.preDraw / postDraw)

    /**
     * Llamar en preDraw. div > 1 si el mundo se está dibujando en el framebuffer reducido de FlatRender (el recorte se
     * expresa en píxeles de ese framebuffer).
     */
    public static void begin(int div){
        end();
        refresh();
        if(!active) return;

        Draw.flush();
        if(div <= 1){
            // Todo negro primero; el juego limpia después solo el rectángulo (más barato).
            Gl.clearColor(0f, 0f, 0f, 1f);
            Gl.clear(Gl.colorBufferBit);
            Gl.enable(Gl.scissorTest);
            Gl.scissor(rx, ry, rw, rh);
        }else{
            int x0 = rx / div, y0 = ry / div;
            int w = (rx + rw + div - 1) / div - x0, h = (ry + rh + div - 1) / div - y0;
            Gl.enable(Gl.scissorTest);
            Gl.scissor(x0, y0, w, h);
        }
        scissorOn = true;
    }

    /** Antes de volcar el framebuffer reducido a la pantalla: pantalla negra y recorte en píxeles reales. */
    public static void beginBlit(){
        if(!active) return;
        Draw.flush();
        Gl.clearColor(0f, 0f, 0f, 1f);
        Gl.clear(Gl.colorBufferBit);
        Gl.enable(Gl.scissorTest);
        Gl.scissor(rx, ry, rw, rh);
        scissorOn = true;
    }

    /** Llamar en postDraw: la interfaz (Scene2D) se dibuja después y debe verse completa. */
    public static void end(){
        if(!scissorOn) return;
        scissorOn = false;
        Draw.flush();
        Gl.disable(Gl.scissorTest);
    }

    // ------------------------------------------------------------------ proporciones

    /** Zona de proporción a:b lo más grande posible dentro de la pantalla, centrada. */
    public static void preset(int a, int b){
        int W = Math.max(1, Core.graphics.getWidth()), H = Math.max(1, Core.graphics.getHeight());
        float ar = a / (float)b;
        float w = W, h = w / ar;
        if(h > H){
            h = H;
            w = h * ar;
        }
        Core.settings.put(K_W, Math.max(10, Math.min(100, Math.round(w / W * 100f))));
        Core.settings.put(K_H, Math.max(10, Math.min(100, Math.round(h / H * 100f))));
        Core.settings.put(K_ON, true);
        if(Vars.ui != null) Vars.ui.showInfoToast("Zona " + a + ":" + b + " aplicada. Reabre Ajustes para ver los valores.", 4f);
    }

    public static void full(){
        Core.settings.put(K_W, 100);
        Core.settings.put(K_H, 100);
        Core.settings.put(K_ON, false);
        if(Vars.ui != null) Vars.ui.showInfoToast("Pantalla completa", 3f);
    }

    // ------------------------------------------------------------------ barras negras

    /**
     * Cubre la pantalla entera pero SOLO existe fuera del rectángulo. Va por debajo del resto de la interfaz, así que
     * los botones del HUD siguen recibiendo sus toques aunque caigan sobre una barra.
     */
    private static final class Blocker extends Element{
        Blocker(){
            touchable = Touchable.enabled;
            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    return true; // lo absorbe la escena: el juego no recibe ese toque
                }
            });
        }

        @Override
        public void act(float delta){
            super.act(delta);
            visible = active;
            setBounds(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());
        }

        @Override
        public Element hit(float x, float y, boolean touchable){
            if(!active || !visible) return null;
            boolean inside = x >= rx && x < rx + rw && y >= ry && y < ry + rh;
            return inside ? null : this;
        }
    }
}
