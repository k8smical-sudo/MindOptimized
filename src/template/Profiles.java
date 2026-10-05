package template;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;

/**
 * Perfiles de un toque. Cada perfil solo escribe ajustes que ya existen en el mod; nada nuevo se activa a escondidas.
 * Los ajustes que afectan a la simulación (cintas, física, sleep) solo actúan en partidas locales (y las cintas, además,
 * en clientes puros); en host/servidor el juego se queda en vanilla, así que un perfil nunca altera a otros jugadores.
 */
public final class Profiles{
    private Profiles(){
    }

    private static void put(Object[][] kv){
        for(Object[] p : kv){
            String k = (String)p[0];
            if(p[1] instanceof Boolean b) Core.settings.put(k, b);
            else Core.settings.put(k, (Integer)p[1]);
        }
    }

    public static void apply(String name){
        switch(name){
            case "balanced" -> put(new Object[][]{
                {"mo-phys-budget", 2}, {"mo-phys-max", 4},
                {"mo-conv-max", 4}, {"mo-conv-budget", 10}, {"mo-conv-near", 10}, {"mo-conv-items", 0},
                {"flat-anim", FlatRender.ANIM_SYNC}, {"flat-rayalpha", 100}, {"flat-noengine", false}
            });
            case "battle" -> put(new Object[][]{
                {"mo-phys-budget", 1}, {"mo-phys-max", 6},
                {"mo-conv-max", 6}, {"mo-conv-budget", 8}, {"mo-conv-near", 10}, {"mo-conv-items", 6},
                {"flat-anim", 60}, {"flat-rayalpha", 70}, {"flat-noengine", false}
            });
            case "extreme" -> put(new Object[][]{
                {"mo-phys-budget", 1}, {"mo-phys-max", 8},
                {"mo-conv-max", 12}, {"mo-conv-budget", 6}, {"mo-conv-near", 7}, {"mo-conv-items", 10},
                {"flat-anim", 30}, {"flat-rayalpha", 40}, {"flat-noengine", true}
            });
            default -> {
                return;
            }
        }

        // Estos dos se aplican por callback, no por sondeo.
        boolean trailsOff = !name.equals("balanced");
        Core.settings.put("flat-notrail", trailsOff);
        if(FlatRender.inst != null){
            try{
                FlatRender.inst.applyTrails(trailsOff);
                FlatRender.inst.setShading(false);
                Core.settings.put("flat-shading", false);
            }catch(Throwable t){
                Refl.once("perfil", t);
            }
        }

        Log.info("[MO] perfil aplicado: " + name);
        if(Vars.ui != null) Vars.ui.showInfoToast("Perfil aplicado. Reabre Ajustes para ver los valores nuevos.", 4f);
    }
}
