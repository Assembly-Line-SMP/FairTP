package dev.kubabin.fairtp;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
@EventBusSubscriber(modid = Fairtp.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.IntValue DEADZONE_RADIUS = BUILDER
            .comment("The radius of the deadzone around the spawn point. Players will not be teleported to locations within this radius.")
            .defineInRange("deadzone_radius", 200, 0, Integer.MAX_VALUE);
    private static final ModConfigSpec.IntValue TELEPORT_RADIUS = BUILDER
            .comment("The radius around the spawn point to teleport players to. Players will be teleported to a random location within this radius.")
            .defineInRange("teleport_radius", 1000, 0, Integer.MAX_VALUE);
    static final ModConfigSpec SPEC = BUILDER.build();
    public static int deadzone_radius;
    public static int teleport_radius;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        deadzone_radius = DEADZONE_RADIUS.get();
        teleport_radius = TELEPORT_RADIUS.get();
    }
}
