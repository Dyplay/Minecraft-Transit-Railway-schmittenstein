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
		final UUID uuid = serverPlayerEntity.getUUID();
		final String playerName = serverPlayerEntity.getGameProfile().getName();
		final ServerLevel serverWorld = serverPlayerEntity.serverLevel();
		final int cents = TicketSystem.getBalance(serverWorld, playerName);

		if (cents <= 0) {
			serverPlayerEntity.displayClientMessage(TranslationProvider.GUI_MTR_CARD_NOTHING_TO_REFUND.getText(), false);
			PacketOpenTicketMachineScreen.refresh(minecraftServer, uuid, serverWorld, playerName);
			return;
		}

		if (!EuroEconomyBridge.isAvailable()) {
			serverPlayerEntity.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.NOT_INSTALLED), false);
			PacketOpenTicketMachineScreen.refresh(minecraftServer, uuid, serverWorld, playerName);
			return;
		}

		// Emptied before the bank is asked, so a second click while this one is in flight finds nothing to refund
		TicketSystem.addBalance(serverWorld, playerName, -cents);

		EuroEconomyBridge.deposit(uuid, cents, EuroEconomyBridge.LEDGER_TYPE_REFUND, "Refunded " + EuroEconomyBridge.format(cents) + " from a transit card").whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> {
			if (throwable != null) {
				// The bank did not take it, so the money goes back on the card
				TicketSystem.addBalance(serverWorld, playerName, cents);
			}

			final ServerPlayer player = minecraftServer.getPlayerList().getPlayer(uuid);
			if (player != null) {
				if (throwable == null) {
					player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_REFUNDED.getText(EuroEconomyBridge.format(cents), EuroEconomyBridge.format(bankBalance)), false);
					player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1, 1);
				} else {
					player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.getErrorMessage(throwable)), false);
				}
			}

			PacketOpenTicketMachineScreen.refresh(minecraftServer, uuid, serverWorld, playerName);
		}));
	}
}
