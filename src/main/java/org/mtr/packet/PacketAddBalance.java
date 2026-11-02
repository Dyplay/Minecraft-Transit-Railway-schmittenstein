package org.mtr.packet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.mtr.data.EuroEconomyBridge;
import org.mtr.data.TicketSystem;
import org.mtr.generated.lang.TranslationProvider;

import java.util.UUID;

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
		final UUID uuid = serverPlayerEntity.getUUID();
		final String playerName = serverPlayerEntity.getGameProfile().getName();
		final ServerLevel serverWorld = serverPlayerEntity.serverLevel();

		if (index < 0 || index >= TOP_UP_CENTS.length) {
			PacketOpenTicketMachineScreen.refresh(minecraftServer, uuid, serverWorld, playerName);
			return;
		}

		final int cents = TOP_UP_CENTS[index];
		EuroEconomyBridge.withdraw(uuid, cents, EuroEconomyBridge.LEDGER_TYPE_TOP_UP, "Loaded " + EuroEconomyBridge.format(cents) + " onto a transit card").whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> {
			// The money has left the bank by now, so the card is credited even if the player logged out meanwhile
			if (throwable == null) {
				TicketSystem.addBalance(serverWorld, playerName, cents);
			}

			final ServerPlayer player = minecraftServer.getPlayerList().getPlayer(uuid);
			if (player != null) {
				if (throwable == null) {
					player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_TOPPED_UP.getText(
						EuroEconomyBridge.format(cents),
						EuroEconomyBridge.format(TicketSystem.getBalance(serverWorld, playerName)),
						EuroEconomyBridge.format(bankBalance)
					), false);
					player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1, 1);
				} else {
					player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.getErrorMessage(throwable)), false);
				}
			}

			PacketOpenTicketMachineScreen.refresh(minecraftServer, uuid, serverWorld, playerName);
		}));
	}

	public static int getButtonCount() {
		return TOP_UP_CENTS.length;
	}

	public static int getAddBalanceAmount(int index) {
		return TOP_UP_CENTS[index];
	}
}
