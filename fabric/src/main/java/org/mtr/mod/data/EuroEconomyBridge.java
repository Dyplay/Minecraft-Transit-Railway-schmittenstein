package org.mtr.mod.data;

import org.mtr.mod.Init;

import javax.annotation.Nullable;
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
 * needs it installed: without it every payment fails with {@link #NOT_INSTALLED} and ticket machines say so.
 * <p>
 * Amounts are cents (EUR 1.00 = 100). The futures complete on the economy's worker threads, never the server thread -
 * callers hop back with {@code MinecraftServer#execute} before touching the world.
 */
public final class EuroEconomyBridge {

	public static final String NOT_INSTALLED = "Euro Economy is not installed";
	/**
	 * Ledger entries the economy backend files ticket machine payments under (its {@code MOD_CHARGE_TYPES} and
	 * {@code MOD_CREDIT_TYPES}). An older backend ignores them and files plain admin removals and grants.
	 */
	public static final String LEDGER_TYPE_TOP_UP = "TRANSIT_CARD_TOPUP";
	public static final String LEDGER_TYPE_REFUND = "TRANSIT_CARD_REFUND";

	private static final String API_CLASS = "com.dyplay.euroeconomy.neoforge.api.EuroEconomyApi";
	private static final int REQUIRED_API_VERSION = 1;

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
			Init.LOGGER.error("Euro Economy: isAvailable failed", e);
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
	 * Take {@code cents} from the player's bank account. Fails, taking nothing, if they cannot afford it. Completes
	 * with what is left in the account.
	 */
	public static CompletableFuture<Integer> withdraw(UUID player, int cents, String ledgerType, String reason) {
		final Api resolvedApi = getApi();
		return resolvedApi == null ? notInstalled() : call(resolvedApi.withdraw, player, cents, ledgerType, reason);
	}

	/**
	 * Pay {@code cents} into the player's bank account. Completes with the new balance.
	 */
	public static CompletableFuture<Integer> deposit(UUID player, int cents, String ledgerType, String reason) {
		final Api resolvedApi = getApi();
		return resolvedApi == null ? notInstalled() : call(resolvedApi.deposit, player, cents, ledgerType, reason);
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
		return cents % 100 == 0 ? "\u20AC" + new DecimalFormat("#,###").format(cents / 100) : NumberFormat.getCurrencyInstance(Locale.GERMANY).format(cents / 100D);
	}

	/**
	 * The message to show a player for a failed payment future.
	 */
	public static String getErrorMessage(Throwable throwable) {
		Throwable cause = throwable;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
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
			Init.LOGGER.info("Euro Economy not found; ticket machines are disabled");
			return null;
		}
		try {
			final int version = apiClass.getField("VERSION").getInt(null);
			if (version < REQUIRED_API_VERSION) {
				Init.LOGGER.warn("Euro Economy API version {} is too old (need {}); ticket machines are disabled", version, REQUIRED_API_VERSION);
				return null;
			}
			final Api resolvedApi = new Api(
					apiClass.getMethod("isAvailable"),
					apiClass.getMethod("getBalance", UUID.class),
					apiClass.getMethod("withdraw", UUID.class, int.class, String.class, String.class),
					apiClass.getMethod("deposit", UUID.class, int.class, String.class, String.class)
			);
			Init.LOGGER.info("Ticket machines take Euro from Euro Economy (API version {})", version);
			return resolvedApi;
		} catch (Exception e) {
			Init.LOGGER.error("Euro Economy API does not match; ticket machines are disabled", e);
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
			return ((CompletableFuture<Integer>) method.invoke(null, arguments)).thenApply(EuroEconomyBridge::toInt);
		} catch (Exception e) {
			final CompletableFuture<Integer> future = new CompletableFuture<>();
			future.completeExceptionally(e.getCause() == null ? e : e.getCause());
			return future;
		}
	}

	private static int toInt(Object value) {
		return ((Number) value).intValue();
	}

	private static CompletableFuture<Integer> notInstalled() {
		final CompletableFuture<Integer> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException(NOT_INSTALLED));
		return future;
	}

	private static final class Api {

		private final Method isAvailable;
		private final Method getBalance;
		private final Method withdraw;
		private final Method deposit;

		private Api(Method isAvailable, Method getBalance, Method withdraw, Method deposit) {
			this.isAvailable = isAvailable;
			this.getBalance = getBalance;
			this.withdraw = withdraw;
			this.deposit = deposit;
		}
	}
}
