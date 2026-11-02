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
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		if (index < 0 || index >= TOP_UP_CENTS.length) {
			return;
		}

		final int cents = TOP_UP_CENTS[index];
		final UUID uuid = serverPlayerEntity.getUuid();
		final String playerName = serverPlayerEntity.getGameProfile().getName();
		final World world = new World(serverPlayerEntity.getServerWorld().data);

		EuroEconomyBridge.withdraw(uuid, cents, EuroEconomyBridge.LEDGER_TYPE_TOP_UP, "Loaded " + EuroEconomyBridge.format(cents) + " onto a transit card").whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> {
			// The money has left the bank by now, so the card is credited even if the player logged out meanwhile
			if (throwable == null) {
				TicketSystem.addBalance(world, playerName, cents);
			}

			final ServerPlayerEntity player = minecraftServer.getPlayerManager().getPlayer(uuid);
			if (player == null) {
				return;
			}

			if (throwable == null) {
				player.sendMessage(TranslationProvider.GUI_MTR_CARD_TOPPED_UP.getText(
						EuroEconomyBridge.format(cents),
						EuroEconomyBridge.format(TicketSystem.getBalance(world, playerName)),
						EuroEconomyBridge.format(bankBalance)
				), false);
				player.getServerWorld().playSound((PlayerEntity) null, player.getBlockPos(), SoundEvents.getEntityExperienceOrbPickupMapped(), SoundCategory.getBlocksMapped(), 1, 1);
			} else {
				player.sendMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.getErrorMessage(throwable)), false);
			}
		}));
	}

	public static int getButtonCount() {
		return TOP_UP_CENTS.length;
	}

	public static int getAddAmount(int index) {
		return TOP_UP_CENTS[index];
	}
}
