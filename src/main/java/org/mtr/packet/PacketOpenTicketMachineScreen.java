package org.mtr.packet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.mtr.data.EuroEconomyBridge;
import org.mtr.data.TicketSystem;
import org.mtr.registry.RegistryServer;

import java.util.UUID;

/**
 * Opens the ticket machine screen with the player's transit card and bank balances, or refreshes it after a top-up or
 * refund settles.
 */
public final class PacketOpenTicketMachineScreen extends PacketHandler {

	private final int cardBalance;
	private final boolean bankAvailable;
	private final int bankBalance;
	private final String bankError;
	private final boolean refreshOnly;

	public PacketOpenTicketMachineScreen(PacketBufferReceiver packetBufferReceiver) {
		cardBalance = packetBufferReceiver.readInt();
		bankAvailable = packetBufferReceiver.readBoolean();
		bankBalance = packetBufferReceiver.readInt();
		bankError = packetBufferReceiver.readString();
		refreshOnly = packetBufferReceiver.readBoolean();
	}

	/**
	 * @param cardBalance transit card balance in Euro cents
	 * @param bankBalance Euro Economy bank balance in cents, or {@code null} if the bank could not be reached
	 * @param bankError   why the bank could not be reached
	 * @param refreshOnly only update a ticket machine screen that is already open, never open one
	 */
	public PacketOpenTicketMachineScreen(int cardBalance, @Nullable Integer bankBalance, @Nullable String bankError, boolean refreshOnly) {
		this.cardBalance = cardBalance;
		bankAvailable = bankBalance != null;
		this.bankBalance = bankBalance == null ? 0 : bankBalance;
		this.bankError = bankError == null ? "" : bankError;
		this.refreshOnly = refreshOnly;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(cardBalance);
		packetBufferSender.writeBoolean(bankAvailable);
		packetBufferSender.writeInt(bankBalance);
		packetBufferSender.writeString(bankError);
		packetBufferSender.writeBoolean(refreshOnly);
	}

	@Override
	public void runClient() {
		ClientPacketHelper.openTicketMachineScreen(cardBalance, bankAvailable, bankBalance, bankError, refreshOnly);
	}

	/**
	 * Server side: look up the bank balance, then send both balances to the player. Must be called on the server thread.
	 *
	 * @param refreshOnly only update a ticket machine screen the player already has open
	 */
	public static void send(MinecraftServer minecraftServer, UUID uuid, Level world, String playerName, boolean refreshOnly) {
		final int cardBalance = TicketSystem.getBalance(world, playerName);
		EuroEconomyBridge.getBalance(uuid).whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> {
			final ServerPlayer serverPlayer = minecraftServer.getPlayerList().getPlayer(uuid);
			if (serverPlayer != null) {
				RegistryServer.sendPacketToClient(serverPlayer, new PacketOpenTicketMachineScreen(
					cardBalance,
					throwable == null ? bankBalance : null,
					throwable == null ? null : EuroEconomyBridge.getErrorMessage(throwable),
					refreshOnly
				));
			}
		}));
	}

	/**
	 * Server side: update the player's open ticket machine screen once a top-up or refund has settled.
	 */
	public static void refresh(MinecraftServer minecraftServer, UUID uuid, Level world, String playerName) {
		send(minecraftServer, uuid, world, playerName, true);
	}
}
