package template;

import arc.Core;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Scl;
import arc.util.Log;
import mindustry.Vars;
import mindustry.ui.dialogs.BaseDialog;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Perfilado de hardware en Java puro (sin JNI): todo por reflexión sobre la API de Android y por las cadenas de GL.
 *
 * Qué detecta: versión de Android, versión máxima de Vulkan que DECLARA el dispositivo (característica del sistema
 * android.hardware.vulkan.version), versión de GLES, GPU (fabricante, modelo y familia), RAM, heap, CPU (vía Cores),
 * capacidades de audio (baja latencia, frecuencia y buffer nativos) y el motor de audio que usa el juego.
 *
 * Qué NO hace: cambiar el backend gráfico. Eso lo decide el APK, no un mod (ver BackendSelector).
 *
 * Con esos datos calcula un "nivel de equipo" (0 bajo, 1 medio, 2 alto) y recomienda uno de los perfiles de Profiles.
 * Debe llamarse una vez, desde el hilo de GL (las cadenas de GL solo existen con el contexto activo).
 */
public final class HardwareProfile{
    public static final int VK_1_1 = 0x401000;

    public static int apiLevel, vulkanVersion, vulkanLevel = -1, glesVersion, outRate, outFrames, ramMB, heapMB, gpuModel, audioNatives, tier = 1;
    public static boolean lowLatency, proAudio, detected;
    public static String glVendor = "?", glRenderer = "?", glVersion = "?", family = "desconocida", audioEngine = "?";

    private HardwareProfile(){
    }

    private static Object sys(String name) throws Exception{
        return Class.forName("android.content.Context").getMethod("getSystemService", String.class).invoke(Core.app, name);
    }

    private static int toInt(Object o, int def){
        return o instanceof Number n ? n.intValue() : def;
    }

    public static void detect(){
        if(detected) return;
        detected = true;

        try{
            heapMB = (int)(Runtime.getRuntime().maxMemory() / (1024L * 1024L));
        }catch(Throwable ignored){
        }

        if(Core.app.isAndroid()){
            try{
                detectAndroid();
            }catch(Throwable t){
                Refl.once("HardwareProfile (Android)", t);
            }
        }
        try{
            detectGl();
        }catch(Throwable t){
            Refl.once("HardwareProfile (GL)", t);
        }
        try{
            detectAudioEngine();
        }catch(Throwable t){
            Refl.once("HardwareProfile (audio)", t);
        }
        classify();
        Log.info("[MO] hardware:\n" + summary());
    }

    // ------------------------------------------------------------------ Android

    private static void detectAndroid() throws Exception{
        apiLevel = Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);

        Object pm = Class.forName("android.content.Context").getMethod("getPackageManager").invoke(Core.app);
        Class<?> pmC = Class.forName("android.content.pm.PackageManager");

        // Versión de Vulkan que declara el dispositivo: (mayor << 22) | (menor << 12) | parche.
        Object feats = pmC.getMethod("getSystemAvailableFeatures").invoke(pm);
        if(feats != null){
            for(int i = 0; i < Array.getLength(feats); i++){
                Object fi = Array.get(feats, i);
                Object name = Refl.get(fi, "name");
                if("android.hardware.vulkan.version".equals(name)) vulkanVersion = Math.max(vulkanVersion, toInt(Refl.get(fi, "version"), 0));
                else if("android.hardware.vulkan.level".equals(name)) vulkanLevel = toInt(Refl.get(fi, "version"), -1);
            }
        }

        Method has = pmC.getMethod("hasSystemFeature", String.class);
        lowLatency = Boolean.TRUE.equals(has.invoke(pm, "android.hardware.audio.low_latency"));
        proAudio = Boolean.TRUE.equals(has.invoke(pm, "android.hardware.audio.pro"));

        // RAM total y versión de GLES solicitada por el sistema.
        Class<?> amC = Class.forName("android.app.ActivityManager");
        Object am = sys("activity");
        if(am != null){
            Class<?> miC = Class.forName("android.app.ActivityManager$MemoryInfo");
            Object mi = miC.getConstructor().newInstance();
            amC.getMethod("getMemoryInfo", miC).invoke(am, mi);
            ramMB = (int)(miC.getField("totalMem").getLong(mi) / (1024L * 1024L));

            Object ci = amC.getMethod("getDeviceConfigurationInfo").invoke(am);
            glesVersion = toInt(Refl.get(ci, "reqGlEsVersion"), 0);
        }

