package dev.kubabin.fairtp;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

final class RandomTeleportService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CANDIDATES_PER_BATCH = 24;
    private static final int MAX_CANDIDATE_ATTEMPTS = 384;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "fairtp-randomizer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, TeleportRequest> pending = new ConcurrentHashMap<>();

    boolean requestTeleport(ServerPlayer player, ServerLevel level) {
        TeleportRequest request = new TeleportRequest(player.getUUID(), level, createSnapshot(level));
        if (pending.putIfAbsent(player.getUUID(), request) != null) {
            return false;
        }
        scheduleCandidateBatch(request);
        return true;
    }

    void close() {
        pending.clear();
        executor.shutdownNow();
    }

    private SearchSnapshot createSnapshot(ServerLevel level) {
        BlockPos center = level.getSharedSpawnPos();
        WorldBorder border = level.getWorldBorder();
        return new SearchSnapshot(
                center.getX(),
                center.getZ(),
                Config.deadzone_radius,
                Config.teleport_radius,
                toIntegerInclusiveBound(border.getMinX(), true),
                toIntegerInclusiveBound(border.getMaxX(), false),
                toIntegerInclusiveBound(border.getMinZ(), true),
                toIntegerInclusiveBound(border.getMaxZ(), false)
        );
    }

    private long toIntegerInclusiveBound(double bound, boolean lowerBound) {
        double integral = lowerBound ? Math.ceil(bound) : Math.floor(bound);
        return (long) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, integral));
    }

    private void scheduleCandidateBatch(TeleportRequest request) {
        if (!isCurrent(request)) {
            return;
        }
        CompletableFuture
                .supplyAsync(() -> generateCandidates(request.snapshot), executor)
                .whenComplete((candidates, exception) -> request.level.getServer().execute(() -> {
                    if (!isCurrent(request)) {
                        return;
                    }
                    if (exception != null) {
                        LOGGER.error("Failed to generate random teleport candidates", exception);
                        finishWithFailure(request);
                        return;
                    }
                    if (candidates.isEmpty()) {
                        finishWithFailure(request);
                        return;
                    }
                    inspectNextCandidate(request, candidates, 0);
                }));
    }

    private List<BlockPos> generateCandidates(SearchSnapshot snapshot) {
        List<BlockPos> candidates = new ArrayList<>(CANDIDATES_PER_BATCH);
        long minX = Math.max(snapshot.borderMinX(), snapshot.centerX() - (long) snapshot.outerRadius());
        long maxX = Math.min(snapshot.borderMaxX(), snapshot.centerX() + (long) snapshot.outerRadius());
        long minZ = Math.max(snapshot.borderMinZ(), snapshot.centerZ() - (long) snapshot.outerRadius());
        long maxZ = Math.min(snapshot.borderMaxZ(), snapshot.centerZ() + (long) snapshot.outerRadius());
        if (minX > maxX || minZ > maxZ) {
            return candidates;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double innerSquared = (double) snapshot.innerRadius() * snapshot.innerRadius();
        double outerSquared = (double) snapshot.outerRadius() * snapshot.outerRadius();
        int searchLimit = CANDIDATES_PER_BATCH * 200;
        for (int attempt = 0; attempt < searchLimit && candidates.size() < CANDIDATES_PER_BATCH; attempt++) {
            int x = (int) random.nextLong(minX, maxX + 1);
            int z = (int) random.nextLong(minZ, maxZ + 1);
            double dx = x - (double) snapshot.centerX();
            double dz = z - (double) snapshot.centerZ();
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared >= innerSquared && distanceSquared <= outerSquared) {
                candidates.add(new BlockPos(x, 0, z));
            }
        }
        return candidates;
    }

    private void inspectNextCandidate(TeleportRequest request, List<BlockPos> candidates, int index) {
        if (!isCurrent(request)) {
            return;
        }
        if (index >= candidates.size()) {
            if (request.attempts.get() >= MAX_CANDIDATE_ATTEMPTS) {
                finishWithFailure(request);
            } else {
                scheduleCandidateBatch(request);
            }
            return;
        }
        if (request.attempts.incrementAndGet() > MAX_CANDIDATE_ATTEMPTS) {
            finishWithFailure(request);
            return;
        }

        BlockPos candidate = candidates.get(index);
        if (!isAllowedChunk(request.level, candidate)) {
            inspectNextCandidate(request, candidates, index + 1);
            return;
        }

        request.level.getChunkSource()
                .getChunkFuture(candidate.getX() >> 4, candidate.getZ() >> 4, ChunkStatus.FULL, true)
                .whenComplete((result, exception) -> request.level.getServer().execute(
                        () -> inspectLoadedCandidate(request, candidates, index, result, exception)
                ));
    }

    private void inspectLoadedCandidate(
            TeleportRequest request,
            List<BlockPos> candidates,
            int index,
            ChunkResult<ChunkAccess> result,
            Throwable exception
    ) {
        if (!isCurrent(request)) {
            return;
        }
        BlockPos candidate = candidates.get(index);
        ChunkAccess chunk = result == null ? null : result.orElse(null);
        if (exception != null || chunk == null) {
            if (exception != null) {
                LOGGER.debug("Could not load random teleport candidate {}", candidate, exception);
            }
            inspectNextCandidate(request, candidates, index + 1);
            return;
        }

        if (!isAllowedChunk(request.level, candidate)) {
            inspectNextCandidate(request, candidates, index + 1);
            return;
        }

        int y = findLandingY(request.level, chunk, candidate.getX(), candidate.getZ());
        if (y == Integer.MIN_VALUE) {
            inspectNextCandidate(request, candidates, index + 1);
            return;
        }

        BlockPos destination = new BlockPos(candidate.getX(), y, candidate.getZ());
        if (!isAllowedChunk(request.level, destination)
                || !isSafeDestination(request.level, destination)
                || !isCenteredPositionWithinBorder(request.level.getWorldBorder(), destination)) {
            inspectNextCandidate(request, candidates, index + 1);
            return;
        }

        ServerPlayer player = request.level.getServer().getPlayerList().getPlayer(request.playerId);
        if (player == null) {
            pending.remove(request.playerId, request);
            return;
        }

        player.teleportTo(
                request.level,
                destination.getX() + 0.5D,
                destination.getY(),
                destination.getZ() + 0.5D,
                player.getYRot(),
                player.getXRot()
        );
        pending.remove(request.playerId, request);
        player.sendSystemMessage(Component.literal("Teleported to a safe random location!").withColor(0x00ff00));
    }

    private boolean isAllowedChunk(ServerLevel level, BlockPos candidate) {
        if (!level.getWorldBorder().isWithinBounds(candidate)) {
            return false;
        }
        IPlayerChunkClaimAPI claim = OpenPACServerAPI.get(level.getServer()).getServerClaimsManager().get(
                level.dimension().location(),
                candidate.getX() >> 4,
                candidate.getZ() >> 4
        );
        return claim == null;
    }

    private boolean isCenteredPositionWithinBorder(WorldBorder border, BlockPos destination) {
        double x = destination.getX() + 0.5D;
        double z = destination.getZ() + 0.5D;
        return x >= border.getMinX() && x <= border.getMaxX()
                && z >= border.getMinZ() && z <= border.getMaxZ();
    }

    private int findLandingY(ServerLevel level, ChunkAccess chunk, int x, int z) {
        if (!level.dimensionType().hasCeiling()) {
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            return isSafeDestination(level, new BlockPos(x, y, z)) ? y : Integer.MIN_VALUE;
        }

        int maximum = Math.min(level.getMaxBuildHeight() - 2, 126);
        int minimum = Math.max(level.getMinBuildHeight() + 1, 1);
        for (int y = maximum; y >= minimum; y--) {
            if (isSafeDestination(level, new BlockPos(x, y, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean isSafeDestination(ServerLevel level, BlockPos destination) {
        if (!level.getWorldBorder().isWithinBounds(destination)
                || destination.getY() <= level.getMinBuildHeight()
                || destination.getY() + 1 >= level.getMaxBuildHeight()) {
            return false;
        }

        BlockPos ground = destination.below();
        if (!level.getBlockState(ground).getFluidState().isEmpty()
                || !level.getBlockState(destination).getFluidState().isEmpty()
                || !level.getBlockState(destination.above()).getFluidState().isEmpty()
                || level.getBlockState(ground).getCollisionShape(level, ground).isEmpty()
                || !level.getBlockState(destination).getCollisionShape(level, destination).isEmpty()
                || !level.getBlockState(destination.above()).getCollisionShape(level, destination.above()).isEmpty()) {
            return false;
        }
        return true;
    }

    private boolean isCurrent(TeleportRequest request) {
        return pending.get(request.playerId) == request;
    }

    private void finishWithFailure(TeleportRequest request) {
        if (!pending.remove(request.playerId, request)) {
            return;
        }
        ServerPlayer player = request.level.getServer().getPlayerList().getPlayer(request.playerId);
        if (player != null) {
            player.sendSystemMessage(
                    Component.literal("Could not find a safe, unclaimed location. Please try again.").withColor(0xff0000)
            );
        }
    }

    private record SearchSnapshot(
            int centerX,
            int centerZ,
            int innerRadius,
            int outerRadius,
            long borderMinX,
            long borderMaxX,
            long borderMinZ,
            long borderMaxZ
    ) {
    }

    private static final class TeleportRequest {
        private final UUID playerId;
        private final ServerLevel level;
        private final SearchSnapshot snapshot;
        private final AtomicInteger attempts = new AtomicInteger();

        private TeleportRequest(UUID playerId, ServerLevel level, SearchSnapshot snapshot) {
            this.playerId = playerId;
            this.level = level;
            this.snapshot = snapshot;
        }
    }
}
