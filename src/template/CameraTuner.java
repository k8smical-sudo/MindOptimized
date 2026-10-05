package template;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Cámara lineal: sin suavizado de zoom, sin seguimiento suave y sin inercia al arrastrar.
 *
 *  - Zoom: el juego acerca/aleja con un lerp (camerascale -> destino, 10% por frame). Aquí camerascale salta
 *    directamente al destino, ANTES de que el renderer lo use en ese mismo frame (Trigger.update corre dentro de Logic,
 *    que va antes del renderer). El valor final se fija en preDraw (ver applyZoom): no pelea con el lerp del juego.
 *  - Seguimiento: se apaga el ajuste "smoothcamera" del juego mientras el módulo esté activo (y se restaura al apagarlo).
 *  - Inercia (gesto de lanzar en móvil): se pone a cero camVel cuando no hay dedos tocando la pantalla.
 *  - Alineado a píxeles (opcional, apagado por defecto, solo con escala entera): solo durante el dibujo, la posición de la cámara se redondea a la cuadrícula de
 *    píxeles de la pantalla y se restaura después; la lógica nunca ve la posición redondeada.
 *
 * Expectativas realistas: el suavizado cuesta una interpolación por frame, es decir, casi nada; el beneficio está en que
 * la cámara se detiene al instante (el juego deja de recalcular lo visible durante la "cola" del suavizado), en la
 * respuesta inmediata y en que no hay temblor de texturas. No esperes un cambio grande de CPU solo por esto.
 *
 * Todo va por reflexión y con try/catch: si algún campo no existe en esta versión, esa parte se desactiva y se registra
 * en el log con el prefijo [MO].
 */
public class CameraTuner{
    public static final String K_LINEAR = "mo-cam-linear";
    public static final String K_SNAP = "mo-cam-snap";

    private boolean linear = true, snap = false;
    private int poll;

    private Field fScale, fTarget;
    private Method mMin, mMax;
    private float minScale = 0f, maxScale = Float.MAX_VALUE;
    private boolean zoomBound, zoomFailed;

    private Field fCamVel;
    private Class<?> camVelOwner;
    private boolean velFailed;

    private boolean savedSmooth, prevSmooth;

    private boolean snapped;
    private float savedX, savedY;

    public void install(){
        Events.run(Trigger.update, this::onUpdate);
        Events.run(Trigger.preDraw, this::onPreDraw);
        Events.run(Trigger.postDraw, this::onPostDraw);
        Log.info("[MO] CameraTuner listo");
    }

    // ------------------------------------------------------------------ frame lógico (antes del renderer)

    private void onUpdate(){
        if(Vars.headless) return;

        if((poll++ & 15) == 0){
            boolean wasLinear = linear;
            linear = Core.settings.getBool(K_LINEAR, true);
            snap = Core.settings.getBool(K_SNAP, false);
            if(wasLinear && !linear) restoreSmooth();
            if(linear) refreshLimits();
        }
        if(!linear) return;

        try{
            forceNoSmooth();
            killInertia();
        }catch(Throwable t){
            Refl.once("CameraTuner (cámara)", t);
        }
        try{
            applyZoom(false);
        }catch(Throwable t){
            pauseZoom("update", t);
        }
    }

    private void forceNoSmooth(){
        if(!savedSmooth){
            prevSmooth = Core.settings.getBool("smoothcamera", true);
            savedSmooth = true;
        }
        if(Core.settings.getBool("smoothcamera", true)){
            Core.settings.put("smoothcamera", false);
        }
    }

    private void restoreSmooth(){
        if(savedSmooth){
            Core.settings.put("smoothcamera", prevSmooth);
            savedSmooth = false;
        }
    }

    // ------------------------------------------------------------------ zoom

    private void bindZoom(){
        if(zoomBound || zoomFailed) return;
        try{
            Class<?> rc = Vars.renderer.getClass();
            for(Class<?> c = rc; c != null && c != Object.class; c = c.getSuperclass()){
                for(Field f : c.getDeclaredFields()){
                    if(f.getType() != float.class) continue;
                    if(f.getName().equals("camerascale")) fScale = f;
                    else if(f.getName().equals("targetscale")) fTarget = f;
                }
            }
            if(fScale == null || fTarget == null){
                zoomFailed = true;
                Log.err("[MO] CameraTuner: no se encontraron camerascale/targetscale; el zoom sigue suave. Campos float de Renderer:");
                for(Field f : rc.getDeclaredFields()){
                    if(f.getType() == float.class) Log.info("  float " + f.getName());
                }
                return;
            }
            fScale.setAccessible(true);
            fTarget.setAccessible(true);

            try{
                mMin = rc.getMethod("minScale");
                mMax = rc.getMethod("maxScale");
            }catch(Throwable ignored){
                mMin = mMax = null; // sin límites propios: se usa el objetivo tal cual
            }
            zoomBound = true;
            Log.info("[MO] CameraTuner: zoom lineal enlazado");
        }catch(Throwable t){
            zoomFailed = true;
            Log.err("[MO] CameraTuner: bindZoom falló", t);
        }
    }

