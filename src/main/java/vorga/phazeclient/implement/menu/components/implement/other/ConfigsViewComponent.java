package vorga.phazeclient.implement.menu.components.implement.other;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import vorga.phazeclient.api.system.animation.Animation;
import vorga.phazeclient.api.system.animation.Direction;
import vorga.phazeclient.api.system.animation.implement.DecelerateAnimation;
import vorga.phazeclient.api.system.font.msdf.MsdfFonts;
import vorga.phazeclient.api.system.font.msdf.MsdfRenderer;
import vorga.phazeclient.api.system.shape.ShapeProperties;
import vorga.phazeclient.api.system.shape.batched.BatchedRectangle;
import vorga.phazeclient.base.util.Lang;
import vorga.phazeclient.base.util.math.MathUtil;
import vorga.phazeclient.implement.config.ConfigManager;
import vorga.phazeclient.implement.menu.MenuScreen;
import vorga.phazeclient.implement.menu.MenuStyle;
import vorga.phazeclient.implement.menu.UiMsdfIconAtlas;
import vorga.phazeclient.implement.menu.components.AbstractComponent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "CONFIGS" tab content. The sidebar is hidden by {@code MenuScreen}
 * while this view is open, so the card grid spans the full menu width
 * minus equal side margins. No header / back-arrow / title - the user
 * told us those duplicated information already shown in the top tabs.
 *
 * <p>Three square-ish cards per row, each carrying:
 * <ul>
 *   <li>Top-left: file / file-import type icon.</li>
 *   <li>Top: config name (ellipsis-trimmed to the card width).</li>
 *   <li>Under the name: size chip + short modified chip
 *       ("Сегодня, 03:32", "Вчера, 21:07", "1 день назад").</li>
 *   <li>Bottom-right: three square keys (rename / share / delete),
 *       delete on a red-tinted plate.</li>
 * </ul>
 *
 * <p>Click on the card body activates that config (loads it). The wheel
 * moves a scroll target that the render eases towards, and the scissor
 * window ends flush with the GUI's bottom edge.
 */
public class ConfigsViewComponent extends AbstractComponent {

    private static final float CARD_HEIGHT = 54.0F;
    private static final float CARD_GAP = 4.0F;
    private static final float CARD_RADIUS = 4.0F;
    private static final int CARD_COLUMNS = 3;
    private static final float CARD_PAD = 8.0F;
    private static final float CARD_ICON_SIZE = 16.0F;
    private static final float NAME_SIZE = 7.0F;
    private static final float META_SIZE = 5.6F;
    /** Square inline action buttons (rename / share / delete), each with
     *  its own rounded-square plate so they read as three keys instead
     *  of one grouped strip. Hover-anim per (config, kind). */
    private static final float ACTION_BUTTON_SIZE = 14.0F;
    private static final float ACTION_BUTTON_GAP = 3.0F;
    private static final float ACTION_BUTTON_RADIUS = 3.5F;
    private static final float ACTION_ICON_SIZE = 8.5F;
    private static final float ACTIONS_TRAIL_PAD = 6.0F;
    private static final float ACTIONS_BOTTOM_PAD = 6.0F;
    /** Size of the inline meta icons (size, clock for last-modified,
     *  cloud for imported). The cloud / clock icons are visually
     *  lighter than the size icon, so they're rendered larger to
     *  read at the same weight in the strip. */
    private static final float META_ICON_SIZE = 6.5F;
    private static final float META_ICON_SIZE_LARGE = 7.8F;
    private static final float META_ICON_GAP = 3.0F;
    private static final float META_GROUP_GAP = 8.0F;

    /** Top inset keeps the grid out from under the top tabs. The bottom
     *  inset is hair-thin so the scissor (and therefore the cards) ends
     *  flush with the GUI's bottom edge instead of leaving a dead band. */
    private static final float TOP_MARGIN = 50.0F;
    private static final float BOTTOM_MARGIN = 2.0F;
    private static final float SIDE_MARGIN = 12.0F;

    private final Animation openAnim = new DecelerateAnimation().setMs(1).setValue(1);
    private final Map<String, Animation> rowHoverAnims = new HashMap<>();
    /** Hover animations per (config name + action kind) so each
     *  inline icon button (rename / share / delete) animates
     *  independently. Key format: {@code <name>::<kind>}. */
    private final Map<String, Animation> actionHoverAnims = new HashMap<>();
    /** Per-row "is this the active config?" animation. Tweens the
     *  outline colour from the standard BORDER to the green-tinted
     *  CHIP_ACTIVE mix when a config becomes active and back when
     *  another config takes its place. Same approach the module
     *  card uses for its enable/disable outline pulse, so the two
     *  surfaces feel like one design language. */
    private final Map<String, Animation> activeAnims = new HashMap<>();

