package dev.kubabin.fairtp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Fairtp.MODID)
public class Fairtp {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "fairtp";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_CHUNK_ATTEMPTS = 32;
    private static final int POSITIONS_PER_CHUNK = 8;
    private static final TicketType<UUID> RTP_TICKET = TicketType.create("fairtp_rtp", Comparator.<UUID>naturalOrder());
    private final Set<UUID> pendingTeleports = new HashSet<>();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Fairtp(ModContainer modContainer) {
        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (Fairtp) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }


    private boolean isAvailable(ServerPlayer player, ServerLevel level) {
        return level.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && !player.isRemoved() && player.isAlive() && player.serverLevel() == level;
    }

    private boolean cancelIfUnavailable(ServerPlayer player, ServerLevel level) {
        if (isAvailable(player, level)) {
            return false;
        }
        if (level.getServer().getPlayerList().getPlayer(player.getUUID()) == player) {
            player.sendSystemMessage(Component.literal("Random teleport cancelled because you died or changed dimensions.").withColor(0xff0000));
        }
        return true;
    }

    private boolean isUnclaimed(ServerLevel level, int x, int z) {
        IPlayerChunkClaimAPI claim = OpenPACServerAPI.get(level.getServer()).getServerClaimsManager().get(
                level.dimension().location(), x >> 4, z >> 4);
        return claim == null;
    }

    private boolean isSafe(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (pos.getY() <= level.getMinBuildHeight() || pos.getY() + 1 >= level.getMaxBuildHeight()) {
            return false;
        }
        BlockPos floorPos = pos.below();
        BlockState floor = level.getBlockState(floorPos);
        if (!floor.isCollisionShapeFullBlock(level, floorPos) || !floor.getFluidState().isEmpty()
                || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE)
                || floor.is(Blocks.SOUL_CAMPFIRE) || floor.is(Blocks.CACTUS)
                || floor.is(Blocks.SWEET_BERRY_BUSH) || floor.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()
                || feet.is(BlockTags.FIRE) || head.is(BlockTags.FIRE)
                || feet.is(Blocks.CACTUS) || head.is(Blocks.CACTUS)
                || feet.is(Blocks.SWEET_BERRY_BUSH) || head.is(Blocks.SWEET_BERRY_BUSH)
                || feet.is(Blocks.WITHER_ROSE) || head.is(Blocks.WITHER_ROSE)
                || feet.is(Blocks.POWDER_SNOW) || head.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        AABB destination = player.getDimensions(Pose.STANDING).makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return level.getWorldBorder().isWithinBounds(destination) && level.noCollision(player, destination);
    }