        // Parámetros de audio nativos del dispositivo (los que usaría una ruta de baja latencia).
        Object audio = sys("audio");
        if(audio != null){
            Method gp = Class.forName("android.media.AudioManager").getMethod("getProperty", String.class);
            outRate = parse((String)gp.invoke(audio, "android.media.property.OUTPUT_SAMPLE_RATE"));
            outFrames = parse((String)gp.invoke(audio, "android.media.property.OUTPUT_FRAMES_PER_BUFFER"));
        }
    }

    private static int parse(String s){
        try{
            return s == null ? 0 : Integer.parseInt(s.trim());
        }catch(NumberFormatException e){
            return 0;
        }
    }

    // ------------------------------------------------------------------ GL y audio del juego

    private static void detectGl() throws Exception{
        Method gs = Class.forName("android.opengl.GLES20").getMethod("glGetString", int.class);
        String v = (String)gs.invoke(null, 0x1F00), r = (String)gs.invoke(null, 0x1F01), ver = (String)gs.invoke(null, 0x1F02);
        if(v != null) glVendor = v;
        if(r != null) glRenderer = r;
        if(ver != null) glVersion = ver;
    }

    private static void detectAudioEngine(){
        Object audio = Refl.getStatic(Core.class, "audio");
        if(audio == null) return;
        audioEngine = audio.getClass().getName();
        // Métodos nativos en el motor de audio del juego: indican un motor en C++ (no SoundPool, que vive en Java/Android).
        int n = 0;
        for(Class<?> c : new Class<?>[]{audio.getClass(), Refl.cls("arc.audio.Sound"), Refl.cls("arc.audio.Music")}){
            if(c == null) continue;
            for(Method m : c.getDeclaredMethods()) if(Modifier.isNative(m.getModifiers())) n++;
        }
        audioNatives = n;
    }

    // ------------------------------------------------------------------ clasificación

    private static int firstNumber(String s){
        Matcher m = Pattern.compile("(\\d{2,4})").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static void classify(){
        String r = glRenderer.toLowerCase(Locale.ROOT);
        int g;
        if(r.contains("adreno")){
            family = "Adreno";
            gpuModel = firstNumber(r);
            g = gpuModel >= 650 ? 2 : gpuModel >= 630 ? 1 : 0; // 610/612/618/619/620 = bajo; 630-649 = medio; 650+ = alto
        }else if(r.contains("mali")){
            family = "Mali";
            gpuModel = firstNumber(r);
            g = gpuModel <= 52 ? 0 : (gpuModel >= 70 && gpuModel < 100) || gpuModel >= 700 ? 2 : 1;
        }else if(r.contains("powervr")){
            family = "PowerVR";
            g = 0;
        }else if(r.contains("xclipse")){
            family = "Xclipse";
            g = 2;
        }else{
            g = 1;
        }

        int ram = ramMB <= 0 ? 1 : ramMB < 3500 ? 0 : ramMB < 7000 ? 1 : 2;
        Cores c = Cores.get();
        int cpu = c.fast >= 6 ? 2 : (c.fast >= 4 && c.maxMHz >= 2800) ? 2 : ((c.fast >= 4 && c.maxMHz >= 2300) || c.fast >= 3) ? 1 : 0;

        // La GPU pesa el doble: en este juego el límite suele ser el relleno de píxeles y el ancho de banda.
        tier = Math.max(0, Math.min(2, Math.round((2f * g + ram + cpu) / 4f)));
    }

    public static String recommended(){
        return tier <= 0 ? "extreme" : tier == 1 ? "battle" : "balanced";
    }

    public static String profileLabel(String p){
        return p.equals("extreme") ? "Extremo" : p.equals("battle") ? "Batalla grande" : "Equilibrado";
    }

    public static String vulkanText(){
        if(vulkanVersion == 0) return "no declarado";
        return (vulkanVersion >> 22) + "." + ((vulkanVersion >> 12) & 0x3ff) + "." + (vulkanVersion & 0xfff)
            + (vulkanLevel >= 0 ? " (nivel " + vulkanLevel + ")" : "");
    }

    public static boolean vulkan11(){
        return vulkanVersion >= VK_1_1;
    }

    public static String oneLine(){
        return family + (gpuModel > 0 ? " " + gpuModel : "") + " | Vulkan " + vulkanText() + " | " + ramMB + " MB RAM | nivel " + tier;
    }

    public static String summary(){
        StringBuilder sb = new StringBuilder();
        sb.append("Android API ").append(apiLevel).append(" | RAM ").append(ramMB).append(" MB | heap máx. ").append(heapMB).append(" MB\n");
        sb.append("GPU: ").append(glRenderer).append(" (").append(glVendor).append("), familia ").append(family)
            .append(gpuModel > 0 ? " " + gpuModel : "").append('\n');
        sb.append("GL: ").append(glVersion).append(" | GLES pedido por el sistema: ")
            .append(glesVersion == 0 ? "?" : (glesVersion >> 16) + "." + (glesVersion & 0xffff)).append('\n');
        sb.append("Vulkan declarado: ").append(vulkanText()).append(vulkan11() ? "  (>= 1.1)" : "").append('\n');
        sb.append("CPU: ").append(Cores.get().summary).append('\n');
        sb.append("Audio: motor ").append(audioEngine).append(", ").append(audioNatives).append(" métodos nativos | baja latencia ")
            .append(lowLatency ? "sí" : "no").append(", pro ").append(proAudio ? "sí" : "no")
            .append(" | salida nativa ").append(outRate).append(" Hz, buffer ").append(outFrames).append(" frames\n");
        sb.append("Nivel de equipo: ").append(tier).append(" -> perfil recomendado: ").append(profileLabel(recommended()));
        return sb.toString();
    }

    // ------------------------------------------------------------------ sugerencia de primer arranque

    static void dialog(String title, String text, String actionText, Runnable action){
        BaseDialog d = new BaseDialog(title);
        Label l = new Label(text);
        l.setWrap(true);
        d.cont.add(l).width(Math.max(260f, Math.min(Core.graphics.getWidth() / Scl.scl(1f) - 120f, 560f))).row();
        if(action != null){
            d.cont.button(actionText, () -> {
                d.hide();
                action.run();
            }).size(320f, 60f).pad(8f).row();
        }
        d.addCloseButton();
        d.show();
    }

    /** Una sola vez: muestra lo detectado y ofrece aplicar el perfil que le corresponde a este equipo. */
    public static void suggestOnce(){
        if(Vars.headless || Vars.ui == null || Core.settings.getBool("mo-hw-suggested", false)) return;
        Core.settings.put("mo-hw-suggested", true);
        final String prof = recommended();
        dialog("Equipo detectado", summary() + "\n\nPuedes aplicar el perfil recomendado ahora, o cambiarlo cuando quieras en Ajustes → MindOptimized.",
            "Aplicar perfil: " + profileLabel(prof), () -> Profiles.apply(prof));
    }
}
