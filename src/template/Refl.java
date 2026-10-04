package template;

import arc.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Reflexión con caché para tocar campos del juego sin depender de que existan al compilar.
 * Si un campo o clase no existe en esta versión de MindustryX, la operación simplemente no hace nada (devuelve
 * null/false) y el módulo que la usa sigue funcionando sin esa parte.
 */
public final class Refl{
    private static final Object NONE = new Object();
    private static final HashMap<String, Object> cache = new HashMap<>();
    private static final HashSet<String> logged = new HashSet<>();

    private Refl(){
    }

    public static void once(String key, Throwable t){
        boolean first;
        synchronized(logged){
            first = logged.add(key);
        }
        if(first) Log.err("[MO] " + key + " falló: " + t);
    }

    public static Class<?> cls(String name){
        try{
            return Class.forName(name);
        }catch(Throwable t){
            return null;
        }
    }

    public static boolean is(Object o, Class<?> c){
        return c != null && c.isInstance(o);
    }

    public static synchronized Field field(Class<?> c, String name){
        String k = c.getName() + "#" + name;
        Object v = cache.get(k);
        if(v != null) return v == NONE ? null : (Field)v;

        Field f = null;
        for(Class<?> x = c; x != null && f == null; x = x.getSuperclass()){
            try{
                f = x.getDeclaredField(name);
                f.setAccessible(true);
            }catch(NoSuchFieldException e){
                f = null;
            }catch(Throwable t){
                f = null;
                break;
            }
        }
        cache.put(k, f == null ? NONE : f);
        return f;
    }

    // ---------------------------------------------------------------- instancia

    public static Object get(Object o, String name){
        if(o == null) return null;
        Field f = field(o.getClass(), name);
        try{
            return f == null ? null : f.get(o);
        }catch(Throwable t){
            return null;
        }
    }

    public static float getF(Object o, String name, float def){
        Object v = get(o, name);
        return v instanceof Number n ? n.floatValue() : def;
    }

    public static boolean getB(Object o, String name, boolean def){
        Object v = get(o, name);
        return v instanceof Boolean b ? b : def;
    }

    /** Escribe un número en un campo float/int/double/long. */
    public static boolean setNum(Object o, String name, float v){
        if(o == null) return false;
        Field f = field(o.getClass(), name);
        if(f == null) return false;
        try{
            Class<?> t = f.getType();
            if(t == float.class) f.setFloat(o, v);
            else if(t == int.class) f.setInt(o, Math.round(v));
            else if(t == double.class) f.setDouble(o, v);
            else if(t == long.class) f.setLong(o, Math.round(v));
            else return false;
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    public static boolean setBool(Object o, String name, boolean v){
        if(o == null) return false;
        Field f = field(o.getClass(), name);
        if(f == null || f.getType() != boolean.class) return false;
        try{
            f.setBoolean(o, v);
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    public static boolean set(Object o, String name, Object v){
        if(o == null) return false;
        Field f = field(o.getClass(), name);
        if(f == null) return false;
        try{
            f.set(o, v);
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    /** Llama a un método sin argumentos. */
    public static boolean call(Object o, String name){
        if(o == null) return false;
        try{
            for(Method m : o.getClass().getMethods()){
                if(m.getName().equals(name) && m.getParameterCount() == 0){
                    m.invoke(o);
                    return true;
                }
            }
        }catch(Throwable t){
            once("llamada " + name, t);
        }
        return false;
    }

    // ---------------------------------------------------------------- estáticos

    public static Object getStatic(Class<?> c, String name){
        Field f = field(c, name);
        try{
            return f == null ? null : f.get(null);
        }catch(Throwable t){
            return null;
        }
    }

    public static boolean getStaticBool(Class<?> c, String name, boolean def){
        Object v = getStatic(c, name);
        return v instanceof Boolean b ? b : def;
    }

    public static boolean setStaticBool(Class<?> c, String name, boolean v){
        Field f = field(c, name);
        if(f == null || f.getType() != boolean.class) return false;
        try{
            f.setBoolean(null, v);
            return true;
        }catch(Throwable t){
            return false;
        }
    }
}
