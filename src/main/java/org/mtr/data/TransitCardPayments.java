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
 * A payment that may already have reached the bank - after an unknown outcome, or found pending at startup - is sent
 * as a retry: the bank then only reports a refusal as definite if it looked the reference up first, so a refusal that
 * says nothing about the earlier attempt (a wrong API key, say) keeps the payment pending instead of undoing it.
 * <p>
 * Called on the server thread.
 */
public final class TransitCardPayments {

	@Nullable
	private static MinecraftServer server;
	private static int ticks;

	private static final int RETRY_INTERVAL_SECONDS = 30;
	private static final int RETRY_INTERVAL_TICKS = RETRY_INTERVAL_SECONDS * 20;
	private static final int FLUSH_INTERVAL_TICKS = 20;
	public static final String CARD_NOT_SAVED = "The transit card could not be saved";
	/**
	 * References with a bank request outstanding, so a retry never overlaps the attempt before it.
	 */
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
	/**
	 * References that may already have been applied by the bank: sent with an unknown outcome, or found pending at
	 * startup.
	 */
	private static final Set<String> MAY_HAVE_BEEN_APPLIED = ConcurrentHashMap.newKeySet();

	private TransitCardPayments() {
	}

	public static void onServerStarted(MinecraftServer minecraftServer) {
		server = minecraftServer;
		ticks = 0;
		IN_FLIGHT.clear();
		MAY_HAVE_BEEN_APPLIED.clear();
		// A payment still pending from the last run may have reached the bank before the server stopped
		getStore(minecraftServer).getPending().forEach(payment -> MAY_HAVE_BEEN_APPLIED.add(payment.reference()));
	}

	public static void onServerStopping() {
		final MinecraftServer minecraftServer = server;
		server = null;
		if (minecraftServer != null && !getStore(minecraftServer).flush()) {
			MTR.LOGGER.error("Transit cards could not be saved while stopping; the last saved state will be loaded next time");
		}
	}

	/**
	 * Every server tick. Every 30 seconds, sends the payments the bank has not settled again - from this run, or left over
	 * from before a restart.
	 */
	public static void tick() {
		final MinecraftServer minecraftServer = server;
		if (minecraftServer == null) {
			return;
		}
		ticks++;
		if (ticks % FLUSH_INTERVAL_TICKS == 0) {
			getStore(minecraftServer).flush();
		}
		if (ticks % RETRY_INTERVAL_TICKS == 0 && EuroEconomyBridge.isAvailable()) {
			for (final TransitCardStore.PendingPayment payment : getStore(minecraftServer).getPending()) {
				send(minecraftServer, payment, false);
			}
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
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(CARD_NOT_SAVED), false);
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
			player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_FAILED.getText(CARD_NOT_SAVED), false);
		}

		PacketOpenTicketMachineScreen.refresh(minecraftServer, player);
	}

	private static void send(MinecraftServer minecraftServer, TransitCardStore.PendingPayment payment, boolean firstAttempt) {
		if (!IN_FLIGHT.add(payment.reference())) {
			return;
		}

		final String amount = EuroEconomyBridge.format(payment.cents());
		final boolean retry = MAY_HAVE_BEEN_APPLIED.contains(payment.reference());
		final CompletableFuture<Integer> future = payment.kind() == TransitCardStore.Kind.TOP_UP
			? EuroEconomyBridge.withdraw(payment.player(), payment.cents(), EuroEconomyBridge.LEDGER_TYPE_TOP_UP, "Loaded " + amount + " onto a transit card", payment.reference(), retry)
			: EuroEconomyBridge.deposit(payment.player(), payment.cents(), EuroEconomyBridge.LEDGER_TYPE_REFUND, "Refunded " + amount + " from a transit card", payment.reference(), retry);

		future.whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> settle(minecraftServer, payment, bankBalance, throwable, firstAttempt)));
	}

	private static void settle(MinecraftServer minecraftServer, TransitCardStore.PendingPayment payment, @Nullable Integer bankBalance, @Nullable Throwable throwable, boolean firstAttempt) {
		IN_FLIGHT.remove(payment.reference());
		final TransitCardStore store = getStore(minecraftServer);
		final ServerPlayer player = minecraftServer.getPlayerList().getPlayer(payment.player());
		final String amount = EuroEconomyBridge.format(payment.cents());

		if (throwable == null) {
			MAY_HAVE_BEEN_APPLIED.remove(payment.reference());
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
			MAY_HAVE_BEEN_APPLIED.add(payment.reference());
			MTR.LOGGER.warn("Transit card {} {} for {} has an unknown outcome ({}); retrying", payment.kind(), payment.reference(), payment.player(), EuroEconomyBridge.getErrorMessage(throwable));
			if (player != null && firstAttempt) {
				player.displayClientMessage(TranslationProvider.GUI_MTR_CARD_PAYMENT_DELAYED.getText(), false);
			}
		} else {
			// A definite refusal: nothing moved at the bank (for a retry, the bank checked the reference was never applied),
			// so a refund goes back onto the card
			MAY_HAVE_BEEN_APPLIED.remove(payment.reference());
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
