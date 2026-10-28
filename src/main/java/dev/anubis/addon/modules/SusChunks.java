package dev.anubis.addon.modules;

import dev.anubis.addon.AnubisAddon;
import it.unimi.dsi.fastutil.longs.*;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.client.world.ClientWorld;

/**
 * Anubis "Better Sus Chunk" portu.
 *
 * Bitki büyümesi (random tick) sadece oyuncu yakınındayken olur. Büyümesi gecikmiş bitki
 * barındıran chunk tick almamıştır, yani yakında kimse yoktur; o chunk ve çevresi elenir.
 * Elenmeyen ama büyük bir boş bölgenin ortasında, 3+ yandan boş şeritle çevrili chunk
 * şüphelidir: onu bir şey (oyuncu / AFK / base) tick'te tutuyor demektir.
 */
public class SusChunks extends Module {
    private static final long[] NO_KEYS = new long[0];
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> simDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance").description("Server simulation distance (chunks). Match the server.")
        .defaultValue(4).min(2).sliderRange(2, 16).onChanged(v -> dirty = true).build());
    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity").description("Amount of overdue growth around a chunk before it counts as an idle region. Lower = more results.")
        .defaultValue(40).min(1).sliderRange(1, 500).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").defaultValue(new SettingColor(255, 0, 0, 100)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(255, 0, 0, 255)).build());
    private final Setting<Boolean> fullHeight = sgRender.add(new BoolSetting.Builder()
        .name("full-height").description("Draw the whole chunk column instead of a flat tile at your height.")
        .defaultValue(false).build());

    private final Object lock = new Object();
    private final Long2ObjectOpenHashMap<Rec> records = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap clearCoverage = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap support = new Long2IntOpenHashMap();
    private final LongOpenHashSet scanned = new LongOpenHashSet();
    private final LongOpenHashSet pending = new LongOpenHashSet();

    private volatile long[] primary = NO_KEYS;
    private volatile int generation;
    private volatile boolean dirty;
    private Object dimension;
    private int tickCounter;

    private record Rec(boolean clears, int radius, int weight) {}

    public SusChunks() {
        super(AnubisAddon.CATEGORY, "sus-chunks", "Marks chunks that stay ticked inside regions where nobody has been (base / AFK finder).");
    }

    @Override
    public void onActivate() {
        resetState();
        dimension = mc.world != null ? mc.world.getRegistryKey() : null;
        rescanLoaded();
    }

    @Override
    public void onDeactivate() {
        resetState();
        dimension = null;
    }

    private void resetState() {
        synchronized (lock) {
            generation++;
            records.clear();
            clearCoverage.clear();
            support.clear();
            scanned.clear();
            pending.clear();
        }
        primary = NO_KEYS;
        tickCounter = 0;
    }

    private void rescanLoaded() {
        if (mc.world == null || mc.player == null) return;
        resetState();
        int radius = mc.options.getViewDistance().getValue() + 3;
        int px = mc.player.getChunkPos().x, pz = mc.player.getChunkPos().z;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                WorldChunk c = mc.world.getChunkManager().getWorldChunk(x, z);
                if (c != null) schedule(ChunkPos.toLong(x, z));
            }
        }
    }

    private void schedule(long key) {
        synchronized (lock) {
            if (!pending.add(key)) return;
        }
        int expected = generation;
        MeteorExecutor.execute(() -> runScan(key, expected));
    }

    private void runScan(long key, int expected) {
        synchronized (lock) { pending.remove(key); }
        if (expected != generation || !isActive()) return;
        ClientWorld world = mc.world;
        if (world == null) return;
        WorldChunk chunk = world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
        if (chunk == null) return;

        int weight;
        try {
            weight = scanChunk(world, chunk);
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (expected != generation) return;
            Rec old = removeRecord(key);
            int oldWeight = old == null ? 0 : old.weight();
            boolean clears = weight > 0 || (old != null && old.clears());
            Rec merged = new Rec(clears, simDistance.get(), Math.max(weight, oldWeight));
            scanned.add(key);
            records.put(key, merged);
            if (merged.clears()) applyRecord(key, merged, 1);
        }
    }

    private void applyRecord(long key, Rec r, int sign) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        int reach = 2 * r.radius() + 2;
        for (int dx = -r.radius(); dx <= r.radius(); dx++)
            for (int dz = -r.radius(); dz <= r.radius(); dz++)
                add(clearCoverage, ChunkPos.toLong(cx + dx, cz + dz), sign);
        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++)
                add(support, ChunkPos.toLong(cx + dx, cz + dz), sign * r.weight());
    }

    private static void add(Long2IntOpenHashMap map, long key, int delta) {
        int v = map.get(key) + delta;
        if (v <= 0) map.remove(key);
        else map.put(key, v);
    }

    /** Çağıran lock tutmalı. */
    private Rec removeRecord(long key) {
        Rec old = records.remove(key);
        scanned.remove(key);
        if (old != null && old.clears()) applyRecord(key, old, -1);
        return old;
    }

    // ---------------------------------------------------------------- scanning

    private int scanChunk(ClientWorld world, WorldChunk chunk) {
        Reader reader = new Reader(world);
        int minX = chunk.getPos().getStartX();
        int minZ = chunk.getPos().getStartZ();
        int count = 0;
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            if (!section.hasAny(SusChunks::isTracked)) continue;
            int baseY = chunk.getBottomY() + (i << 4);
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (isTracked(state) && owed(reader, minX + x, baseY + y, minZ + z, state)) count++;
                    }
        }
        return count;
    }

    private static boolean isTracked(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.KELP || b == Blocks.CAVE_VINES || b == Blocks.WEEPING_VINES || b == Blocks.BAMBOO
            || b == Blocks.SUGAR_CANE || b == Blocks.CACTUS || b == Blocks.COCOA || b == Blocks.NETHER_WART
            || b == Blocks.SWEET_BERRY_BUSH || b instanceof CropBlock;
    }

    /** Chunk tick alıyor olsaydı bu bitki şimdiye kadar büyümüş olmalıydı. */
    private static boolean owed(Reader r, int x, int y, int z, BlockState st) {
        Block b = st.getBlock();
        if (b == Blocks.KELP)
            return st.get(Properties.AGE_25) < 25 && r.state(x, y + 1, z).isOf(Blocks.WATER);
        if (b == Blocks.CAVE_VINES || b == Blocks.WEEPING_VINES)
            return st.get(Properties.AGE_25) < 25 && r.state(x, y - 1, z).isAir();
        if (b == Blocks.BAMBOO)
            return st.get(Properties.STAGE) == 0 && r.state(x, y + 1, z).isAir()
                && bambooBelow(r, x, y, z) + 1 < 16 && r.light(x, y + 1, z) >= 9;
        if (b == Blocks.SUGAR_CANE)
            return r.state(x, y + 1, z).isAir() && column(r, x, y, z, Blocks.SUGAR_CANE, 3) < 3;
        if (b == Blocks.CACTUS)
            return r.state(x, y + 1, z).isAir()
                && (st.get(Properties.AGE_15) < 15 || column(r, x, y, z, Blocks.CACTUS, 3) < 3);
        if (b == Blocks.COCOA)
            return st.get(Properties.AGE_2) < 2;
        if (b == Blocks.NETHER_WART)
            return st.get(Properties.AGE_3) < 3;
        if (b == Blocks.SWEET_BERRY_BUSH)
            return st.get(Properties.AGE_3) < 3 && r.light(x, y + 1, z) >= 9;
        if (b instanceof CropBlock crop)
            return !crop.isMature(st) && r.light(x, y, z) >= 9;
        return false;
    }

    private static int column(Reader r, int x, int y, int z, Block target, int limit) {
        int len = 1;
        while (len < limit && r.state(x, y - len, z).isOf(target)) len++;
        return len;
    }

    private static int bambooBelow(Reader r, int x, int y, int z) {
        int n = 0;
        while (n < 16 && r.state(x, y - n - 1, z).isOf(Blocks.BAMBOO)) n++;
        return n;
    }

    private static final class Reader {
        private final ClientWorld world;
        private final BlockPos.Mutable pos = new BlockPos.Mutable();
        private WorldChunk chunk;

        Reader(ClientWorld world) { this.world = world; }

        BlockState state(int x, int y, int z) {
            if (world.isOutOfHeightLimit(y)) return Blocks.BEDROCK.getDefaultState();
            int cx = x >> 4, cz = z >> 4;
            if (chunk == null || chunk.getPos().x != cx || chunk.getPos().z != cz) {
                WorldChunk c = world.getChunkManager().getWorldChunk(cx, cz);
                if (c == null) return Blocks.BEDROCK.getDefaultState();
                chunk = c;
            }
            return chunk.getBlockState(pos.set(x, y, z));
        }

        int light(int x, int y, int z) {
            pos.set(x, y, z);
            return Math.max(world.getLightLevel(LightType.BLOCK, pos), world.getLightLevel(LightType.SKY, pos));
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        schedule(event.chunk().getPos().toLong());
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetState();
        dimension = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (dimension != mc.world.getRegistryKey()) {
            dimension = mc.world.getRegistryKey();
            rescanLoaded();
        }
        if (dirty) {
            dirty = false;
            rescanLoaded();
        }

        pruneDistant();

        if (++tickCounter >= 20) {
            tickCounter = 0;
            rebuildDisplay();
        }
    }

    private void pruneDistant() {
        int reach = mc.options.getViewDistance().getValue() + 2 * simDistance.get() + 2;
        int px = mc.player.getChunkPos().x, pz = mc.player.getChunkPos().z;
        synchronized (lock) {
            LongArrayList far = new LongArrayList();
            for (long key : records.keySet()) {
                if (Math.abs(ChunkPos.getPackedX(key) - px) > reach || Math.abs(ChunkPos.getPackedZ(key) - pz) > reach)
                    far.add(key);
            }
            for (int i = 0; i < far.size(); i++) removeRecord(far.getLong(i));
        }
    }

    private void rebuildDisplay() {
        int threshold = sensitivity.get();
        int reach = 2 * simDistance.get() + 2;
        LongArrayList next = new LongArrayList();
        synchronized (lock) {
            LongIterator it = scanned.iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
                if (mc.world.getChunkManager().getWorldChunk(cx, cz) == null) continue;
                if (clearCoverage.containsKey(key)) continue;
                if (support.get(key) < threshold) continue;
                if (coveredSides(cx, cz, reach) < 3) continue;
                next.add(key);
            }
        }
        primary = next.toLongArray();
    }

    private int coveredSides(int cx, int cz, int reach) {
        int sides = 0;
        for (int[] s : SIDES) if (stripCleared(cx, cz, s[0], s[1], reach)) sides++;
        return sides;
    }

    private boolean stripCleared(int cx, int cz, int dx, int dz, int reach) {
        for (int depth = 1; depth <= reach; depth++)
            for (int lateral = -reach; lateral <= reach; lateral++)
                if (clearCoverage.containsKey(ChunkPos.toLong(cx + dx * depth + dz * lateral, cz + dz * depth + dx * lateral)))
                    return true;
        return false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        long[] keys = primary;
        if (keys.length == 0) return;

        double y1, y2;
        if (fullHeight.get()) {
            y1 = mc.world.getBottomY();
            y2 = mc.world.getTopYInclusive() + 1;
        } else {
            y1 = Math.floor(mc.player.getY());
            y2 = y1 + 0.05;
        }

        for (long key : keys) {
            double x1 = ChunkPos.getPackedX(key) << 4;
            double z1 = ChunkPos.getPackedZ(key) << 4;
            event.renderer.box(x1, y1, z1, x1 + 16, y2, z1 + 16, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        return String.valueOf(primary.length);
    }
}
