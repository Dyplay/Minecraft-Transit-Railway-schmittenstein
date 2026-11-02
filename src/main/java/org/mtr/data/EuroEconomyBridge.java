package org.mtr.data;

import org.jspecify.annotations.Nullable;
import org.mtr.MTR;

import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Pays for transit with the Euro Economy mod (money-mod-v2, {@code neoforge-mod}) instead of emeralds.
 * <p>
 * That mod is reached by reflection through its {@code EuroEconomyApi}, so this mod neither compiles against it nor
 * needs it installed: without it every payment fails with {@link #NOT_INSTALLED} and ticket machines say so. It only
 * exists for NeoForge 1.21.1, so on Fabric the bank is never available.
 * <p>
 * Amounts are cents (EUR 1.00 = 100). The futures complete on the economy's worker threads, never the server thread -
 * callers hop back with {@code MinecraftServer#execute} before touching the world.
 * <p>
 * A failed payment is either a definite refusal (nothing moved) or has an unknown outcome ({@link #isOutcomeUnknown}):
 * the bank may or may not have moved the money, and the payment must be retried with the same reference until the bank
 * gives a definite answer. {@link TransitCardPayments} does that.
 */
public final class EuroEconomyBridge {

	public static final String NOT_INSTALLED = "Euro Economy is not installed";
	/**
	 * Shown when {@link #isAvailable()} is false: not installed, or installed but not running (singleplayer).
	 */
	public static final String NOT_AVAILABLE = "The Euro Economy bank is not available";
	/**
	 * Ledger entries the economy backend files ticket machine payments under (its {@code MOD_CHARGE_TYPES} and
	 * {@code MOD_CREDIT_TYPES}). The money mod refuses to send these payments to a backend too old to apply references.
	 */
	public static final String LEDGER_TYPE_TOP_UP = "TRANSIT_CARD_TOPUP";
	public static final String LEDGER_TYPE_REFUND = "TRANSIT_CARD_REFUND";

	private static final String API_CLASS = "com.dyplay.euroeconomy.neoforge.api.EuroEconomyApi";
	/**
	 * 3: payments carry a reference and a retry flag, report unknown outcomes, and need a backend that applies references.
	 */
	private static final int REQUIRED_API_VERSION = 3;

	private static boolean resolved;
	@Nullable
	private static Api api;

	private EuroEconomyBridge() {
	}

	/**
	 * Whether payments can go through right now: the mod is installed and its economy is running (dedicated servers
	 * only - singleplayer worlds are not connected to the bank).
	 */
	public static boolean isAvailable() {
		final Api resolvedApi = getApi();
		if (resolvedApi == null) {
			return false;
		}
		try {
			return (boolean) resolvedApi.isAvailable.invoke(null);
		} catch (Exception e) {
			MTR.LOGGER.error("Euro Economy: isAvailable failed", e);
			return false;
		}
	}

	/**
	 * The player's bank balance in cents.
	 */
	public static CompletableFuture<Integer> getBalance(UUID player) {
		final Api resolvedApi = getApi();
		return resolvedApi == null ? notInstalled() : call(resolvedApi.getBalance, player);
	}

	/**
	 * Take {@code cents} from the player's bank account. Refused, taking nothing, if they cannot afford it. Completes
	 * with what is left in the account.
	 *
	 * @param reference unique per payment; the bank applies it at most once, so a retry with it is safe
	 * @param retry     an earlier attempt with this reference may have been applied (see {@link TransitCardPayments})
	 */
	public static CompletableFuture<Integer> withdraw(UUID player, int cents, String ledgerType, String reason, String reference, boolean retry) {
		final Api resolvedApi = getApi();
		return resolvedApi == null ? notInstalled() : call(resolvedApi.withdraw, player, cents, ledgerType, reason, reference, retry);
	}

	/**
	 * Pay {@code cents} into the player's bank account. Completes with the new balance.
	 *
	 * @param reference unique per payment; the bank applies it at most once, so a retry with it is safe
	 * @param retry     an earlier attempt with this reference may have been applied (see {@link TransitCardPayments})
	 */
	public static CompletableFuture<Integer> deposit(UUID player, int cents, String ledgerType, String reason, String reference, boolean retry) {
		final Api resolvedApi = getApi();
		return resolvedApi == null ? notInstalled() : call(resolvedApi.deposit, player, cents, ledgerType, reason, reference, retry);
	}

	/**
	 * Whether a failed payment future has an unknown outcome - timeout, lost connection, server error - rather than a
	 * definite refusal. The money may or may not have moved; retry with the same reference to find out.
	 */
	public static boolean isOutcomeUnknown(Throwable throwable) {
		return unwrap(throwable) instanceof UncheckedIOException;
	}

	/**
	 * A cents amount the way the economy mod writes it: {@code €12}, {@code 12,50 €}, {@code -€3}.
	 * Local rather than a call into the mod, so clients without it show the same thing.
	 */
	public static String format(long cents) {
		if (cents < 0) {
			return "-" + format(-cents);
		}
		// New formatters each time: they are not thread-safe, and the client and integrated server share this
		return cents % 100 == 0 ? "€" + new DecimalFormat("#,###").format(cents / 100) : NumberFormat.getCurrencyInstance(Locale.GERMANY).format(cents / 100D);
	}

	/**
	 * The message to show a player for a failed payment future.
	 */
	public static String getErrorMessage(Throwable throwable) {
		final Throwable cause = unwrap(throwable);
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private static Throwable unwrap(Throwable throwable) {
		Throwable cause = throwable;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	@Nullable
	private static synchronized Api getApi() {
		if (!resolved) {
			resolved = true;
			api = resolveApi();
		}
		return api;
	}

	@Nullable
	private static Api resolveApi() {
		final Class<?> apiClass = findClass();
		if (apiClass == null) {
			MTR.LOGGER.info("Euro Economy not found; ticket machines are disabled");
			return null;
		}
		try {
			final int version = apiClass.getField("VERSION").getInt(null);
			if (version < REQUIRED_API_VERSION) {
				MTR.LOGGER.warn("Euro Economy API version {} is too old (need {}); ticket machines are disabled", version, REQUIRED_API_VERSION);
				return null;
			}
			final Api resolvedApi = new Api(
				apiClass.getMethod("isAvailable"),
				apiClass.getMethod("getBalance", UUID.class),
				apiClass.getMethod("withdraw", UUID.class, int.class, String.class, String.class, String.class, boolean.class),
				apiClass.getMethod("deposit", UUID.class, int.class, String.class, String.class, String.class, boolean.class)
			);
			MTR.LOGGER.info("Ticket machines take Euro from Euro Economy (API version {})", version);
			return resolvedApi;
		} catch (Exception e) {
			MTR.LOGGER.error("Euro Economy API does not match; ticket machines are disabled", e);
			return null;
		}
	}

	@Nullable
	private static Class<?> findClass() {
		for (final ClassLoader classLoader : new ClassLoader[]{EuroEconomyBridge.class.getClassLoader(), Thread.currentThread().getContextClassLoader()}) {
			if (classLoader != null) {
				try {
					return Class.forName(API_CLASS, true, classLoader);
				} catch (ClassNotFoundException | LinkageError ignored) {
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static CompletableFuture<Integer> call(Method method, Object... arguments) {
		try {
			return ((CompletableFuture<Object>) method.invoke(null, arguments)).thenApply(value -> ((Number) value).intValue());
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e.getCause() == null ? e : e.getCause());
		}
	}

	private static CompletableFuture<Integer> notInstalled() {
		return CompletableFuture.failedFuture(new IllegalStateException(NOT_INSTALLED));
	}

	private record Api(Method isAvailable, Method getBalance, Method withdraw, Method deposit) {
	}
}
