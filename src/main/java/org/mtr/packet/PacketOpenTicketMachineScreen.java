package org.mtr.packet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.mtr.data.EuroEconomyBridge;
import org.mtr.data.TransitCardPayments;
import org.mtr.data.TransitCardStore;
import org.mtr.registry.RegistryServer;

import java.util.UUID;

/**
 * Opens the ticket machine screen with the player's transit card balance, or refreshes the open one with the card and
 * bank balances.
 */
public final class PacketOpenTicketMachineScreen extends PacketHandler {

	private final int cardBalance;
	private final int bankState;
	private final int bankBalance;
	private final String bankError;
	private final boolean paymentPending;
	private final boolean refreshOnly;

	/**
	 * The bank balance has been asked for and not arrived yet.
	 */
	public static final int BANK_LOADING = 0;
	public static final int BANK_AVAILABLE = 1;
	/**
	 * The bank could not be reached; {@code bankError} says why.
	 */
	public static final int BANK_UNAVAILABLE = 2;

	public PacketOpenTicketMachineScreen(PacketBufferReceiver packetBufferReceiver) {
		cardBalance = packetBufferReceiver.readInt();
		bankState = packetBufferReceiver.readInt();
		bankBalance = packetBufferReceiver.readInt();
		bankError = packetBufferReceiver.readString();
		paymentPending = packetBufferReceiver.readBoolean();
		refreshOnly = packetBufferReceiver.readBoolean();
	}

	/**
	 * @param cardBalance    transit card balance in Euro cents
	 * @param bankState      {@link #BANK_LOADING}, {@link #BANK_AVAILABLE} or {@link #BANK_UNAVAILABLE}
	 * @param bankBalance    Euro Economy bank balance in cents, if available
	 * @param bankError      why the bank could not be reached, if unavailable
	 * @param paymentPending a top-up or refund is still waiting for the bank
	 * @param refreshOnly    only update a ticket machine screen that is already open, never open one
	 */
	public PacketOpenTicketMachineScreen(int cardBalance, int bankState, int bankBalance, String bankError, boolean paymentPending, boolean refreshOnly) {
		this.cardBalance = cardBalance;
		this.bankState = bankState;
		this.bankBalance = bankBalance;
		this.bankError = bankError;
		this.paymentPending = paymentPending;
		this.refreshOnly = refreshOnly;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(cardBalance);
		packetBufferSender.writeInt(bankState);
		packetBufferSender.writeInt(bankBalance);
		packetBufferSender.writeString(bankError);
		packetBufferSender.writeBoolean(paymentPending);
		packetBufferSender.writeBoolean(refreshOnly);
	}

	@Override
	public void runClient() {
		ClientPacketHelper.openTicketMachineScreen(cardBalance, bankState, bankBalance, bankError, paymentPending, refreshOnly);
	}

	/**
	 * Server side: opens the ticket machine screen straight away with the card balance, then fills in the bank balance
	 * once the bank answers ({@link #refresh}).
	 */
	public static void open(MinecraftServer minecraftServer, ServerPlayer serverPlayer) {
		RegistryServer.sendPacketToClient(serverPlayer, create(minecraftServer, serverPlayer.getUUID(), BANK_LOADING, 0, "", false));
		refresh(minecraftServer, serverPlayer);
	}

	/**
	 * Server side: asks the bank for the player's balance and sends it with the card balance to their ticket machine
	 * screen, if it is still open. A refresh never opens a screen, so a slow bank cannot pop one up later.
	 */
	public static void refresh(MinecraftServer minecraftServer, ServerPlayer serverPlayer) {
		final UUID uuid = serverPlayer.getUUID();
		EuroEconomyBridge.getBalance(uuid).whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> {
			final ServerPlayer onlinePlayer = minecraftServer.getPlayerList().getPlayer(uuid);
			if (onlinePlayer != null) {
				RegistryServer.sendPacketToClient(onlinePlayer, throwable == null
					? create(minecraftServer, uuid, BANK_AVAILABLE, bankBalance == null ? 0 : bankBalance, "", true)
					: create(minecraftServer, uuid, BANK_UNAVAILABLE, 0, EuroEconomyBridge.getErrorMessage(throwable), true)
				);
			}
		}));
	}

	private static PacketOpenTicketMachineScreen create(MinecraftServer minecraftServer, UUID uuid, int bankState, int bankBalance, String bankError, boolean refreshOnly) {
		final TransitCardStore store = TransitCardPayments.getStore(minecraftServer);
		return new PacketOpenTicketMachineScreen((int) store.getBalance(uuid), bankState, bankBalance, bankError, store.hasPending(uuid), refreshOnly);
	}
}
