package com.slowbag.pulse;

import com.slowbag.pulse.network.PulseNetworkPayload;
import com.slowbag.pulse.network.PulseServerNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicPlayerMod implements ModInitializer {
    public static final String MOD_ID = "musicplayer";
    public static final Logger LOGGER = LoggerFactory.getLogger("MusicPlayer");

    static {
        try {
            if (System.getProperty("java.net.preferIPv6Addresses") == null) {
                System.setProperty("java.net.preferIPv6Addresses", "system");
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onInitialize() {
        try {
            // Register bidirectional network payloads
            PayloadTypeRegistry.serverboundPlay().register(PulseNetworkPayload.TYPE, PulseNetworkPayload.CODEC);
            PayloadTypeRegistry.clientboundPlay().register(PulseNetworkPayload.TYPE, PulseNetworkPayload.CODEC);

            // Initialize server networking (acts as dedicated host when placed in server mods folder)
            PulseServerNetworking.init();

            LOGGER.info("[Music Player] Initialized core mod with native server networking.");
        } catch (Throwable t) {
            LOGGER.error("[Music Player] Networking initialization warning: " + t.getMessage(), t);
        }
    }
}
