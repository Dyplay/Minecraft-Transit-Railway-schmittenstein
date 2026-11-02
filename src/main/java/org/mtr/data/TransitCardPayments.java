package org.mtr.data;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.packet.PacketOpenTicketMachineScreen;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket machine payments between a player's transit card ({@link TransitCardStore}) and their Euro Economy bank
 * account ({@link EuroEconomyBridge}).
 * <p>
 * Every payment gets a reference and is recorded as pending before the bank is asked. It is settled only on a definite
 * answer: a success, or a refusal (nothing moved). When the outcome is unknown - a timeout, a lost connection, the
 * server stopping mid-request - the payment stays pending and is sent again with the same reference every 30 seconds,
 * across restarts too, until the bank answers. The bank applies a reference at
 * most once, so a retry never moves the money twice. A player can have one payment pending at a time.
 * <p>
 * Called on the server thread.
 */
public final class TransitCardPayments {

	@Nullable
	private static MinecraftServer server;
	private static int ticks;

	private static final int RETRY_INTERVAL_SECONDS = 30;
	private static final int RETRY_INTERVAL_TICKS = RETRY_INTERVAL_SECONDS * 20;
	/**
	 * References with a bank request outstanding, so a retry never overlaps the attempt before it.
	 */
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	private TransitCardPayments() {
	}

	public static void onServerStarted(MinecraftServer minecraftServer) {
		server = minecraftServer;
		ticks = 0;
		IN_FLIGHT.clear();
	}

	public static void onServerStopping() {
		server = null;
	}

	/**
	 * Every server tick. Every 30 seconds, sends the payments the bank has not settled again - from this run, or left over
	 * from before a restart.
	 */
	public static void tick() {
		final MinecraftServer minecraftServer = server;
		if (minecraftServer == null || ++ticks % RETRY_INTERVAL_TICKS != 0 || !EuroEconomyBridge.isAvailable()) {
			return;
		}
		for (final TransitCardStore.PendingPayment payment : getStore(minecraftServer).getPending()) {
			send(minecraftServer, payment, false);
		}
	}

	/**
	 * The transit cards of the server's world.
	 */
	public static TransitCardStore getStore(MinecraftServer minecraftServer) {
		return TransitCardStore.get(minecraftServer.getWorldPath(LevelResource.ROOT));
	}

	/**
	 * Loads {@code cents} from the player's bank account onto their card.
	 */
	public static void topUp(MinecraftServer minecraftServer, ServerPlayer player, int cents) {
		final TransitCardStore store = getStore(minecraftServer);
		final UUID uuid = player.getUUID();

		if (store.hasPending(uuid)) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_PENDING.getText(), false);
		} else if (!EuroEconomyBridge.isAvailable()) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.NOT_AVAILABLE), false);
		} else if (store.getBalance(uuid) + cents > TransitCardStore.LIMIT_CENTS) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_LIMIT.getText(EuroEconomyBridge.format(TransitCardStore.LIMIT_CENTS)), false);
		} else {
			final TransitCardStore.PendingPayment payment = store.beginTopUp(uuid, cents);
			if (payment != null) {
				send(minecraftServer, payment, true);
				return;
			}
		}

		PacketOpenTicketMachineScreen.refresh(minecraftServer, player);
	}

	/**
	 * Pays everything on the player's card back into their bank account.
	 */
	public static void refund(MinecraftServer minecraftServer, ServerPlayer player) {
		final TransitCardStore store = getStore(minecraftServer);
		final UUID uuid = player.getUUID();

		if (store.hasPending(uuid)) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_PENDING.getText(), false);
		} else if (store.getBalance(uuid) <= 0) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_NOTHING_TO_REFUND.getText(), false);
		} else if (!EuroEconomyBridge.isAvailable()) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.NOT_AVAILABLE), false);
		} else {
			final TransitCardStore.PendingPayment payment = store.beginRefund(uuid);
			if (payment != null) {
				send(minecraftServer, payment, true);
				return;
			}
		}

		PacketOpenTicketMachineScreen.refresh(minecraftServer, player);
	}

	private static void send(MinecraftServer minecraftServer, TransitCardStore.PendingPayment payment, boolean firstAttempt) {
		if (!IN_FLIGHT.add(payment.reference())) {
			return;
		}

		final String amount = EuroEconomyBridge.format(payment.cents());
		final CompletableFuture<Integer> future = payment.kind() == TransitCardStore.Kind.TOP_UP
			? EuroEconomyBridge.withdraw(payment.player(), payment.cents(), EuroEconomyBridge.LEDGER_TYPE_TOP_UP, "Loaded " + amount + " onto a transit card", payment.reference())
			: EuroEconomyBridge.deposit(payment.player(), payment.cents(), EuroEconomyBridge.LEDGER_TYPE_REFUND, "Refunded " + amount + " from a transit card", payment.reference());

		future.whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> settle(minecraftServer, payment, bankBalance, throwable, firstAttempt)));
	}

	private static void settle(MinecraftServer minecraftServer, TransitCardStore.PendingPayment payment, @Nullable Integer bankBalance, @Nullable Throwable throwable, boolean firstAttempt) {
		IN_FLIGHT.remove(payment.reference());
		final TransitCardStore store = getStore(minecraftServer);
		final ServerPlayer player = minecraftServer.getPlayerList().getPlayer(payment.player());
		final String amount = EuroEconomyBridge.format(payment.cents());

		if (throwable == null) {
			store.succeed(payment.reference());
			if (player != null) {
				final String bank = EuroEconomyBridge.format(bankBalance == null ? 0 : bankBalance);
				player.displayClientMessage(payment.kind() == TransitCardStore.Kind.TOP_UP
					? TranslationProvider.GUI_MTR_CARD_TOPPED_UP.getText(amount, EuroEconomyBridge.format(store.getBalance(payment.player())), bank)
					: TranslationProvider.GUI_MTR_CARD_REFUNDED.getText(amount, bank), false);
				player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1, 1);
			}
		} else if (EuroEconomyBridge.isOutcomeUnknown(throwable)) {
			// The money may or may not have moved: keep the payment pending, tick() sends it again with the same reference
			MTR.LOGGER.warn("Transit card {} {} for {} has an unknown outcome ({}); retrying", payment.kind(), payment.reference(), payment.player(), EuroEconomyBridge.getErrorMessage(throwable));
			if (player != null && firstAttempt) {
				player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_DELAYED.getText(), false);
			}
		} else {
			// A definite refusal: nothing moved at the bank, so a refund goes back onto the card
			store.refuse(payment.reference());
			if (player != null) {
				player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(EuroEconomyBridge.getErrorMessage(throwable)), false);
			}
		}

		if (player != null) {
			PacketOpenTicketMachineScreen.refresh(minecraftServer, player);
		}
	}
}
