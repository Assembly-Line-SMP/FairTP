package dev.kubabin.fairtp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.logging.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Fairtp.MODID)
public class Fairtp {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "fairtp";
    private static final Logger LOGGER = Logger.getLogger(MODID);

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


    public BlockPos determineRandomLocation(ServerLevel level, int radius) {
        WorldBorder border = level.getWorldBorder();
        BlockPos centerPos = level.getSharedSpawnPos();
        int deadzone = Config.deadzone_radius;

        while (true) {
            int spread = 2 * radius - 2 * deadzone;
            int x = centerPos.getX() - radius + deadzone + (int) (Math.random() * spread);
            int z = centerPos.getZ() - radius + deadzone + (int) (Math.random() * spread);

            // Check world border
            if (x > border.getMaxX() || x < border.getMinX() || z > border.getMaxZ() || z < border.getMinZ()) {
                radius += 100;
                LOGGER.info("OOB in X/Z. Retrying with increased radius: " + radius);
                continue;
            }

            // Check if the target chunk is claimed
            ChunkDimPos targetChunk = new ChunkDimPos(level.dimension(), x >> 4, z >> 4);
            if (FTBChunksAPI.api().getManager().getChunk(targetChunk) != null) {
                radius += 100;
                LOGGER.info("Chunk is claimed. Retrying with increased radius: " + radius);
                continue;
            }

            ChunkResult<ChunkAccess> chunkResult = level.getChunkSource()
                    .getChunkFuture(x >> 4, z >> 4, ChunkStatus.FULL, true)
                    .join();
            if (chunkResult.orElse(null) == null) {
                radius += 100;
                LOGGER.info("Chunk generation failed. Retrying with increased radius: " + radius);
                continue;
            }
            // Use heightmap for O(1) surface lookup instead of scanning Y manually
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

            if (y <= 0 || y >= level.getMaxBuildHeight()) {
                radius += 100;
                LOGGER.info("OOB in Y. Retrying with increased radius: " + radius);
                continue;
            }

            // Skip fluid surfaces (water/lava)
            //noinspection deprecation
            if (level.getBlockState(new BlockPos(x, y - 1, z)).liquid()) {
                radius += 100;
                LOGGER.info("Surface is liquid. Retrying with increased radius: " + radius);
                continue;
            }

            return new BlockPos(x, y, z);
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
        LiteralCommandNode<CommandSourceStack> rtpNode = dispatcher.register(
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
    private int spawnCommand(CommandContext<CommandSourceStack> context){
        BlockPos spawnPos = context.getSource().getLevel().getSharedSpawnPos();
        try {
            context.getSource().getPlayerOrException().teleportTo(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Teleported to spawn!"), false);
        return Command.SINGLE_SUCCESS;
    }
    private int rtpCommand(CommandContext<CommandSourceStack> context){
        ServerLevel level = context.getSource().getLevel();
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Finding a random location..."), false);
        level.getServer().execute(() -> {
            BlockPos pos = determineRandomLocation(level, Config.teleport_radius);
            player.teleportTo(pos.getX(), pos.getY(), pos.getZ());
            player.sendSystemMessage(Component.literal("Teleported to a random location!"));
        });
        return Command.SINGLE_SUCCESS;
    }
}
