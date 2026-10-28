package dev.anubis.addon.modules;

import dev.anubis.addon.AnubisAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uçarken geçtiğin her chunk'ı oyuncu yapımı şeylere göre puanlar (depolama, fırın, yatak,
 * tabela, seçilen indicator bloklar) ve eşiği geçenleri işaretler. Bulunanlar modül kapanana
 * veya boyut değişene kadar hatırlanır.
 */
public class BaseFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> minScore = sgGeneral.add(new IntSetting.Builder()
        .name("min-score").description("Score a chunk needs to be flagged as a base.")
        .defaultValue(8).min(1).sliderRange(1, 60).build());
    private final Setting<Integer> storageWeight = sgGeneral.add(new IntSetting.Builder()
        .name("storage-weight").description("Points per chest / barrel / trapped chest.")
        .defaultValue(1).min(0).sliderMax(10).build());
    private final Setting<Integer> shulkerWeight = sgGeneral.add(new IntSetting.Builder()
        .name("shulker-weight").description("Points per shulker box or ender chest.")
        .defaultValue(3).min(0).sliderMax(10).build());
    private final Setting<Integer> utilityWeight = sgGeneral.add(new IntSetting.Builder()
        .name("utility-weight").description("Points per furnace / hopper / dispenser / bed / sign / brewing stand.")
        .defaultValue(1).min(0).sliderMax(10).build());
    private final Setting<Integer> indicatorWeight = sgGeneral.add(new IntSetting.Builder()
        .name("indicator-weight").description("Points per indicator block (list below).")
        .defaultValue(2).min(0).sliderMax(10).build());
    private final Setting<List<Block>> indicatorBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("indicator-blocks").description("Blocks that rarely generate naturally.")
        .defaultValue(Blocks.ENCHANTING_TABLE, Blocks.BEACON, Blocks.ANVIL, Blocks.CRAFTING_TABLE,
            Blocks.NETHERITE_BLOCK, Blocks.DIAMOND_BLOCK)
        .build());
    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify").description("Prints coordinates when a new base is found.")
        .defaultValue(true).build());

    private final Setting<Integer> minY = sgFilter.add(new IntSetting.Builder()
        .name("min-y").description("Bu yüksekliğin altındaki depolamaları puanlamaya katma.")
        .defaultValue(-64).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Integer> maxY = sgFilter.add(new IntSetting.Builder()
        .name("max-y").description("Bu yüksekliğin üstündeki depolamaları puanlamaya katma.")
        .defaultValue(320).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Integer> ignoreSpawn = sgFilter.add(new IntSetting.Builder()
        .name("ignore-spawn-radius").description("Ignore chunks closer than this many blocks to 0,0 (0 = off).")
        .defaultValue(0).min(0).sliderMax(100000).build());
    private final Setting<Integer> ignoreNearPlayer = sgFilter.add(new IntSetting.Builder()
        .name("ignore-near-player").description("Ignore chunks within this many blocks of you when scanned (0 = off).")
        .defaultValue(0).min(0).sliderMax(512).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").defaultValue(new SettingColor(255, 60, 60, 40)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(255, 60, 60, 255)).build());
    private final Setting<Boolean> fullHeight = sgRender.add(new BoolSetting.Builder()
        .name("full-height").description("Draw the whole chunk column instead of a flat tile at your height.")
        .defaultValue(false).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw lines to flagged chunks.").defaultValue(true).build());
    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color").defaultValue(new SettingColor(255, 200, 0, 200)).visible(tracers::get).build());

    private final Map<Long, Integer> scores = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    private Object dimension;

    public BaseFinder() {
        super(AnubisAddon.CATEGORY, "base-finder", "Flags chunks that contain a lot of player-made blocks and storage.");
    }

    @Override
    public void onActivate() {
        synchronized (scores) {
            scores.clear();
            announced.clear();
        }
        dimension = mc.world != null ? mc.world.getRegistryKey() : null;
    }

    @Override
    public void onDeactivate() {
        synchronized (scores) {
            scores.clear();
            announced.clear();
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.world == null) return;
        if (dimension != mc.world.getRegistryKey()) {
            synchronized (scores) {
                scores.clear();
                announced.clear();
            }
            dimension = mc.world.getRegistryKey();
        }
        scan(event.chunk());
    }

    private void scan(WorldChunk chunk) {
        ChunkPos cp = chunk.getPos();
        int cx = cp.getStartX() + 8;
        int cz = cp.getStartZ() + 8;

        if (ignoreSpawn.get() > 0 && Math.abs(cx) < ignoreSpawn.get() && Math.abs(cz) < ignoreSpawn.get()) return;
        if (ignoreNearPlayer.get() > 0 && mc.player != null) {
            double dx = mc.player.getX() - cx, dz = mc.player.getZ() - cz;
            if (dx * dx + dz * dz < (double) ignoreNearPlayer.get() * ignoreNearPlayer.get()) return;
        }

        int score = 0;

        List<BlockEntity> entities;
        try {
            entities = new ArrayList<>(chunk.getBlockEntities().values());
        } catch (Exception e) {
            return;
        }
        for (BlockEntity be : entities) {
            int by = be.getPos().getY();
            if (by < minY.get() || by > maxY.get()) continue;
            if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) score += storageWeight.get();
            else if (be instanceof ShulkerBoxBlockEntity || be instanceof EnderChestBlockEntity) score += shulkerWeight.get();
            else if (be instanceof AbstractFurnaceBlockEntity || be instanceof HopperBlockEntity
                || be instanceof DispenserBlockEntity || be instanceof BedBlockEntity
                || be instanceof SignBlockEntity || be instanceof BrewingStandBlockEntity) score += utilityWeight.get();
        }

        List<Block> wanted = indicatorBlocks.get();
        if (!wanted.isEmpty() && indicatorWeight.get() > 0) {
            Set<Block> set = new HashSet<>(wanted);
            ChunkSection[] sections = chunk.getSectionArray();
            for (ChunkSection section : sections) {
                if (section == null || section.isEmpty()) continue;
                if (!section.hasAny(state -> set.contains(state.getBlock()))) continue;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++)
                            if (set.contains(section.getBlockState(x, y, z).getBlock())) score += indicatorWeight.get();
            }
        }

        long key = cp.toLong();
        boolean flagged = score >= minScore.get();
        synchronized (scores) {
            if (flagged) scores.put(key, score);
            else scores.remove(key);
        }

        if (flagged && chat.get()) {
            boolean fresh;
            synchronized (scores) { fresh = announced.add(key); }
            if (fresh) info("Possible base at (highlight)%d, %d(default) - score %d.", cx, cz, score);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        double y1, y2;
        if (fullHeight.get()) {
            y1 = mc.world.getBottomY();
            y2 = mc.world.getTopYInclusive() + 1;
        } else {
            y1 = Math.floor(mc.player.getY());
            y2 = y1 + 0.05;
        }

        Vec3d from = RenderUtils.center;
        List<long[]> snapshot = new ArrayList<>();
        synchronized (scores) {
            for (Map.Entry<Long, Integer> e : scores.entrySet()) snapshot.add(new long[]{e.getKey(), e.getValue()});
        }

        for (long[] entry : snapshot) {
            long key = entry[0];
            double x1 = ChunkPos.getPackedX(key) << 4;
            double z1 = ChunkPos.getPackedZ(key) << 4;
            event.renderer.box(x1, y1, z1, x1 + 16, y2, z1 + 16, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (tracers.get()) {
                event.renderer.line(from.x, from.y, from.z, x1 + 8, (y1 + y2) / 2, z1 + 8, tracerColor.get());
            }
        }
    }

    @Override
    public String getInfoString() {
        synchronized (scores) { return String.valueOf(scores.size()); }
    }
}
