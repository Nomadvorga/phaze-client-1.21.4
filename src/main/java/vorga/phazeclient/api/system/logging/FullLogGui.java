package vorga.phazeclient.api.system.logging;

import vorga.phazeclient.api.feature.module.Module;
import vorga.phazeclient.api.feature.module.setting.Setting;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Only called from mixins targeting Phaze's own GUI classes. */
public final class FullLogGui {
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = type; current != null && current.getName().startsWith("vorga.phazeclient.");
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    String name = field.getName();
                    if (Module.class.isAssignableFrom(field.getType()) || Setting.class.isAssignableFrom(field.getType())
                            || List.of("x", "y", "width", "height", "title", "label", "category", "name",
                            "dragging", "draggable", "resizing", "selected", "listening").contains(name)) {
                        if (field.trySetAccessible()) fields.add(field);
                    }
                }
            }
            return List.copyOf(fields);
        }
    };

    private FullLogGui() {}

    public static void input(Object target, String action, double x, double y, String detail) {
        if (!FullLog.isEnabled() || !isPhazeTarget(target)) return;
        FullLog.event("PHAZE_GUI", "gui:" + target.getClass().getName() + '@' + System.identityHashCode(target) + ':' + action,
                action + " x=" + x + " y=" + y + " " + detail + " " + describe(target));
    }

    public static boolean isPhazeTarget(Object target) {
        return target != null && target.getClass().getName().startsWith("vorga.phazeclient.implement.menu.");
    }

    public static void hud(Object target, String action) {
        if (!FullLog.isEnabled()) return;
        try {
            StringBuilder detail = new StringBuilder("action=").append(action)
                    .append(" module=").append(((Module) target).getIdentifier());
            for (Class<?> current = target.getClass(); current != null && Module.class.isAssignableFrom(current);
                 current = current.getSuperclass()) {
                for (String name : List.of("hudX", "hudY", "hudScale")) {
                    try {
                        Field field = current.getDeclaredField(name);
                        if (field.trySetAccessible()) detail.append(' ').append(name).append('=').append(field.get(target));
                    } catch (NoSuchFieldException ignored) {
                        // The transform lives on a superclass for most HUDs.
                    }
                }
            }
            FullLog.event("PHAZE_HUD_TRANSFORM", "hud:" + ((Module) target).getIdentifier() + ':' + action, detail.toString());
        } catch (ReflectiveOperationException | RuntimeException error) {
            FullLog.error("HUD_DIAGNOSTICS", error);
        }
    }

    static String describe(Object target) {
        StringBuilder result = new StringBuilder("element=").append(target.getClass().getSimpleName());
        try {
            for (Field field : FIELDS.get(target.getClass())) {
                Object value = field.get(target);
                if (value instanceof Module module) value = module.getIdentifier();
                else if (value instanceof Setting setting) value = setting.getModuleContext() + "/" + setting.getRawName();
                else if (value != null && !(value instanceof Number) && !(value instanceof Boolean)
                        && !(value instanceof Enum<?>) && !(value instanceof CharSequence)) continue;
                result.append(' ').append(field.getName()).append('=').append(value);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            FullLog.error("GUI_ELEMENT_DESCRIPTION", error);
        }
        return result.toString();
    }
}
