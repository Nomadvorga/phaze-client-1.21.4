package vorga.phazeclient.mixins;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vorga.phazeclient.api.system.logging.FullLog;

@Mixin(ClientConnection.class)
public abstract class FullLoggingConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V",
            at = @At("HEAD"), require = 1)
    private void phaze$fullLogSend(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLog.event("PACKET_SEND", "type=" + packet.getPacketType()
                + " class=" + packet.getClass().getName() + " flush=" + flush);
    }

    @Inject(method = "handlePacket", at = @At("HEAD"), require = 1)
    private static void phaze$fullLogReceive(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLog.event("PACKET_RECEIVE", "type=" + packet.getPacketType()
                + " class=" + packet.getClass().getName());
    }

    @Inject(method = "exceptionCaught", at = @At("HEAD"), require = 1)
    private void phaze$fullLogNetworkError(ChannelHandlerContext context, Throwable error, CallbackInfo ci) {
        FullLog.error("NETWORK_EXCEPTION", error);
    }
}