    private BlockPos findSafePosition(ServerLevel level, ServerPlayer player, SearchArea area, int x, int z) {
        for (int i = 0; i < POSITIONS_PER_CHUNK; i++) {
            int sampleX = i == 0 ? x : (x & ~15) + ThreadLocalRandom.current().nextInt(16);
            int sampleZ = i == 0 ? z : (z & ~15) + ThreadLocalRandom.current().nextInt(16);
            if (!area.contains(sampleX, sampleZ)
                    || !level.getWorldBorder().isWithinBounds(new BlockPos(sampleX, level.getMinBuildHeight(), sampleZ))) {
                continue;
            }
            if (level.dimensionType().hasCeiling()) {
                int top = Math.min(level.getMaxBuildHeight() - 2,
                        level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 2);
                for (int y = level.getMinBuildHeight() + 1; y <= top; y++) {
                    BlockPos pos = new BlockPos(sampleX, y, sampleZ);
                    if (isSafe(level, player, pos)) {
                        return pos;
                    }
                }
            } else {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX, sampleZ);
                BlockPos pos = new BlockPos(sampleX, y, sampleZ);
                if (isSafe(level, player, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private record SearchArea(int centerX, int centerZ, int deadzone,
                              int minX, int maxX, int minZ, int maxZ) {
        boolean contains(int x, int z) {
            long dx = (long) x - centerX;
            long dz = (long) z - centerZ;
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ
                    && dx * dx + dz * dz >= (long) deadzone * deadzone;
        }
    }

    private void searchAndTeleport(ServerPlayer player, ServerLevel level, SearchArea area, ServerChunkCache chunks) {
        var server = level.getServer();
        try {
            for (int attempt = 0; attempt < MAX_CHUNK_ATTEMPTS; attempt++) {
                int x = (int) ThreadLocalRandom.current().nextLong(area.minX(), (long) area.maxX() + 1);
                int z = (int) ThreadLocalRandom.current().nextLong(area.minZ(), (long) area.maxZ() + 1);
                if (!area.contains(x, z)) {
                    continue;
                }
                ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);
                boolean canLoad = server.submit(() -> {
                    if (!isAvailable(player, level)
                            || !level.getWorldBorder().isWithinBounds(new BlockPos(x, level.getMinBuildHeight(), z))
                            || !isUnclaimed(level, x, z)) {
                        return false;
                    }
                    chunks.addRegionTicket(RTP_TICKET, chunkPos, 0, player.getUUID());
                    return true;
                }).join();
                if (!canLoad) {
                    if (server.submit(() -> cancelIfUnavailable(player, level)).join()) {
                        return;
                    }
                    continue;
                }
                try {
                    ChunkResult<ChunkAccess> result = chunks.getChunkFuture(x >> 4, z >> 4, ChunkStatus.FULL, true).join();
                    if (result.orElse(null) == null) {
                        LOGGER.debug("Failed to load RTP chunk at {}, {}: {}", x >> 4, z >> 4, result.getError());
                        continue;
                    }
                    boolean finished = server.submit(() -> {
                        if (cancelIfUnavailable(player, level)) {
                            return true;
                        }
                        if (!isUnclaimed(level, x, z)) {
                            return false;
                        }
                        BlockPos pos = findSafePosition(level, player, area, x, z);
                        if (pos == null) {
                            return false;
                        }
                        if (!player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                                Set.of(), player.getYRot(), player.getXRot())) {
                            return false;
                        }
                        player.setDeltaMovement(0, 0, 0);
                        player.resetFallDistance();
                        player.sendSystemMessage(Component.literal("Teleported to a random location!").withColor(0x00ff00));
                        return true;
                    }).join();
                    if (finished) {
                        return;
                    }
                } finally {
                    server.execute(() -> chunks.removeRegionTicket(RTP_TICKET, chunkPos, 0, player.getUUID()));
                }
            }
            server.execute(() -> {
                if (isAvailable(player, level)) {
                    player.sendSystemMessage(Component.literal("Failed to find a suitable location. Please try again later.").withColor(0xff0000));
                }
            });
        } catch (RuntimeException e) {
            LOGGER.error("Random teleport search failed for {}", player.getGameProfile().getName(), e);
            server.execute(() -> {
                if (isAvailable(player, level)) {
                    player.sendSystemMessage(Component.literal("Random teleport failed due to an error.").withColor(0xff0000));
                }
            });
        } finally {
            server.execute(() -> pendingTeleports.remove(player.getUUID()));
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event){
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("spawn").executes(
                        this::spawnCommand
                )
        );
        dispatcher.register(
                Commands.literal("stuck")
                        .executes(
                                this::spawnCommand
                        )
        );
        // Almost random teleport command.
        // Teleports to a random location above the ground, but not if it's claimed.
        dispatcher.register(
                Commands.literal("rtp").executes(
                        this::rtpCommand
                )
        );
        dispatcher.register(
                Commands.literal("randomtp").executes(
                        this::rtpCommand
                )
        );
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        pendingTeleports.clear();
    }

    private int spawnCommand(CommandContext<CommandSourceStack> context){
        BlockPos spawnPos = context.getSource().getLevel().getSharedSpawnPos();
        try {
            context.getSource().getPlayerOrException().teleportTo(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Teleported to spawn!").withColor(0x00ff00), false);
        return Command.SINGLE_SUCCESS;
    }
    private int rtpCommand(CommandContext<CommandSourceStack> context){
        ServerLevel level = context.getSource().getLevel();
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (CommandSyntaxException e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player.").withColor(0xff0000));
            return 0;
        }
        if (pendingTeleports.contains(player.getUUID())) {
            context.getSource().sendFailure(Component.literal("A random teleport is already in progress."));
            return 0;
        }
        int radius = Config.teleport_radius;
        int deadzone = Config.deadzone_radius;
        if (radius <= deadzone) {
            context.getSource().sendFailure(Component.literal("Random teleport radius must be larger than the deadzone radius."));
            return 0;
        }
        BlockPos center = level.getSharedSpawnPos();
        WorldBorder border = level.getWorldBorder();
        long minX = Math.max(Math.max((long) center.getX() - radius, (long) Math.floor(border.getMinX())), -29999984L);
        long maxX = Math.min(Math.min((long) center.getX() + radius, (long) Math.ceil(border.getMaxX()) - 1), 29999983L);
        long minZ = Math.max(Math.max((long) center.getZ() - radius, (long) Math.floor(border.getMinZ())), -29999984L);
        long maxZ = Math.min(Math.min((long) center.getZ() + radius, (long) Math.ceil(border.getMaxZ()) - 1), 29999983L);
        if (minX > maxX || minZ > maxZ) {
            context.getSource().sendFailure(Component.literal("The random teleport area is outside the world border."));
            return 0;
        }
        SearchArea area = new SearchArea(center.getX(), center.getZ(), deadzone,
                (int) minX, (int) maxX, (int) minZ, (int) maxZ);
        pendingTeleports.add(player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal("Finding a random location..."), false);
        ServerChunkCache chunks = level.getChunkSource();
        Thread.ofVirtual().name("fairtp-rtp-" + player.getUUID()).start(() -> searchAndTeleport(player, level, area, chunks));
        return Command.SINGLE_SUCCESS;
    }
}
