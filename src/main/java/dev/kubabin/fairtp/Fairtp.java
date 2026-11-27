package dev.kubabin.fairtp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Fairtp.MODID)
public class Fairtp {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "fairtp";
    private final RandomTeleportService randomTeleportService = new RandomTeleportService();

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
    public void onServerStopping(ServerStoppingEvent event) {
        randomTeleportService.close();
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
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player.").withColor(0xff0000));
            return 0;
        }
        if (Config.deadzone_radius < 0 || Config.teleport_radius <= Config.deadzone_radius) {
            context.getSource().sendFailure(
                    Component.literal("Random teleport is not configured with a valid radius.").withColor(0xff0000)
            );
            return 0;
        }
        if (!randomTeleportService.requestTeleport(player, level)) {
            context.getSource().sendFailure(
                    Component.literal("A random teleport is already being prepared.").withColor(0xffff00)
            );
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Finding a safe random location..."), false);
        return Command.SINGLE_SUCCESS;
    }
}
