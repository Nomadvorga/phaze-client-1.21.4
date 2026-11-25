package vorga.phazeclient.implement.menu;

import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Offline guards against deferred shader selection followed by ambient GL texture mutations. */
public final class TextureFilteringChecks {
    private static final String MENU = "vorga/phazeclient/implement/menu/";
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (String name : List.of(
                "vorga/phazeclient/api/system/shape/implement/Image",
                MENU + "components/implement/other/BackgroundComponent",
                MENU + "components/implement/window/implement/settings/color/component/AlphaComponent",
                MENU + "components/implement/window/implement/settings/color/component/SaturationComponent",
                MENU + "components/implement/window/implement/settings/color/SettingColorPickerWindow",
                MENU + "components/implement/window/implement/settings/itempicker/ItemPickerColorWindow")) {
            for (MethodNode method : read(name).methods) {
                List<MethodInsnNode> calls = calls(method);
                check(calls.stream().noneMatch(call -> call.name.toLowerCase().contains("texparameter")
                                || call.name.equals("setFilter")),
                        name + "." + method.name + " never mutates ambient texture filtering during drawing");
                check(calls.stream().noneMatch(call -> call.name.startsWith("glGet")),
                        name + "." + method.name + " adds no per-draw GL queries");
            }
        }
        ClassNode font = read(MENU + "loading/LoadingFont");
        MethodNode load = font.methods.stream().filter(method -> method.name.equals("load")).findFirst().orElseThrow();
        MethodNode close = font.methods.stream().filter(method -> method.name.equals("close")).findFirst().orElseThrow();
        check(calls(load).stream().anyMatch(call -> call.owner.equals("com/mojang/blaze3d/platform/TextureUtil")
                        && call.name.equals("generateTextureId")), "bootstrap allocates through Minecraft's texture lifecycle");
        check(calls(close).stream().anyMatch(call -> call.owner.equals("com/mojang/blaze3d/platform/TextureUtil")
                        && call.name.equals("releaseTextureId")), "bootstrap disposal invalidates all cached texture bindings");
        check(calls(close).stream().noneMatch(call -> call.name.equals("glDeleteTextures")), "no raw bootstrap texture disposal");
        int bind = calls(load).stream().filter(call -> call.name.equals("bindTexture")).mapToInt(load.instructions::indexOf).min().orElseThrow();
        int filter = calls(load).stream().filter(call -> call.name.equals("glTexParameteri")).mapToInt(load.instructions::indexOf).min().orElseThrow();
        check(bind < filter, "bootstrap atlas is explicitly bound before filtering/upload");
        for (String name : List.of("LoadingFont", "LoadingGlState", "BootstrapLoadingRenderer")) {
            check(read(MENU + "loading/" + name).methods.stream().flatMap(method -> calls(method).stream())
                            .noneMatch(call -> call.name.equals("glBindTexture") || call.name.equals("glActiveTexture")),
                    "bootstrap texture units/bindings always go through the Minecraft cache: " + name);
        }
        for (String file : List.of("back_arrow", "settings", "trash", "reset", "file", "file_import", "edit", "share")) {
            linear("assets/minecraft/textures/" + file + ".png");
        }
        for (String file : List.of("hue", "alpha", "triangle")) linear("assets/minecraft/textures/color_picker/" + file + ".png");
        linear("assets/phaze/textures/menu/cross.png");
        msdfVanillaCross();
        configCrossUsesMsdf();
        msdfIconSmoothing();
        settingCardOutlineUsesSharedThickness();
        roundedOutlineCoverage();
        // Preserve Image's raster fallback quality even if the MSDF icon atlas is unavailable.
        try (var files = Files.list(Path.of("src/main/resources/assets/phaze/textures/modules"))) {
            for (Path png : files.filter(file -> file.toString().endsWith(".png")).toList()) {
                linear("assets/phaze/textures/modules/" + png.getFileName());
            }
        }
        check(TextureFilteringChecks.class.getClassLoader().getResource("assets/minecraft/textures/atlas/blocks.png.mcmeta") == null,
                "does not override vanilla atlas filtering or mipmap policy");
        System.out.println("Texture filtering checks passed: " + checks + " (no GL context)");
    }

    /** The icon-only edge ramp must be bounded identically in both draw paths. */
    private static void msdfIconSmoothing() throws Exception {
        String atlas = Files.readString(Path.of("src/main/java/vorga/phazeclient/implement/menu/UiMsdfIconAtlas.java"));
        check(atlas.contains("private static final float ICON_SMOOTHNESS = 0.30F;"),
                "MSDF icon default edge ramp is the crisp 0.30 value");
        check(atlas.contains("float iconSmoothness = Math.min(smoothness, ICON_SMOOTHNESS);"),
                "explicit icon ramps cannot exceed the crispness limit");
        check(atlas.contains("MsdfBatch.beginIcon(atlasTexture.getGlId(), distanceRange, thickness, iconSmoothness)"),
                "deferred icon draws receive the bounded edge ramp");
        check(atlas.contains("shader.getUniform(\"Smoothness\").set(iconSmoothness);"),
                "immediate icon draws receive the same bounded edge ramp");
    }

    /** Keep the vanilla close/delete cross addressable through the shared MSDF icon atlas. */
    private static void msdfVanillaCross() throws Exception {
        try (InputStream metadata = TextureFilteringChecks.class.getClassLoader()
                .getResourceAsStream("assets/phaze/msdf/ui_icons.json")) {
            check(metadata != null, "bundled MSDF icon atlas metadata is available");
            var root = JsonParser.parseReader(new InputStreamReader(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
            var atlas = root.getAsJsonObject("atlas");
            check(atlas != null && atlas.get("distanceRange").getAsFloat() > 0.0F,
                    "MSDF icon atlas provides a positive distance range");

            boolean foundCross = false;
            for (var element : root.getAsJsonArray("icons")) {
                var icon = element.getAsJsonObject();
                if (!icon.has("id") || !"minecraft:textures/cross.png".equals(icon.get("id").getAsString())) {
                    continue;
                }
                var cell = icon.getAsJsonObject("atlasBounds");
                var ink = icon.getAsJsonObject("inkBounds");
                check(cell != null && ink != null, "vanilla cross has atlas and visible-ink bounds");
                check(cell.get("right").getAsFloat() > cell.get("left").getAsFloat()
                                && cell.get("bottom").getAsFloat() > cell.get("top").getAsFloat(),
                        "vanilla cross atlas cell has positive dimensions");
                foundCross = true;
                break;
            }
            check(foundCross, "vanilla cross ID resolves to an MSDF atlas entry");
        }
    }

    /** The sidebar delete X must keep its quad/tint but have no raster rendering branch. */
    private static void configCrossUsesMsdf() throws Exception {
        ClassNode background = read(MENU + "components/implement/other/BackgroundComponent");
        MethodNode method = background.methods.stream()
                .filter(candidate -> candidate.name.equals("renderConfigCross"))
                .findFirst().orElseThrow();
        List<MethodInsnNode> methodCalls = calls(method);
        long atlasCalls = methodCalls.stream().filter(call -> call.owner.equals(MENU + "UiMsdfIconAtlas")
                && call.name.equals("renderIcon")).count();
        check(atlasCalls == 1, "config delete X submits exactly one shared-atlas icon quad");
        check(methodCalls.stream().noneMatch(call -> call.owner.equals(MENU + "GuiBatch")
                        || call.owner.equals("vorga/phazeclient/api/system/shape/implement/Image")
                        || call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                        || call.owner.startsWith("net/minecraft/client/render/")),
                "config delete X has no raster texture or immediate-render fallback");

        String source = Files.readString(Path.of(
                "src/main/java/vorga/phazeclient/implement/menu/components/implement/other/BackgroundComponent.java"));
        check(source.contains("Identifier.ofVanilla(\"textures/cross.png\")"),
                "config delete X uses the registered vanilla cross atlas ID");
        check(source.contains("renderIcon(matrix, CONFIG_CROSS_ICON, x, y, size, size, color)"),
                "config delete X preserves its original position, quad size and hover tint");
    }

    /** Both eager fallback and the shared batched shader use the same analytic stroke band. */
    private static void roundedOutlineCoverage() throws Exception {
        String eager = Files.readString(Path.of("src/main/resources/assets/phaze/shaders/core/round.fsh"));
        String batched = Files.readString(Path.of("src/main/resources/assets/phaze/shaders/core/gui_batched.fsh"));
        String[] required = {
                "length(vec2(dFdx(distance), dFdy(distance)))",
                "float shapeCoverage = 1.0 - smoothstep(-halfAa, halfAa, distance);",
                "float strokeWidth = max(thickness - aa, 0.0);",
                "float fillCoverage = 1.0 - smoothstep(-strokeWidth - halfAa, -strokeWidth + halfAa, distance);",
                "float borderCoverage = max(shapeCoverage - fillCoverage, 0.0);"
        };
        for (String fragment : required) {
            check(eager.contains(fragment), "eager rounded shader contains shared SDF outline step: " + fragment);
            check(batched.contains(fragment), "batched rounded shader contains shared SDF outline step: " + fragment);
        }
        String oldBand = "smoothstep(thickness - 2.0, thickness, abs(distance))";
        check(!eager.contains(oldBand) && !batched.contains(oldBand),
                "sub-2px outline no longer uses the fading symmetric thickness-minus-two band");

        String scissor = Files.readString(Path.of("src/main/java/vorga/phazeclient/base/util/render/ScissorManager.java"));
        check(scissor.contains("Math.floor(scissor.y * scaleFactor)")
                        && scissor.contains("Math.ceil((scissor.y + scissor.height) * scaleFactor)"),
                "scissor edges round outward after framebuffer scaling, preserving fractional first rows");
    }

    private static void settingCardOutlineUsesSharedThickness() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/vorga/phazeclient/implement/menu/components/implement/settings/AbstractSettingComponent.java"));
        check(source.contains("private static final float CARD_OUTLINE_THICKNESS = 1.2F;"),
                "all setting cards use the shared intended 1.2px outline value");
        check(source.contains(".thickness(CARD_OUTLINE_THICKNESS)"),
                "setting-card renderer passes the shared outline value to the SDF shader");
    }

    private static void linear(String png) throws Exception {
        try (InputStream image = TextureFilteringChecks.class.getClassLoader().getResourceAsStream(png);
             InputStream metadata = TextureFilteringChecks.class.getClassLoader().getResourceAsStream(png + ".mcmeta")) {
            check(image != null && metadata != null, "bundled UI image and filter metadata are packaged: " + png);
            var texture = JsonParser.parseReader(new InputStreamReader(metadata, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("texture");
            check(texture != null && texture.get("blur").getAsBoolean(), "UI-only linear filter is defined at load time: " + png);
        }
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream input = TextureFilteringChecks.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new AssertionError("Missing class " + name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