    /** Popup attached to the row whose kebab the user clicked.
     *  Lives until its fade-out animation finishes so close-clicks
     *  visually trail off instead of snapping.
     *
     *  <p>Kept around because the share / rename modals are still
     *  spawned via the same constants the popup formerly used. The
     *  popup itself is no longer rendered or instantiated - the row
     *  shows three inline icon buttons (rename / share / delete)
     *  instead. */
    private KebabPopup popup = null;

    private boolean open = false;

    private final Map<String, String> sizeCache = new LinkedHashMap<>();
    private final Map<String, Long> modifiedTimeCache = new LinkedHashMap<>();
    private final Map<String, String> relativeModifiedCache = new LinkedHashMap<>();
    private final Map<String, Boolean> importedCache = new LinkedHashMap<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private long nextRelativeRefreshMs = 0L;

    private float scrollOffset = 0.0F;
    /** Glide target follower: mouse wheel moves {@link #scrollOffset},
     *  rendering eases towards it so the grid scrolls smoothly. */
    private float smoothScrollOffset = 0.0F;
    private float maxScrollOffset = 0.0F;

    public void open() {
        this.open = true;
        this.openAnim.setDirection(Direction.FORWARDS);
        if (this.popup != null) this.popup.close();
        this.popup = null;
        this.scrollOffset = 0.0F;
        this.smoothScrollOffset = 0.0F;
        refreshMetadataCaches();
    }

    public void close() {
        this.open = false;
        this.openAnim.setDirection(Direction.BACKWARDS);
        if (this.popup != null) this.popup.close();
    }

    public boolean isOpen() {
        return open || openAnim.getOutputFloat() > 0.001F;
    }

    public boolean isActive() {
        return open;
    }

    public boolean shouldRender() {
        return open || openAnim.getOutputFloat() > 0.001F;
    }

    private void refreshMetadataCaches() {
        sizeCache.clear();
        modifiedTimeCache.clear();
        relativeModifiedCache.clear();
        importedCache.clear();
        ConfigManager mgr = ConfigManager.getInstance();
        long now = System.currentTimeMillis();
        for (String name : mgr.getConfigList()) {
            File file = mgr.getConfigFile(name);
            boolean exists = file.exists();
            long mtime = exists ? file.lastModified() : 0L;
            sizeCache.put(name, exists ? humanReadableSize(file.length()) : "—");
            modifiedTimeCache.put(name, mtime);
            relativeModifiedCache.put(name, humanReadableModified(now, mtime));
            importedCache.put(name, mgr.isImportedConfig(name));
        }
        nextRelativeRefreshMs = now + 1000L;
    }

    private void refreshRelativeModifiedCache() {
        long now = System.currentTimeMillis();
        if (now < nextRelativeRefreshMs) {
            return;
        }
        for (Map.Entry<String, Long> entry : modifiedTimeCache.entrySet()) {
            relativeModifiedCache.put(entry.getKey(), humanReadableModified(now, entry.getValue()));
        }
        nextRelativeRefreshMs = now + 1000L;
    }

    /**
     * Hook the import modal calls after a successful download. Just
     * a thin wrapper around {@link #refreshMetadataCaches} that the menu
     * can pass as a {@code Runnable} - keeps the import path from
     * having to know about ConfigsView's internals.
     */
    public void refreshAfterImport() {
        refreshMetadataCaches();
    }

