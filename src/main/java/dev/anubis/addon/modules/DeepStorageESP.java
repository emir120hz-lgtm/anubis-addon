package dev.anubis.addon.modules;

import dev.anubis.addon.AnubisAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Depolama ESP'si: sandık, barrel, shulker, ender chest, fırın, hopper vb. Y aralığı
 * ayarlanabilir; varsayılan -64 (dünyanın dibi) ile 320 arası.
 */
public class DeepStorageESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y").description("Bu yüksekliğin altındakileri gösterme.")
        .defaultValue(-64).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Bu yüksekliğin üstündekileri gösterme.")
        .defaultValue(320).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Integer> chunkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-radius").description("Taranacak chunk yarıçapı (render distance'ı aşamaz).")
        .defaultValue(32).min(1).sliderRange(1, 64).build());
    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval").description("Kaç tick'te bir tarasın.")
        .defaultValue(20).min(1).sliderRange(5, 100).build());
    private final Setting<Integer> maxRendered = sgGeneral.add(new IntSetting.Builder()
        .name("max-rendered").description("Aynı anda çizilecek en fazla kutu.")
        .defaultValue(2000).min(1).sliderRange(100, 10000).build());

    private final Setting<Boolean> chests = sgGeneral.add(new BoolSetting.Builder().name("chests").description("Sandık / trapped chest / barrel.").defaultValue(true).build());
    private final Setting<Boolean> shulkers = sgGeneral.add(new BoolSetting.Builder().name("shulkers").description("Shulker box.").defaultValue(true).build());
    private final Setting<Boolean> enderChests = sgGeneral.add(new BoolSetting.Builder().name("ender-chests").description("Ender chest.").defaultValue(true).build());
    private final Setting<Boolean> furnaces = sgGeneral.add(new BoolSetting.Builder().name("furnaces").description("Fırın / blast furnace / smoker.").defaultValue(false).build());
    private final Setting<Boolean> hoppers = sgGeneral.add(new BoolSetting.Builder().name("hoppers").description("Hopper / dispenser / dropper.").defaultValue(false).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> chestColor = sgRender.add(new ColorSetting.Builder()
        .name("chest-color").defaultValue(new SettingColor(255, 160, 0, 255)).build());
    private final Setting<SettingColor> shulkerColor = sgRender.add(new ColorSetting.Builder()
        .name("shulker-color").defaultValue(new SettingColor(200, 80, 255, 255)).build());
    private final Setting<SettingColor> enderColor = sgRender.add(new ColorSetting.Builder()
        .name("ender-color").defaultValue(new SettingColor(0, 255, 200, 255)).build());
    private final Setting<SettingColor> otherColor = sgRender.add(new ColorSetting.Builder()
        .name("other-color").defaultValue(new SettingColor(180, 180, 180, 255)).build());
    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha").defaultValue(40).range(0, 255).sliderRange(0, 255).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Bulunanlara çizgi çek.").defaultValue(false).build());

    private record Hit(BlockPos pos, int kind) {}

    private volatile List<Hit> hits = List.of();
    private int timer;

    public DeepStorageESP() {
        super(AnubisAddon.CATEGORY, "deep-storage-esp", "Depolamaları seçtiğin Y aralığında (varsayılan -64'ten yukarı) gösterir.");
    }

    @Override
    public void onDeactivate() {
        hits = List.of();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (++timer < interval.get()) return;
        timer = 0;

        int radius = Math.min(chunkRadius.get(), mc.options.getViewDistance().getValue());
        ChunkPos pc = mc.player.getChunkPos();
        int lo = Math.min(minY.get(), maxY.get());
        int hi = Math.max(minY.get(), maxY.get());
        List<Hit> list = new ArrayList<>();

        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(pc.x + dx, pc.z + dz);
                if (chunk == null) continue;
                List<BlockEntity> bes;
                try {
                    bes = new ArrayList<>(chunk.getBlockEntities().values());
                } catch (Exception e) {
                    continue;
                }
                for (BlockEntity be : bes) {
                    BlockPos p = be.getPos();
                    if (p.getY() < lo || p.getY() > hi) continue;
                    int kind = classify(be);
                    if (kind < 0) continue;
                    list.add(new Hit(p.toImmutable(), kind));
                    if (list.size() >= maxRendered.get()) break outer;
                }
            }
        }
        hits = list;
    }

    /** 0 = sandık/barrel, 1 = shulker, 2 = ender chest, 3 = diğer, -1 = gösterme. */
    private int classify(BlockEntity be) {
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) return chests.get() ? 0 : -1;
        if (be instanceof ShulkerBoxBlockEntity) return shulkers.get() ? 1 : -1;
        if (be instanceof EnderChestBlockEntity) return enderChests.get() ? 2 : -1;
        if (be instanceof AbstractFurnaceBlockEntity) return furnaces.get() ? 3 : -1;
        if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity) return hoppers.get() ? 3 : -1;
        return -1;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        Vec3d from = RenderUtils.center;
        for (Hit h : hits) {
            SettingColor line = switch (h.kind()) {
                case 0 -> chestColor.get();
                case 1 -> shulkerColor.get();
                case 2 -> enderColor.get();
                default -> otherColor.get();
            };
            SettingColor side = new SettingColor(line.r, line.g, line.b, fillAlpha.get());
            BlockPos p = h.pos();
            event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, side, line, shapeMode.get(), 0);
            if (tracers.get()) {
                event.renderer.line(from.x, from.y, from.z, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, line);
            }
        }
    }

    @Override
    public String getInfoString() {
        return String.valueOf(hits.size());
    }
}
