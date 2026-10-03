package template.gen;

import arc.func.Prov;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.util.Structs;
import mindustry.gen.EntityMapping;
import mindustry.gen.Entityc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/** Registro de entidades del mod (sin cambios funcionales respecto al jar original). */
public final class EntityRegistry{
    private static final ObjectIntMap<Class<? extends Entityc>> ids = new ObjectIntMap<>();
    private static final ObjectMap<String, Prov<? extends Entityc>> map = new ObjectMap<>();

    private EntityRegistry(){
        throw new AssertionError();
    }

    @SuppressWarnings("unchecked")
    public static <T extends Entityc> Prov<T> get(Class<T> type){
        return get(type.getCanonicalName());
    }

    @SuppressWarnings("unchecked")
    public static <T extends Entityc> Prov<T> get(String name){
        return (Prov<T>)map.get(name);
    }

    public static <T extends Entityc> void register(String name, Class<T> type, Prov<? extends T> prov){
        map.put(name, prov);
        ids.put(type, EntityMapping.register(name, prov));
    }

    public static <T extends Unit> void register(UnitType unit, Class<T> type){
        if(type.getName().startsWith("mindustry.gen.")){
            unit.constructor = Structs.find(EntityMapping.idMap, p -> p != null && p.get().getClass().equals(type));
            EntityMapping.nameMap.put(unit.name, unit.constructor);
        }else{
            unit.constructor = get(type);
            EntityMapping.nameMap.put(unit.name, unit.constructor);
        }
    }

    public static int getID(Class<? extends Entityc> type){
        return ids.get(type, -1);
    }

    public static void register(){
    }

    public static void registerUnits(){
    }
}
