package org.mtr.packet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mtr.data.TransitCardPayments;

/**
 * Ticket machine: pay what is left on the transit card back into the player's Euro Economy bank account.
 */
public final class PacketRefundBalance extends PacketHandler {

	public PacketRefundBalance(PacketBufferReceiver packetBufferReceiver) {
	}

	public PacketRefundBalance() {
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayer serverPlayerEntity) {
		TransitCardPayments.refund(minecraftServer, serverPlayerEntity);
	}
}
