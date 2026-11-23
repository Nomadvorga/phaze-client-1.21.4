package vorga.phazeclient.implement.hud;

/** Shared HUD widget dimensions, colours and Z layers. */
final class HudStyle {
    private HudStyle() {}

    static final float BASE_WIDTH = 60.0f;
    static final float BASE_HEIGHT = 20.0f;
    static final int HUD_TEXT_COLOR = 0xFFFFFFFF;
    static final float HUD_TEXT_SIZE = 8.0f;
    static final float HUD_TEXT_RENDER_Z = 1000.0f;
    static final int HANDLE_COLOR = 0xFF72F7D4;
    static final float BASE_HOVER_OUTLINE_THICKNESS = 1.0f;
    static final float HUD_RENDER_Z = 400.0f;
    static final float HANDLE_RENDER_Z = 450.0f;
    /**
     * PaperDoll sits ABOVE every panel/text layer. The batched HUD FBO has a
     * depth attachment, so a model drawn at the vanilla inventory Z (50) is
     * depth-occluded by widgets drawn earlier at 400/1000 even though the
     * doll is submitted last. The +300 margin keeps the whole model (its
     * depth extent grows with render scale) in front of HUD_TEXT_RENDER_Z.
     */
    static final float PAPER_DOLL_RENDER_Z = HUD_TEXT_RENDER_Z + 300.0f;
    /** Editor outline/resize handle must stay in front of the model itself. */
    static final float PAPER_DOLL_EDITOR_Z = PAPER_DOLL_RENDER_Z + 300.0f;
}
