package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeepslateESP
 *
 * Highlights deepslate blocks that are NOT naturally generated.
 * Heuristic: natural deepslate has axis = Y. If AXIS is X or Z, we highlight it.
 *
 * Performance:
 * - Background worker scans chunks to avoid main-thread stalls.
 * - Reacts to BlockUpdateEvent to keep cache accurate.
 * - Rebuilds a sorted render list only when needed.
 */
public class DeepslateESP extends Module {
    // Settings
    private final Setting<SettingColor> espColor = settings.getDefaultGroup().add(new ColorSetting.Builder()
        .name("color")
        .description("ESP color (lines alpha used; fill alpha is softer).")
        .defaultValue(new SettingColor(255, 80, 80, 200))
        .build()
    );

    // State
    private final Map<Long, Map<BlockPos, Boolean>> chunkToFlaggedPositions = new ConcurrentHashMap<>(); // pos -> flagged
    private final List<BlockPos> toRender = Collections.synchronizedList(new ArrayList<>());
    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean renderListDirty = true;

    public DeepslateESP() {
        super(NoraTweaks.CATEGORY, "deepslate-esp", "Highlights deepslate with non-Y axis (likely player-placed).");
    }

    @Override
    public void onActivate() {
        chunkToFlaggedPositions.clear();
        toRender.clear();
        renderListDirty = true;

        for (Chunk c : Utils.chunks()) {
            if (c instanceof WorldChunk wc) scanChunkAsync(wc);
        }
    }

    @Override
    public void onDeactivate() {
        chunkToFlaggedPositions.clear();
        toRender.clear();
        renderListDirty = false;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        scanChunkAsync(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!isActive()) return;

        int bx = event.pos.getX();
        int by = event.pos.getY();
        int bz = event.pos.getZ();
        long key = ChunkPos.toLong(bx >> 4, bz >> 4);

        var oldState = event.oldState;
        var newState = event.newState;

        boolean wasDs = oldState.getBlock() == Blocks.DEEPSLATE;
        boolean isDs = newState.getBlock() == Blocks.DEEPSLATE;

        worker.submit(() -> {
            Map<BlockPos, Boolean> map = chunkToFlaggedPositions.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            BlockPos pos = new BlockPos(bx, by, bz);

            if (isDs) {
                Boolean flagged = isNonNaturalDeepslate(newState);
                if (flagged != null && flagged) map.put(pos, Boolean.TRUE); else map.remove(pos);
            } else if (wasDs) {
                map.remove(pos);
            }

            renderListDirty = true;
        });
    }

    private void scanChunkAsync(WorldChunk chunk) {
        worker.submit(() -> scanChunk(chunk));
    }

    private void scanChunk(WorldChunk chunk) {
        if (!isActive() || mc.world == null) return;

        long key = chunk.getPos().toLong();
        Map<BlockPos, Boolean> map = new ConcurrentHashMap<>();

        int startX = chunk.getPos().getStartX();
        int endX = chunk.getPos().getEndX();
        int startZ = chunk.getPos().getStartZ();
        int endZ = chunk.getPos().getEndZ();

        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                int top = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x - startX, z - startZ);
                for (int y = mc.world.getBottomY(); y < top; y++) {
                    scanPos.set(x, y, z);
                    BlockState state = chunk.getBlockState(scanPos);
                    if (state.getBlock() == Blocks.DEEPSLATE) {
                        Boolean flagged = isNonNaturalDeepslate(state);
                        if (flagged != null && flagged) map.put(scanPos.toImmutable(), Boolean.TRUE);
                    }
                }
            }
        }

        if (!map.isEmpty()) {
            chunkToFlaggedPositions.put(key, map);
            renderListDirty = true;
        }
    }

    private Boolean isNonNaturalDeepslate(BlockState state) {
        if (!state.contains(Properties.AXIS)) return null;
        return state.get(Properties.AXIS) != net.minecraft.util.math.Direction.Axis.Y;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        if (renderListDirty) rebuildRenderList();

        Color lc = new Color(espColor.get());
        Color sc = new Color(espColor.get().r, espColor.get().g, espColor.get().b, Math.max(25, espColor.get().a / 4));

        for (int i = 0; i < toRender.size(); i++) {
            BlockPos pos = toRender.get(i);
            double x1 = pos.getX();
            double y1 = pos.getY();
            double z1 = pos.getZ();
            double x2 = x1 + 1;
            double y2 = y1 + 1;
            double z2 = z1 + 1;

            event.renderer.box(x1, y1, z1, x2, y2, z2, sc, lc, ShapeMode.Both, 0);
        }
    }

    private void rebuildRenderList() {
        renderListDirty = false;
        toRender.clear();

        for (Map<BlockPos, Boolean> map : chunkToFlaggedPositions.values()) {
            toRender.addAll(map.keySet());
        }

    }
}


