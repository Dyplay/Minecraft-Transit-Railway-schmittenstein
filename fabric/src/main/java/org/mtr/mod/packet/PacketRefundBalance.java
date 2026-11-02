package org.mtr.mod.packet;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.data.EuroEconomyBridge;
import org.mtr.mod.data.TicketSystem;
import org.mtr.mod.generated.lang.TranslationProvider;

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
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final UUID uuid = serverPlayerEntity.getUuid();
		final String playerName = serverPlayerEntity.getGameProfile().getName();
		final World world = new World(serverPlayerEntity.getServerWorld().data);
		final int cents = TicketSystem.getBalance(world, playerName);

		if (cents <= 0) {
			serverPlayerEntity.sendMessage(TranslationProvider.GUI_MTR_CARD_NOTHING_TO_REFUND.getText(), false);
			return;
		}

		if (!EuroEconomyBridge.isAvailable()) {
			serverPlayerEntity.sendMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.NOT_INSTALLED), false);
			return;
		}

		// Emptied before the bank is asked, so a second click while this one is in flight finds nothing to refund
		TicketSystem.addBalance(world, playerName, -cents);

		EuroEconomyBridge.deposit(uuid, cents, EuroEconomyBridge.LEDGER_TYPE_REFUND, "Refunded " + EuroEconomyBridge.format(cents) + " from a transit card").whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> {
			if (throwable != null) {
				// The bank did not take it, so the money goes back on the card
				TicketSystem.addBalance(world, playerName, cents);
			}

			final ServerPlayerEntity player = minecraftServer.getPlayerManager().getPlayer(uuid);
			if (player == null) {
				return;
			}

			if (throwable == null) {
				player.sendMessage(TranslationProvider.GUI_MTR_CARD_REFUNDED.getText(EuroEconomyBridge.format(cents), EuroEconomyBridge.format(bankBalance)), false);
				player.getServerWorld().playSound((PlayerEntity) null, player.getBlockPos(), SoundEvents.getEntityExperienceOrbPickupMapped(), SoundCategory.getBlocksMapped(), 1, 1);
			} else {
				player.sendMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.getErrorMessage(throwable)), false);
			}
		}));
	}
}