    private void refreshLimits(){
        bindZoom();
        if(!zoomBound || mMin == null || mMax == null) return;
        try{
            minScale = ((Number)mMin.invoke(Vars.renderer)).floatValue();
            maxScale = ((Number)mMax.invoke(Vars.renderer)).floatValue();
        }catch(Throwable t){
            mMin = mMax = null;
            minScale = 0f;
            maxScale = Float.MAX_VALUE;
        }
    }

    /**
     * Zoom lineal, sin pelear con el juego.
     *
     * Diseño anterior (eliminado): escribir camerascale en Trigger.update y vigilar si el juego lo movía; al creer que había
     * "conflicto" se cambiaba de modo hasta quedar desactivado PARA SIEMPRE. El gesto de zoom con dos dedos se procesa
     * después de Trigger.update y antes del renderer, así que ese "conflicto" era falso y el zoom volvía a vanilla.
     *
     * Diseño actual: el valor FINAL se fija en Trigger.preDraw, el último punto antes de dibujar, con el objetivo de ese
     * mismo frame. Lo que se ve es siempre el objetivo actual, decida lo que decida el lerp del juego. Además, en
     * Trigger.update se adelanta la misma escritura para que el lerp del juego casi no tenga nada que mover (y la entrada
     * que usa camera.width vea valores coherentes). Nunca se desactiva por "conflicto": si algo lanza una excepción, se
     * pausa 5 s y se reintenta.
     */
    private int zoomPause;
    private boolean sizeChecked, sizeFormulaOk;

    private void applyZoom(boolean finalPass) throws IllegalAccessException{
        if(zoomPause > 0){
            zoomPause--;
            return;
        }
        bindZoom();
        if(!zoomBound) return;

        float target = fTarget.getFloat(Vars.renderer);
        float dest = target;
        if(mMin != null && mMax != null) dest = Mathf.clamp(dest, minScale, maxScale);
        if(dest <= 0f || Float.isNaN(dest)) return;

        float cur = fScale.getFloat(Vars.renderer);
        if(Math.abs(cur - dest) > 0.0001f){
            fScale.setFloat(Vars.renderer, dest);
        }

        if(!finalPass) return;

        // El renderer ya calculó camera.width/height con la escala suavizada; se rehace con la final.
        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        if(!sizeChecked){
            sizeChecked = true;
            sizeFormulaOk = Math.abs(Core.camera.width * cur - w) < 2f; // ¿camera.width == ancho / escala en esta versión?
            if(!sizeFormulaOk) Log.info("[MO] CameraTuner: camera.width no es ancho/escala en esta versión; el zoom se aplica un frame tarde");
        }
        if(sizeFormulaOk && Math.abs(cur - dest) > 0.0001f){
            Core.camera.width = w / dest;
            Core.camera.height = h / dest;
        }
    }

    private void pauseZoom(String where, Throwable t){
        zoomPause = 300;
        Refl.once("CameraTuner (" + where + ")", t);
    }

    // ------------------------------------------------------------------ inercia al arrastrar (móvil)

    private void killInertia() throws IllegalAccessException{
        if(velFailed || Vars.control == null || Vars.control.input == null) return;
        if(Core.input.isTouched()) return; // mientras arrastras no se toca nada

        Object in = Vars.control.input;
        if(fCamVel == null || camVelOwner != in.getClass()){
            fCamVel = null;
            camVelOwner = in.getClass();
            for(Class<?> c = camVelOwner; c != null && c != Object.class && fCamVel == null; c = c.getSuperclass()){
                for(Field f : c.getDeclaredFields()){
                    if(f.getName().equals("camVel") && Vec2.class.isAssignableFrom(f.getType())){
                        f.setAccessible(true);
                        fCamVel = f;
                        break;
                    }
                }
            }
            if(fCamVel == null){
                // En escritorio no hay inercia de arrastre; no es un error. Se reintenta si cambia el tipo de input.
                return;
            }
        }

        Object v = fCamVel.get(in);
        if(v instanceof Vec2 vec) vec.setZero();
    }

    // ------------------------------------------------------------------ alineado a píxeles (solo al dibujar)

    private void onPreDraw(){
        snapped = false;
        if(linear && !Vars.headless && Vars.state.isGame()){
            try{
                applyZoom(true);
            }catch(Throwable t){
                pauseZoom("preDraw", t);
            }
        }
        if(!linear || !snap || Vars.headless || !Vars.state.isGame()) return;
        if(Core.settings.getInt("flat-div", 1) > 1) return; // FlatRender ya alinea la cámara a su rejilla: no pelear con él

        float w = Core.camera.width;
        if(w <= 0f) return;
        float ppt = Core.graphics.getWidth() / w; // píxeles de pantalla por unidad de mundo
        // Con escala fraccionaria no existe una cuadrícula de píxeles a la que "encajar": redondear solo provoca temblor.
        if(Math.abs(ppt - Math.round(ppt)) > 0.01f) return;

        savedX = Core.camera.position.x;
        savedY = Core.camera.position.y;
        Core.camera.position.x = Math.round(savedX * ppt) / ppt;
        Core.camera.position.y = Math.round(savedY * ppt) / ppt;
        snapped = true;
    }

    private void onPostDraw(){
        if(!snapped) return;
        snapped = false;
        Core.camera.position.x = savedX;
        Core.camera.position.y = savedY;
    }
}