    /* ============================================================ */
    /* render                                                       */
    /* ============================================================ */

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!open && openAnim.getOutputFloat() <= 0.001F) return;
        // Combine the menu's open/close fade (globalAlpha, set by
        // MenuScreen each frame) with our own configs-view open/close
        // fade so the rows AND the kebab popup softly fade in/out
        // alongside the rest of the GUI instead of snapping.
        float fadeAlpha = openAnim.getOutputFloat() * globalAlpha;
        refreshRelativeModifiedCache();

        float listX = x + SIDE_MARGIN;
        float listW = width - SIDE_MARGIN * 2.0F;
        float listTop = y + TOP_MARGIN;
        float listBottom = y + height - BOTTOM_MARGIN;

        renderRows(context, mouseX, mouseY, listX, listTop, listW, listBottom, fadeAlpha);

        // Drop the popup once its fade-out is fully done so we don't
        // keep dispatching hover work to a transparent panel.
        if (popup != null) {
            popup.render(context, mouseX, mouseY, fadeAlpha);
            if (popup.isFullyClosed()) {
                popup = null;
            }
        }
    }

    private void renderRows(DrawContext context, int mouseX, int mouseY,
                            float listX, float listY, float listW, float listBottom,
                            float fadeAlpha) {
        MatrixStack matrix = context.getMatrices();
        ConfigManager mgr = ConfigManager.getInstance();
        String[] names = mgr.getConfigList();

        // Bound scroll to the actual visible window so the user can
        // never scroll past the last row or above the first.
        float listH = listBottom - listY;
        int rowCount = (names.length + CARD_COLUMNS - 1) / CARD_COLUMNS;
        float contentH = rowCount * (CARD_HEIGHT + CARD_GAP) - CARD_GAP;
        maxScrollOffset = Math.max(0.0F, contentH - listH);
        if (scrollOffset < 0.0F) scrollOffset = 0.0F;
        if (scrollOffset > maxScrollOffset) scrollOffset = maxScrollOffset;
        // Ease the rendered offset towards the wheel target so scrolling
        // glides instead of snapping one row per notch.
        smoothScrollOffset = (float) MathUtil.interpolateSmooth(3.2D, smoothScrollOffset, scrollOffset);
        if (Math.abs(smoothScrollOffset - scrollOffset) < 0.01F) {
            smoothScrollOffset = scrollOffset;
        }

        // GL scissor so any card that runs past the visible window is
        // clipped to it. The window ends flush with the GUI bottom edge.
        BatchedRectangle.flushIfBatching();
        context.enableScissor((int) listX, (int) listY,
                (int) (listX + listW), (int) listBottom);

        // Equal side margins + equal column gaps keep the grid centred.
        float cardW = (listW - CARD_GAP * (CARD_COLUMNS - 1)) / CARD_COLUMNS;
        for (int i = 0; i < names.length; i++) {
            int column = i % CARD_COLUMNS;
            int row = i / CARD_COLUMNS;
            float cardX = listX + column * (cardW + CARD_GAP);
            float cardY = listY + row * (CARD_HEIGHT + CARD_GAP) - smoothScrollOffset;
            if (cardY + CARD_HEIGHT > listY && cardY < listBottom) {
                renderCard(matrix, mouseX, mouseY, cardX, cardY, cardW, names[i], fadeAlpha);
            }
        }

        BatchedRectangle.flushIfBatching();
        context.disableScissor();
    }

    private void renderCard(MatrixStack matrix, int mouseX, int mouseY,
                            float cardX, float cardY, float cardW,
                            String name, float fadeAlpha) {
        boolean hovered = MathUtil.isHovered(mouseX, mouseY, cardX, cardY, cardW, CARD_HEIGHT);
        if (hovered) {
            vorga.phazeclient.api.system.cursor.CursorManager.requestHand();
        }
        Animation rowAnim = rowHoverAnims.computeIfAbsent(name,
                k -> new DecelerateAnimation().setMs(160).setValue(1));
        rowAnim.setDirection(hovered ? Direction.FORWARDS : Direction.BACKWARDS);
        float h = rowAnim.getOutputFloat();

        // Active config gets the same green-tinted outline that an
        // enabled module card uses (BORDER → CHIP_ACTIVE 75% mix);
        // every other card keeps the standard BORDER outline. The
        // per-card activeAnim tweens the mix factor so picking a config
        // fades the highlight in instead of snapping.
        boolean isActive = name.equalsIgnoreCase(ConfigManager.getInstance().getCurrentConfigName());
        Animation activeAnim = activeAnims.computeIfAbsent(name,
                k -> new DecelerateAnimation().setMs(300).setValue(1));
        activeAnim.setDirection(isActive ? Direction.FORWARDS : Direction.BACKWARDS);
        float activeT = activeAnim.getOutputFloat();
        int baseOutline = MenuStyle.mix(MenuStyle.BORDER, MenuStyle.CHIP_ACTIVE, activeT * 0.75F);
        int fill = MenuStyle.mix(MenuStyle.PANEL_CHIP, MenuStyle.CARD_OPTIONS, 0.30F + h * 0.10F);
        int outline = MenuStyle.mix(baseOutline, MenuStyle.BORDER_LIGHT, h * 0.40F);
        rectangle.render(ShapeProperties.create(matrix, cardX, cardY, cardW, CARD_HEIGHT)
                .round(CARD_RADIUS).thickness(1.0F + activeT * 1.2F)
                .outlineColor(MenuStyle.withAlpha(outline, fadeAlpha))
                .color(MenuStyle.withAlpha(fill, fadeAlpha * 0.92F))
                .build());

        // Type icon top-left. {@code file_import.png} for configs
        // imported from a server share-key, {@code file.png} for
        // locally created configs.
        boolean imported = importedCache.getOrDefault(name, Boolean.FALSE);
        String iconTexture = imported ? "textures/file_import.png" : "textures/file.png";
        float iconWidth = resolveUiIconWidth(iconTexture, CARD_ICON_SIZE);
        renderUiIcon(matrix, iconTexture, cardX + CARD_PAD, cardY + CARD_PAD,
                iconWidth, CARD_ICON_SIZE,
                MenuStyle.withAlpha(MenuStyle.TEXT_PRIMARY, fadeAlpha * 0.85F));

        // Name beside the icon, meta strip (size + modified) under it.
        float textX = cardX + CARD_PAD + CARD_ICON_SIZE + 6.0F;
        float nameY = cardY + CARD_PAD + 1.0F;
        float metaY = nameY + NAME_SIZE + 4.0F;
        float nameMaxW = cardW - (textX - cardX) - CARD_PAD;
        MsdfRenderer.renderText(
                MsdfFonts.bold(), fitLabel(name, nameMaxW), NAME_SIZE,
                MenuStyle.withAlpha(MenuStyle.TEXT_PRIMARY, fadeAlpha),
                matrix.peek().getPositionMatrix(),
                textX,
                nameY,
                0.0F
        );
        renderRowMeta(matrix, cardX, textX, metaY, cardW, name, imported, fadeAlpha);

        // Three square action buttons bottom-right: rename → share →
        // delete. Each has its own plate, hover animation and hit-area.
        renderActionButtons(matrix, mouseX, mouseY, cardX, cardY, cardW, name, fadeAlpha);
    }

    /** Trims a card title with an ellipsis when it outgrows the card. */
    private static String fitLabel(String text, float maxWidth) {
        if (MsdfFonts.bold().getWidth(text, NAME_SIZE) <= maxWidth) return text;
        String ellipsis = "…";
        float ellipsisWidth = MsdfFonts.bold().getWidth(ellipsis, NAME_SIZE);
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() > 1
                && MsdfFonts.bold().getWidth(builder.toString(), NAME_SIZE) + ellipsisWidth > maxWidth) {
            builder.deleteCharAt(builder.length() - 1);
        }
        return builder.append(ellipsis).toString();
    }

    /**
     * Bottom-line meta strip: size + (imported badge OR last-modified
     * humanised). Each chip is an inline icon followed by a label,
     * separated by a fixed gap so the strip layout stays predictable
     * across different label widths.
     */
    private void renderRowMeta(MatrixStack matrix, float cardX, float textX, float metaY,
                               float cardW, String configName, boolean imported, float fadeAlpha) {
        int metaColor = MenuStyle.withAlpha(MenuStyle.TEXT_MUTED, fadeAlpha * 0.95F);
        String sizeLabel = sizeCache.getOrDefault(configName, "—");
        String modifiedLabel = imported
                ? Lang.translate("Imported from cloud")
                : relativeModifiedCache.getOrDefault(configName, "—");
        String modifiedIcon = imported ? "textures/cloud.png" : "textures/clock.png";

        // The strip normally aligns with the name. On narrow cards it
        // falls back to the full card width, and the imported label
        // shortens, so nothing ever runs past the card's right edge.
        float startX = textX;
        float available = cardX + cardW - CARD_PAD - startX;
        float total = metaChipWidth("textures/size.png", sizeLabel, META_ICON_SIZE)
                + META_GROUP_GAP
                + metaChipWidth(modifiedIcon, modifiedLabel, META_ICON_SIZE_LARGE);
        if (total > available) {
            startX = cardX + CARD_PAD;
            available = cardW - CARD_PAD * 2.0F;
        }
        if (imported && total > available) {
            modifiedLabel = Lang.RU.equals(Lang.getActive()) ? "Из облака" : "From cloud";
        }

        // Size chip - dedicated size.png icon, tinted with the same
        // muted text colour so it reads as part of the meta strip
        // instead of standing out as a stray accent. The icon carries
        // the meaning, so the label stays short (value only).
        float cursorX = renderMetaChip(matrix, startX, metaY,
                "textures/size.png",
                sizeLabel,
                metaColor, fadeAlpha, 1.0F, META_ICON_SIZE);
        cursorX += META_GROUP_GAP;

        // Modified chip - clock icon + short relative label
        // ("Сегодня, 03:32" / "1 день назад"); imported configs swap it
        // for the cloud marker so remote-origin entries spot at a glance.
        renderMetaChip(matrix, cursorX, metaY,
                modifiedIcon,
                modifiedLabel,
                metaColor, fadeAlpha, 1.5F, META_ICON_SIZE_LARGE);
    }

    private float metaChipWidth(String iconTexture, String label, float iconSize) {
        return resolveUiIconWidth(iconTexture, iconSize) + META_ICON_GAP
                + MsdfFonts.bold().getWidth(label, META_SIZE);
    }

    /** Renders one icon+label chip and returns the x coordinate
     *  immediately after the label, so the caller can append the
     *  next chip with a fixed group gap. {@code labelDeltaY} nudges
     *  the text vertically without moving the icon - useful when an
     *  icon is asymmetrically weighted in its bbox. {@code iconSize}
     *  picks between the standard 6.5px rendering and the 1.5×
     *  oversized variant for the cloud / clock glyphs. */
    private float renderMetaChip(MatrixStack matrix, float startX, float baselineY,
                                 String iconTexture, String label,
                                 int color, float fadeAlpha,
                                 float labelDeltaY, float iconSize) {
        float iconWidth = resolveUiIconWidth(iconTexture, iconSize);
        float iconY = baselineY + (META_SIZE - iconSize) * 0.5F + 0.4F;
        renderUiIcon(matrix, iconTexture, startX, iconY, iconWidth, iconSize, color);
        float labelX = startX + iconWidth + META_ICON_GAP;
        MsdfRenderer.renderText(
                MsdfFonts.bold(), label, META_SIZE,
                color,
                matrix.peek().getPositionMatrix(),
                labelX, baselineY + labelDeltaY, 0.0F
        );
        return labelX + MsdfFonts.bold().getWidth(label, META_SIZE);
    }

    private float resolveUiIconWidth(String iconTexture, float iconHeight) {
        float aspectRatio = UiMsdfIconAtlas.resolveAspectRatio(Identifier.ofVanilla(iconTexture));
        return iconHeight * Math.max(0.0001F, aspectRatio);
    }

    private void renderUiIcon(MatrixStack matrix, String iconTexture, float x, float y, float width, float height, int color) {
        // Image.render still uses the legacy swapped width/height convention,
        // so pass the box dimensions in that order to keep non-square icons
        // aligned without stretching in Configs rows.
        image.setTexture(iconTexture)
                .render(ShapeProperties.create(matrix, x, y, height, width)
                        .color(color)
                        .build());
    }

    private String humanReadableSize(long bytes) {
        if (bytes < 0L) return "—";
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private String humanReadableModified(long now, long modifiedAt) {
        if (modifiedAt <= 0L) return "—";
        long delta = now - modifiedAt;
        if (delta < 0L) delta = 0L;
        long sec = delta / 1000L;
        if (sec < 60L) return Lang.translate("just now");
        long min = sec / 60L;
        if (min < 60L) return formatRelativeAgo(min, "minute");
        // Recent days read better as "Сегодня, 03:32" / "Вчера, 21:07"
        // than as a growing hour count; older entries stay relative.
        java.util.Calendar modified = java.util.Calendar.getInstance();
        modified.setTimeInMillis(modifiedAt);
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(now);
        boolean russian = Lang.RU.equals(Lang.getActive());
        String clock = timeFormat.format(new Date(modifiedAt));
        if (sameDay(modified, day)) {
            return (russian ? "Сегодня, " : "Today, ") + clock;
        }
        day.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(modified, day)) {
            return (russian ? "Вчера, " : "Yesterday, ") + clock;
        }
        long hr = min / 60L;
        long dayCount = hr / 24L;
        if (dayCount < 30L) return formatRelativeAgo(dayCount, "day");
        long mon = dayCount / 30L;
        if (mon < 12L) return formatRelativeAgo(mon, "month");
        long yr = mon / 12L;
        return formatRelativeAgo(yr, "year");
    }

    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private String formatRelativeAgo(long value, String unit) {
        if (!Lang.RU.equals(Lang.getActive())) {
            return value + " " + unit + (value == 1L ? " ago" : "s ago");
        }
        return switch (unit) {
            case "minute" -> formatRelativeAgoRu(value, "минуту", "минуты", "минут");
            case "hour" -> formatRelativeAgoRu(value, "час", "часа", "часов");
            case "day" -> formatRelativeAgoRu(value, "день", "дня", "дней");
            case "month" -> formatRelativeAgoRu(value, "месяц", "месяца", "месяцев");
            case "year" -> formatRelativeAgoRu(value, "год", "года", "лет");
            default -> value + " " + unit;
        };
    }

    private String formatRelativeAgoRu(long value, String singular, String paucal, String plural) {
        return value + " " + russianPlural(value, singular, paucal, plural) + " назад";
    }

    private String russianPlural(long value, String singular, String paucal, String plural) {
        long mod100 = value % 100L;
        long mod10 = value % 10L;
        if (mod100 >= 11L && mod100 <= 14L) return plural;
        if (mod10 == 1L) return singular;
        if (mod10 >= 2L && mod10 <= 4L) return paucal;
        return plural;
    }

    /** Visual order of the inline action buttons on every row.
     *  Indexed by the {@link ActionKind} ordinal. The kebab popup's
     *  legacy constants ({@code "kebab.share"} etc.) still drive the
     *  share / rename modals, but the row UI itself now uses
     *  these icons instead. */
    private enum ActionKind {
        RENAME("textures/edit.png"),
        SHARE("textures/share.png"),
        DELETE("textures/trash.png");

        final String texture;
        ActionKind(String texture) {
            this.texture = texture;
        }
    }

    private void renderActionButtons(MatrixStack matrix, int mouseX, int mouseY,
                                     float cardX, float cardY, float cardW,
                                     String configName, float fadeAlpha) {
        ActionKind[] kinds = ActionKind.values();
        float totalW = kinds.length * ACTION_BUTTON_SIZE
                + (kinds.length - 1) * ACTION_BUTTON_GAP;
        float startX = cardX + cardW - totalW - ACTIONS_TRAIL_PAD;
        float btnY = cardY + CARD_HEIGHT - ACTION_BUTTON_SIZE - ACTIONS_BOTTOM_PAD;
        for (int i = 0; i < kinds.length; i++) {
            ActionKind kind = kinds[i];
            float btnX = startX + i * (ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP);
            renderActionButton(matrix, mouseX, mouseY, btnX, btnY, configName, kind, fadeAlpha);
        }
    }

    private void renderActionButton(MatrixStack matrix, int mouseX, int mouseY,
                                    float btnX, float btnY, String configName,
                                    ActionKind kind, float fadeAlpha) {
        boolean hover = MathUtil.isHovered(mouseX, mouseY, btnX, btnY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE);
        if (hover) {
            vorga.phazeclient.api.system.cursor.CursorManager.requestHand();
        }
        String key = configName + "::" + kind.name();
        Animation a = actionHoverAnims.computeIfAbsent(key,
                k -> new DecelerateAnimation().setMs(140).setValue(1));
        a.setDirection(hover ? Direction.FORWARDS : Direction.BACKWARDS);
        float h = a.getOutputFloat();

        // Each key sits on its own rounded-square plate; delete keeps a
        // red-tinted plate so the destructive action reads at a glance.
        boolean delete = kind == ActionKind.DELETE;
        int plateFill = delete
                ? MenuStyle.mix(MenuStyle.PANEL_BG, 0xFFB55F73, 0.14F + h * 0.12F)
                : MenuStyle.mix(MenuStyle.PANEL_CHIP, MenuStyle.CARD_OPTIONS, 0.35F + h * 0.15F);
        int plateOutline = delete
                ? MenuStyle.mix(0xFFB55F73, 0xFFFF9BAA, 0.25F + h * 0.50F)
                : MenuStyle.mix(MenuStyle.BORDER, MenuStyle.BORDER_LIGHT, 0.20F + h * 0.60F);
        rectangle.render(ShapeProperties.create(matrix, btnX, btnY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)
                .round(ACTION_BUTTON_RADIUS).thickness(1.0F)
                .outlineColor(MenuStyle.withAlpha(plateOutline, fadeAlpha))
                .color(MenuStyle.withAlpha(plateFill, fadeAlpha))
                .build());

        int color = delete
                ? MenuStyle.mix(0xFFB55F73, 0xFFFF9BAA, 0.30F + h * 0.55F)
                : MenuStyle.mix(MenuStyle.TEXT_MUTED, MenuStyle.TEXT_PRIMARY, 0.30F + h * 0.55F);
        float iconX = btnX + (ACTION_BUTTON_SIZE - ACTION_ICON_SIZE) * 0.5F;
        float iconY = btnY + (ACTION_BUTTON_SIZE - ACTION_ICON_SIZE) * 0.5F;
        image.setTexture(kind.texture)
                .render(ShapeProperties.create(matrix, iconX, iconY, ACTION_ICON_SIZE, ACTION_ICON_SIZE)
                        .color(MenuStyle.withAlpha(color, fadeAlpha))
                        .build());
    }

    /* ============================================================ */
    /* events                                                       */
    /* ============================================================ */

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) return false;
        if (button != 0) return false;

        // Popup intercepts first so a click inside it lands on its
        // action menu, not on the row underneath. Only popups that
        // are still opening / fully open answer to clicks - one that
        // is mid-fade-out is purely visual.
        if (popup != null && popup.isInteractive()) {
            if (popup.handleClick(mouseX, mouseY)) {
                popup.close();
                return true;
            }
            // Click outside the popup starts its smooth fade-out. We
            // deliberately FALL THROUGH so a click on a different
            // kebab in the same gesture immediately spawns the new
            // popup - without this, the user has to click twice
            // (once to close the old popup, once to open the new
            // one), which reads as "clicking the kebab does nothing"
            // half the time.
            popup.close();
        }

        float listX = x + SIDE_MARGIN;
        float listW = width - SIDE_MARGIN * 2.0F;
        float listTop = y + TOP_MARGIN;
        float listBottom = y + height - BOTTOM_MARGIN;
        if (mouseY < listTop || mouseY > listBottom) return false;
        if (mouseX < listX || mouseX > listX + listW) return false;

        ConfigManager mgr = ConfigManager.getInstance();
        String[] names = mgr.getConfigList();
        float cardW = (listW - CARD_GAP * (CARD_COLUMNS - 1)) / CARD_COLUMNS;
        ActionKind[] kinds = ActionKind.values();
        float actionsTotalW = kinds.length * ACTION_BUTTON_SIZE
                + (kinds.length - 1) * ACTION_BUTTON_GAP;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            int column = i % CARD_COLUMNS;
            int row = i / CARD_COLUMNS;
            float cardX = listX + column * (cardW + CARD_GAP);
            float cardY = listTop + row * (CARD_HEIGHT + CARD_GAP) - smoothScrollOffset;
            if (cardY + CARD_HEIGHT > listTop && cardY < listBottom) {
                // Square action buttons take precedence over the
                // body-click "load this config" hit-area, so the
                // user can rename / share / delete without the card
                // also activating itself underneath.
                float actionsStartX = cardX + cardW - actionsTotalW - ACTIONS_TRAIL_PAD;
                float btnY = cardY + CARD_HEIGHT - ACTION_BUTTON_SIZE - ACTIONS_BOTTOM_PAD;
                ActionKind hit = null;
                for (int k = 0; k < kinds.length; k++) {
                    float btnX = actionsStartX + k * (ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP);
                    if (MathUtil.isHovered(mouseX, mouseY, btnX, btnY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)) {
                        hit = kinds[k];
                        break;
                    }
                }
                if (hit != null) {
                    playButtonClickSound();
                    handleActionClick(name, hit);
                    return true;
                }

                if (MathUtil.isHovered(mouseX, mouseY, cardX, cardY, cardW, CARD_HEIGHT)) {
                    playButtonClickSound();
                    mgr.loadConfig(name);
                    refreshMetadataCaches();
                    return true;
                }
            }
        }
        return false;
    }

    /** Routes an inline action button click to the appropriate
     *  flow. Renames go through the rename modal so the user can
     *  type a new name; share spawns the share-key creation modal;
     *  delete is one-shot through {@link ConfigManager#deleteConfig}.
     *  All three mirror what the old kebab popup used to do, just
     *  without the popup intermediate UI. */
    private void handleActionClick(String configName, ActionKind kind) {
        switch (kind) {
            case RENAME -> MenuScreen.INSTANCE.openConfigRenameModal(configName, this::refreshMetadataCaches);
            case SHARE -> MenuScreen.INSTANCE.openConfigShareModal(configName);
            case DELETE -> {
                ConfigManager.getInstance().deleteConfig(configName);
                refreshMetadataCaches();
            }
        }
    }

    /**
     * Returns true while a kebab popup is currently visible (open or
     * mid-fade-out). MenuScreen reads this to refuse menu-window
     * drags while a popup or modal is up - otherwise the click that
     * closes the popup also drags the entire menu, which feels broken.
     */
    public boolean isPopupOpen() {
        return popup != null && popup.isInteractive();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!open) return false;
        scrollOffset -= (float) amount * (CARD_HEIGHT + CARD_GAP);
        if (scrollOffset < 0.0F) scrollOffset = 0.0F;
        if (scrollOffset > maxScrollOffset) scrollOffset = maxScrollOffset;
        return true;
    }

    /* ============================================================ */
    /* helpers                                                      */
    /* ============================================================ */

    private static String nextRename(String base) {
        for (int i = 2; i < 100; i++) {
            String candidate = base + "_" + i;
            if (!ConfigManager.getInstance().getConfigFile(candidate).exists()) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis();
    }

    private static void renameConfig(String oldName, String newName) {
        ConfigManager mgr = ConfigManager.getInstance();
        try {
            java.lang.reflect.Method renameMethod =
                    mgr.getClass().getMethod("renameConfig", String.class, String.class);
            renameMethod.invoke(mgr, oldName, newName);
            return;
        } catch (Throwable ignored) {}
        try {
            File from = mgr.getConfigFile(oldName);
            File to = mgr.getConfigFile(newName);
            if (from.exists() && !to.exists()) {
                from.renameTo(to);
                if (oldName.equalsIgnoreCase(mgr.getCurrentConfigName())) {
                    mgr.loadConfig(newName);
                }
            }
        } catch (Throwable ignored) {}
    }

    /* ============================================================ */
    /* popup inner class                                            */
    /* ============================================================ */

    /**
     * Small action menu spawned at click-point. Three rows:
     * Поделиться / Переименовать / Удалить. Smooth fade-in on
     * spawn AND smooth fade-out when dismissed (outside-click,
     * action picked, or sister popup opened on a different row).
     * Per-item hover is animated through its own DecelerateAnimation
     * so the highlight slides instead of snapping under the cursor.
     */
    private static final class KebabPopup {
        private static final float ITEM_H = 16.0F;
        private static final float WIDTH = 100.0F;
        private static final float TEXT_SIZE = 6.5F;
        private static final String[] ITEMS = {"kebab.share", "kebab.rename", "kebab.delete"};

        private final String configName;
        private final float anchorX;
        private final float anchorY;
        private final ConfigsViewComponent owner;
        private final Animation fade;
        private final Animation[] itemHover;
        private boolean closing = false;

        KebabPopup(String configName, float anchorX, float anchorY, ConfigsViewComponent owner) {
            this.configName = configName;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.owner = owner;
            this.fade = new DecelerateAnimation().setMs(140).setValue(1);
            this.fade.setDirectionAndFinish(Direction.BACKWARDS);
            this.fade.setDirection(Direction.FORWARDS);
            this.itemHover = new Animation[ITEMS.length];
            for (int i = 0; i < ITEMS.length; i++) {
                itemHover[i] = new DecelerateAnimation().setMs(140).setValue(1);
                itemHover[i].setDirectionAndFinish(Direction.BACKWARDS);
            }
        }

        /** Begin the fade-out tween. The popup keeps rendering until
         *  {@link #isFullyClosed()} flips true so its highlight rows
         *  visibly trail off instead of disappearing in one frame. */
        void close() {
            this.closing = true;
            this.fade.setDirection(Direction.BACKWARDS);
            for (Animation anim : itemHover) {
                anim.setDirection(Direction.BACKWARDS);
            }
        }

        /** True while the popup is opening or fully open - i.e. it
         *  should still receive clicks and block menu drags. Once
         *  {@link #close()} is called this flips false even before
         *  the fade-out finishes, so the next click can reach the
         *  rows beneath. */
        boolean isInteractive() {
            return !closing;
        }

        boolean isFullyClosed() {
            return closing && fade.getOutputFloat() <= 0.001F;
        }

        void render(DrawContext context, int mouseX, int mouseY, float menuFadeAlpha) {
            MatrixStack matrix = context.getMatrices();
            float a = fade.getOutputFloat() * menuFadeAlpha;
            if (a <= 0.001F) return;

            // Layout: popup hangs DOWN-LEFT from the kebab so its
            // upper-right corner lines up with the dots. If it would
            // overflow the menu's bottom edge, anchor it ABOVE the
            // kebab instead.
            float popupX = anchorX - WIDTH;
            float popupY = anchorY + 4.0F;
            float popupH = ITEMS.length * ITEM_H;
            float menuBottom = owner.y + owner.height - 8.0F;
            if (popupY + popupH > menuBottom) {
                popupY = anchorY - popupH - 12.0F;
            }
            float menuLeft = owner.x + 6.0F;
            if (popupX < menuLeft) popupX = menuLeft;

            int fill = MenuStyle.mix(MenuStyle.PANEL_CONTENT, 0x000000FF, 0.10F);
            rectangle.render(ShapeProperties.create(matrix, popupX, popupY, WIDTH, popupH)
                    .round(5.0F).softness(1.0F).thickness(1.2F)
                    .outlineColor(MenuStyle.withAlpha(MenuStyle.BORDER, a))
                    .color(MenuStyle.withAlpha(fill, a * 0.97F))
                    .build());

            for (int i = 0; i < ITEMS.length; i++) {
                float iy = popupY + i * ITEM_H;
                // Only register hover while the popup is open;
                // closing popups should drain their highlight to 0
                // alongside the panel fade so the row glow doesn't
                // outlive the panel.
                boolean hover = !closing
                        && MathUtil.isHovered(mouseX, mouseY, popupX, iy, WIDTH, ITEM_H);
                itemHover[i].setDirection(hover ? Direction.FORWARDS : Direction.BACKWARDS);
                float hoverAlpha = itemHover[i].getOutputFloat();

                if (hoverAlpha > 0.001F) {
                    int hoverFill = MenuStyle.mix(MenuStyle.CARD_OPTIONS, MenuStyle.PANEL_CHIP, 0.35F);
                    rectangle.render(ShapeProperties.create(matrix, popupX, iy, WIDTH, ITEM_H)
                            .round(3.0F)
                            .color(MenuStyle.withAlpha(hoverFill, a * 0.85F * hoverAlpha))
                            .build());
                }
                int baseTextColor = MenuStyle.TEXT_PRIMARY;
                int textColor = i == 2
                        ? MenuStyle.mix(baseTextColor, 0xFFE05050, hoverAlpha)
                        : baseTextColor;
                MsdfRenderer.renderText(
                        MsdfFonts.bold(), Lang.t(ITEMS[i]), TEXT_SIZE,
                        MenuStyle.withAlpha(textColor, a),
                        matrix.peek().getPositionMatrix(),
                        popupX + 10.0F,
                        MenuStyle.centerMsdfTextY(TEXT_SIZE, iy, ITEM_H),
                        0.0F
                );
            }
        }

        boolean handleClick(double mouseX, double mouseY) {
            float popupX = anchorX - WIDTH;
            float popupY = anchorY + 4.0F;
            float popupH = ITEMS.length * ITEM_H;
            float menuBottom = owner.y + owner.height - 8.0F;
            if (popupY + popupH > menuBottom) popupY = anchorY - popupH - 12.0F;
            float menuLeft = owner.x + 6.0F;
            if (popupX < menuLeft) popupX = menuLeft;

            if (!MathUtil.isHovered(mouseX, mouseY, popupX, popupY, WIDTH, popupH)) return false;
            int idx = (int) ((mouseY - popupY) / ITEM_H);
            if (idx < 0 || idx >= ITEMS.length) return false;

            switch (idx) {
                case 0 -> MenuScreen.INSTANCE.openConfigShareModal(configName);
                case 1 -> MenuScreen.INSTANCE.openConfigRenameModal(configName, owner::refreshMetadataCaches);
                case 2 -> {
                    ConfigManager mgr = ConfigManager.getInstance();
                    // Default is now a regular file - the manager
                    // recreates it on first save - so it's safe to
                    // delete from the UI too. Active-config delete
                    // is also fine: deleteConfig auto-switches to
                    // the next available config.
                    mgr.deleteConfig(configName);
                    owner.refreshMetadataCaches();
                }
            }
            return true;
        }
    }
}
