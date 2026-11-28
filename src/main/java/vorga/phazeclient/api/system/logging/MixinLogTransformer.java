package vorga.phazeclient.api.system.logging;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Adds stack-neutral probes only to methods actually merged by our mixin. */
public final class MixinLogTransformer {
    private static final String LOG = "vorga/phazeclient/api/system/logging/FullLog";
    private static final String MODULES = "vorga/phazeclient/implement/features/modules/";
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    private MixinLogTransformer() {}

    public static int instrument(ClassNode target, String mixinClassName) {
        // Diagnostic probes must never trace themselves (or a logger's error path).
        if (mixinClassName.contains(".FullLogging")) return 0;
        Set<String> mixinOwners = new LinkedHashSet<>();
        for (MethodNode method : target.methods) {
            if (mergedBy(method, mixinClassName)) collectOwners(method, mixinOwners);
        }
        int count = 0;
        Map<String, String> expectedHandlers = new LinkedHashMap<>();
        for (MethodNode method : target.methods) {
            if (method.name.startsWith("<") || !mergedBy(method, mixinClassName)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || alreadyInstrumented(method)) continue;

            Set<String> owners = new LinkedHashSet<>();
            collectOwners(method, owners);
            // Helpers such as OverlayTexture's reflection resolver belong to the same
            // feature even when they do not perform a singleton lookup themselves.
            if (owners.isEmpty()) owners.addAll(mixinOwners);
            String features = String.join(";", owners);
            String name = method.name + method.desc;
            expectedHandlers.put(name, features);
            AbstractInsnNode[] original = method.instructions.toArray();
            method.instructions.insert(probe(mixinClassName, name, features, "ENTER"));
            for (AbstractInsnNode instruction : original) {
                int opcode = instruction.getOpcode();
                if (instruction instanceof MethodInsnNode call && call.owner.startsWith(MODULES)
                        && !"getInstance".equals(call.name) && !"isEnabled".equals(call.name)
                        && !call.name.startsWith("<")) {
                    method.instructions.insertBefore(instruction, featureProbe(call, mixinClassName + "#" + name, "ENTER"));
                    method.instructions.insert(instruction, featureProbe(call, mixinClassName + "#" + name, "RETURN_OK"));
                }
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    method.instructions.insertBefore(instruction, probe(mixinClassName, name, features, "RETURN_OK"));
                } else if (opcode == Opcodes.ATHROW) {
                    method.instructions.insertBefore(instruction, errorProbe(mixinClassName, name));
                }
            }
            // Existing handlers already have a Throwable on the stack and valid frames.
            // Record exceptions even when the original code silently catches them.
            Set<LabelNode> handlers = new HashSet<>();
            if (method.tryCatchBlocks != null) {
                for (TryCatchBlockNode block : method.tryCatchBlocks) {
                    if (!handlers.add(block.handler)) continue;
                    AbstractInsnNode first = block.handler.getNext();
                    while (first != null && first.getOpcode() < 0) first = first.getNext();
                    if (first != null) method.instructions.insertBefore(first, errorProbe(mixinClassName, name));
                }
            }
            // No new locals, branches, or exception handlers: existing frames stay valid.
            method.maxStack += 4;
            count++;
        }
        if (!expectedHandlers.isEmpty()) {
            for (MethodNode caller : target.methods) {
                boolean changed = false;
                for (AbstractInsnNode instruction : caller.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode call) || !target.name.equals(call.owner)) continue;
                    String method = call.name + call.desc;
                    String owners = expectedHandlers.get(method);
                    if (owners == null) continue;
                    InsnList expected = new InsnList();
                    expected.add(new LdcInsnNode(mixinClassName));
                    expected.add(new LdcInsnNode(method));
                    expected.add(new LdcInsnNode(owners));
                    expected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOG, "mixinExpected",
                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false));
                    caller.instructions.insertBefore(instruction, expected);
                    changed = true;
                }
                if (changed) caller.maxStack += 3;
            }
        }
        return count;
    }

    private static void collectOwners(MethodNode method, Set<String> owners) {
        for (AbstractInsnNode instruction : method.instructions) {
            String owner = instruction instanceof MethodInsnNode call ? call.owner
                    : instruction instanceof FieldInsnNode field ? field.owner : "";
            if (owner.startsWith(MODULES)) owners.add(owner);
        }
    }

    private static boolean mergedBy(MethodNode method, String source) {
        return hasSource(method.visibleAnnotations, source) || hasSource(method.invisibleAnnotations, source);
    }

    private static boolean hasSource(List<AnnotationNode> annotations, String source) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (!MERGED.equals(annotation.desc) || annotation.values == null) continue;
            for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
                if ("mixin".equals(annotation.values.get(index)) && source.equals(annotation.values.get(index + 1))) return true;
            }
        }
        return false;
    }

    private static boolean alreadyInstrumented(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && LOG.equals(call.owner) && "mixin".equals(call.name)) return true;
        }
        return false;
    }

    private static InsnList probe(String source, String method, String features, String phase) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(source));
        code.add(new LdcInsnNode(method));
        code.add(new LdcInsnNode(features));
        code.add(new LdcInsnNode(phase));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOG, "mixin",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false));
        return code;
    }

    private static InsnList errorProbe(String source, String method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(source));
        code.add(new LdcInsnNode(method));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOG, "mixinError",
                "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;)V", false));
        return code;
    }

    private static InsnList featureProbe(MethodInsnNode call, String source, String phase) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(call.owner));
        code.add(new LdcInsnNode(call.name + call.desc));
        code.add(new LdcInsnNode(source));
        code.add(new LdcInsnNode(phase));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOG, "featureCall",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false));
        return code;
    }
}
