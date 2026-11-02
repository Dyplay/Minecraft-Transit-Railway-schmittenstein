package org.mtr.data;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transit cards of one world: every player's card balance in Euro cents, keyed by UUID, and the bank payments for
 * them that have not settled yet.
 * <p>
 * Card money is real money - it was loaded from, and can be refunded to, the Euro Economy bank - so it is not kept on
 * the scoreboard, which is only saved with the world. It lives in its own file, and every change is written before the
 * method returns, balances and pending payments in one atomic write. A crash therefore never leaves a card emptied
 * without its refund on record, or a top-up on record without the card. Payments still pending at startup are retried
 * by {@link TransitCardPayments}; the bank applies each payment's reference at most once, so retrying is safe.
 * <p>
 * Every balance saturates at plus or minus {@link #LIMIT_CENTS}, so no amount of fares or fines can wrap a card around
 * to a large positive balance. Plain Java on purpose: no Minecraft classes, so the money logic can be tested on its own.
 */
public final class TransitCardStore {

	/**
	 * EUR 10,000,000: the most a card may hold, and the most it may owe.
	 */
	public static final long LIMIT_CENTS = 1_000_000_000L;

	private final Path path;
	private final Map<UUID, Long> balances = new HashMap<>();
	private final Map<String, PendingPayment> pending = new LinkedHashMap<>();

	private static final Logger LOGGER = LogManager.getLogger("MinecraftTransitRailway");
	private static final String FILE_NAME = "transit_cards.txt";
	private static final String HEADER = "# MTR transit cards, in Euro cents. Lines: \"card <player uuid> <cents>\" and \"pending <reference> <player uuid> <TOP_UP|REFUND> <cents>\".";

	@Nullable
	private static TransitCardStore instance;

	private TransitCardStore(Path path) {
		this.path = path;
	}

	/**
	 * The store for the world saved in {@code worldDirectory}, loaded from {@code mtr/transit_cards.txt} the first time.
	 */
	public static synchronized TransitCardStore get(Path worldDirectory) {
		final Path file = worldDirectory.toAbsolutePath().normalize().resolve("mtr").resolve(FILE_NAME);
		if (instance == null || !instance.path.equals(file)) {
			instance = load(file);
		}
		return instance;
	}

	/**
	 * Reads a store from {@code file}; a missing file is an empty store.
	 */
	static TransitCardStore load(Path file) {
		final TransitCardStore store = new TransitCardStore(file);
		if (!Files.exists(file)) {
			return store;
		}

		final List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Refusing to start from an empty store: that would wipe every card and lose every pending payment
			throw new IllegalStateException("Could not read transit cards from " + file, e);
		}

		for (final String line : lines) {
			final String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			final String[] parts = trimmed.split("\\s+");
			try {
				if (parts[0].equals("card") && parts.length == 3) {
					store.balances.put(UUID.fromString(parts[1]), clamp(Long.parseLong(parts[2])));
				} else if (parts[0].equals("pending") && parts.length == 5) {
					final PendingPayment payment = new PendingPayment(parts[1], UUID.fromString(parts[2]), Kind.valueOf(parts[3]), Integer.parseInt(parts[4]));
					store.pending.put(payment.reference(), payment);
				} else {
					LOGGER.warn("Ignoring unreadable line in {}: {}", file, line);
				}
			} catch (IllegalArgumentException e) {
				LOGGER.warn("Ignoring unreadable line in {}: {}", file, line);
			}
		}

		return store;
	}

	/**
	 * The player's card balance in cents; negative when they owe fares.
	 */
	public synchronized long getBalance(UUID player) {
		return balances.getOrDefault(player, 0L);
	}

	/**
	 * Whether a top-up or refund for the player is still waiting for the bank.
	 */
	public synchronized boolean hasPending(UUID player) {
		return pending.values().stream().anyMatch(payment -> payment.player().equals(player));
	}

	/**
	 * Every payment still waiting for the bank, oldest first.
	 */
	public synchronized List<PendingPayment> getPending() {
		return new ArrayList<>(pending.values());
	}

	/**
	 * Takes a fare or fine off the card. The card may go negative, down to {@code -LIMIT_CENTS}.
	 *
	 * @return the balance afterwards
	 */
	public synchronized long charge(UUID player, long cents) {
		if (cents > 0) {
			add(player, -cents);
			save();
		}
		return getBalance(player);
	}

	/**
	 * Records a top-up of {@code cents} as pending. The card is only credited once the bank has taken the money
	 * ({@link #succeed}).
	 *
	 * @return the payment to send to the bank, or {@code null} if another payment for the player is pending, the amount
	 * is not positive, or the card would end up above {@link #LIMIT_CENTS}
	 */
	@Nullable
	public synchronized PendingPayment beginTopUp(UUID player, int cents) {
		if (cents <= 0 || hasPending(player) || getBalance(player) + cents > LIMIT_CENTS) {
			return null;
		}
		final PendingPayment payment = new PendingPayment(newReference(), player, Kind.TOP_UP, cents);
		pending.put(payment.reference(), payment);
		save();
		return payment;
	}

	/**
	 * Empties the card and records the refund as pending, in one write, so the same money cannot be refunded twice.
	 *
	 * @return the payment to send to the bank, or {@code null} if the card holds nothing or another payment for the player
	 * is pending
	 */
	@Nullable
	public synchronized PendingPayment beginRefund(UUID player) {
		final long cents = getBalance(player);
		if (cents <= 0 || hasPending(player)) {
			return null;
		}
		final PendingPayment payment = new PendingPayment(newReference(), player, Kind.REFUND, (int) cents);
		add(player, -cents);
		pending.put(payment.reference(), payment);
		save();
		return payment;
	}

	/**
	 * The bank applied the payment: a top-up goes onto the card; a refund is done.
	 */
	public synchronized void succeed(String reference) {
		final PendingPayment payment = pending.remove(reference);
		if (payment != null) {
			if (payment.kind() == Kind.TOP_UP) {
				add(payment.player(), payment.cents());
			}
			save();
		}
	}

	/**
	 * The bank definitely refused the payment, so nothing moved there: a refund goes back onto the card; a top-up is
	 * dropped. Never call this for an unknown outcome - that payment stays pending and is retried.
	 */
	public synchronized void refuse(String reference) {
		final PendingPayment payment = pending.remove(reference);
		if (payment != null) {
			if (payment.kind() == Kind.REFUND) {
				add(payment.player(), payment.cents());
			}
			save();
		}
	}

	private void add(UUID player, long delta) {
		final long newBalance = clamp(getBalance(player) + delta);
		if (newBalance == 0) {
			balances.remove(player);
		} else {
			balances.put(player, newBalance);
		}
	}

	/**
	 * Writes everything to a temporary file, forces it to disk, then moves it over the store file.
	 */
	private void save() {
		final StringBuilder stringBuilder = new StringBuilder(HEADER).append('\n');
		balances.forEach((player, cents) -> stringBuilder.append("card ").append(player).append(' ').append(cents).append('\n'));
		pending.values().forEach(payment -> stringBuilder.append("pending ").append(payment.reference()).append(' ').append(payment.player()).append(' ').append(payment.kind()).append(' ').append(payment.cents()).append('\n'));

		final Path temporaryPath = path.resolveSibling(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			try (final FileChannel fileChannel = FileChannel.open(temporaryPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				final ByteBuffer byteBuffer = ByteBuffer.wrap(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
				while (byteBuffer.hasRemaining()) {
					fileChannel.write(byteBuffer);
				}
				fileChannel.force(true);
			}
			try {
				Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.error("Could not save transit cards to {}; card balances changed since the last save are only in memory", path, e);
		}
	}

	private static long clamp(long cents) {
		return Math.max(-LIMIT_CENTS, Math.min(LIMIT_CENTS, cents));
	}

	private static String newReference() {
		return "mtr-" + UUID.randomUUID();
	}

	public enum Kind {TOP_UP, REFUND}

	/**
	 * A top-up or refund sent to the bank and not yet settled.
	 *
	 * @param reference unique per payment; the bank applies it at most once, so retrying with it is safe
	 */
	public record PendingPayment(String reference, UUID player, Kind kind, int cents) {
	}
}
