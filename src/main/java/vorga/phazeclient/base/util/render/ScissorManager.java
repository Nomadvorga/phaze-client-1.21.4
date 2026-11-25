package vorga.phazeclient.base.util.render;

import com.mojang.blaze3d.systems.RenderSystem;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import vorga.phazeclient.base.QuickImports;

import java.util.ArrayDeque;
import java.util.Deque;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ScissorManager implements QuickImports {
    Deque<Scissor> scissorStack = new ArrayDeque<>();
    Deque<Scissor> freeScissors = new ArrayDeque<>();
    Vector3f scratchPosition = new Vector3f();
    Vector3f scratchSize = new Vector3f();

    public void push(Matrix4f matrix4f, float x, float y, float width, float height) {
        // No batch flush: batched GUI vertices record the scissor active at
        // submission (GuiClipState) and clip themselves in the shader.
        Vector3f pos = matrix4f.transformPosition(x, y, 0, scratchPosition);
        Vector3f size = matrix4f.getScale(scratchSize).mul(width, height, 0);

        Scissor newScissor = freeScissors.pollFirst();
        if (newScissor == null) {
            newScissor = new Scissor();
        }

        if (!scissorStack.isEmpty()) {
            Scissor prevScissor = scissorStack.peek();

            double intersectX = Math.max(prevScissor.x, pos.x);
            double intersectY = Math.max(prevScissor.y, pos.y);
            double intersectWidth = Math.min(prevScissor.x + prevScissor.width, pos.x + size.x) - intersectX;
            double intersectHeight = Math.min(prevScissor.y + prevScissor.height, pos.y + size.y) - intersectY;

            newScissor.set(intersectX, intersectY, intersectWidth, intersectHeight);
        } else {
            newScissor.set(pos.x, pos.y, size.x, size.y);
        }

        scissorStack.push(newScissor);
        setScissor(newScissor);
    }

    public void pop() {
        if (!scissorStack.isEmpty()) {
            freeScissors.addFirst(scissorStack.pop());
            if (scissorStack.isEmpty()) {
                RenderSystem.disableScissor();
            } else {
                setScissor(scissorStack.peek());
            }
        }
    }

    private void setScissor(Scissor scissor) {
        double scaleFactor = window().getScaleFactor();
        // Round the transformed endpoints outward in framebuffer pixels.
        // Rounding GUI-space origin and size separately before multiplying
        // can move a half-pixel viewport inward (the config sidebar starts
        // at y + 42.5), cutting the first row and other rounded edges.
        int left = (int) Math.floor(scissor.x * scaleFactor);
        int right = (int) Math.ceil((scissor.x + scissor.width) * scaleFactor);
        int top = (int) Math.floor(scissor.y * scaleFactor);
        int bottom = (int) Math.ceil((scissor.y + scissor.height) * scaleFactor);
        int x = left;
        int y = window().getHeight() - bottom;
        int width = Math.max(0, right - left);
        int height = Math.max(0, bottom - top);

        RenderSystem.enableScissor(x, y, width, height);
    }

    private static class Scissor {
        public double x, y;
        public double width, height;

        public void set(double x, double y, double width, double height) {
            this.x = Math.max(0.0, x);
            this.y = Math.max(0.0, y);
            this.width = Math.max(0.0, width);
            this.height = Math.max(0.0, height);
        }

        Scissor copy() {
            Scissor newScissor = new Scissor();
            newScissor.set(this.x, this.y, this.width, this.height);
            return newScissor;
        }
    }
}
