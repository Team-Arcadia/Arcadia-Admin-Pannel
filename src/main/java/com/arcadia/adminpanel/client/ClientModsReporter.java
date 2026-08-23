package com.arcadia.adminpanel.client;

import com.arcadia.adminpanel.network.C2SClientMods;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports this client's mod list to the server once, shortly after login.
 *
 * <p>Sent only when the server actually registered the channel, so connecting to a server running an
 * older Admin Panel, or none at all, does nothing and costs nothing. The send is deferred by a
 * moment because the login tick is already the busiest one of the session and this is the least
 * urgent thing happening on it.</p>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel", value = Dist.CLIENT)
public final class ClientModsReporter {

    /** Ticks to wait after login before reporting. */
    private static final int DELAY_TICKS = 40;

    private static int countdown = -1;

    private ClientModsReporter() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        countdown = DELAY_TICKS;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        countdown = -1;
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (countdown < 0) return;
        if (--countdown > 0) return;
        countdown = -1;
        send();
    }

    private static void send() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        // The payload is optional: a server without it must not receive anything.
        if (!NetworkRegistry.hasChannel(mc.getConnection(), C2SClientMods.TYPE.id())) return;

        List<String> ids = new ArrayList<>();
        for (var container : ModList.get().getMods()) {
            ids.add(container.getModId());
            if (ids.size() >= C2SClientMods.MAX_ENTRIES) break;
        }
        try {
            PacketDistributor.sendToServer(new C2SClientMods(ids));
        } catch (Exception ignored) {
            // A server that rejects the payload is not a client-side problem worth surfacing.
        }
    }
}
