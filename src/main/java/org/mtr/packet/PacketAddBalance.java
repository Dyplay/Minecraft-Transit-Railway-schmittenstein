package org.mtr.packet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mtr.data.TransitCardPayments;

/**
 * Ticket machine: load the transit card with Euro taken from the player's Euro Economy bank account.
 */
public final class PacketAddBalance extends PacketHandler {

	private final int index;

	/**
	 * What each ticket machine button loads, in Euro cents: €1 to €500.
	 */
	private static final int[] TOP_UP_CENTS = {100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};

	public PacketAddBalance(PacketBufferReceiver packetBufferReceiver) {
		index = packetBufferReceiver.readInt();
	}

	public PacketAddBalance(int index) {
		this.index = index;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(index);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayer serverPlayerEntity) {
		if (index >= 0 && index < TOP_UP_CENTS.length) {
			TransitCardPayments.topUp(minecraftServer, serverPlayerEntity, TOP_UP_CENTS[index]);
		} else {
			PacketOpenTicketMachineScreen.refresh(minecraftServer, serverPlayerEntity);
		}
	}

	public static int getButtonCount() {
		return TOP_UP_CENTS.length;
	}

	public static int getAddBalanceAmount(int index) {
		return TOP_UP_CENTS[index];
	}
}
