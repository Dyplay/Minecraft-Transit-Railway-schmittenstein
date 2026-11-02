package org.mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.config.Config;
import org.mtr.core.data.Station;
import org.mtr.core.operation.NearbyAreasRequest;
import org.mtr.core.operation.NearbyAreasResponse;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fare collection and player balance management.
 * Handles ticket barriers, balance tracking, zone-based fares, and fare evasion consequences.
 * <p>
 * Fares are paid from a transit card, loaded with Euro from the Euro Economy bank at ticket machines (see
 * {@link TransitCardPayments}). Card balances are real money, so they live in {@link TransitCardStore} (in Euro cents,
 * keyed by player UUID) rather than on the scoreboard; the entry zones stay on the scoreboard. Emeralds are no longer
 * taken, and the old emerald-bought balance ({@code mtr_balance}) is ignored: it was never paid for in Euro.
 * <p>
 * Without the bank (singleplayer, Fabric, or the mod not installed) nobody can load a card, so barriers let everyone
 * through without charging.
 */
public class TicketSystem {

	private static final String ENTRY_ZONE_1_OBJECTIVE = "mtr_entry_zone_1";
	private static final String ENTRY_ZONE_1_TITLE = "Entry Zone 1";
	private static final String ENTRY_ZONE_2_OBJECTIVE = "mtr_entry_zone_2";
	private static final String ENTRY_ZONE_2_TITLE = "Entry Zone 2";
	private static final String ENTRY_ZONE_3_OBJECTIVE = "mtr_entry_zone_3";
	private static final String ENTRY_ZONE_3_TITLE = "Entry Zone 3";
	/**
	 * Station zones are clamped to this, so zone distances and fares stay far from overflowing.
	 */
	private static final long MAX_ZONE = 1_000_000;
	/**
	 * A barrier re-triggers every few seconds while a player stands in it; fine them once, not every time.
	 */
	private static final long FINE_COOLDOWN_MILLIS = 30_000;
	private static final Map<UUID, Long> LAST_FINE_MILLIS = new ConcurrentHashMap<>();

	public static void passThrough(Level world, BlockPos blockPos, Player player, boolean isEntrance, boolean isExit, SoundEvent entrySound, SoundEvent entrySoundConcessionary, SoundEvent exitSound, SoundEvent exitSoundConcessionary, @Nullable SoundEvent failSound, boolean remindIfNoRecord, Consumer<EnumTicketBarrierOpen> callback) {
		MTR.sendMessageC2S(OperationProcessor.NEARBY_STATIONS, world.getServer(), world, new NearbyAreasRequest<>(MTR.blockPosToPosition(blockPos), 0), nearbyAreasResponse -> {
			final ObjectImmutableList<Station> stations = nearbyAreasResponse.getStations();
			if (stations.isEmpty()) {
				callback.accept(EnumTicketBarrierOpen.CLOSED);
			} else {
				final Station station = stations.getFirst();
				final boolean isEntering;

				if (isEntrance && isExit) {
					isEntering = !entered(
						getPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE),
						getPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE),
						getPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE)
					);
				} else {
					isEntering = isEntrance;
				}

				final boolean canOpen;
				if (isEntering) {
					canOpen = onEnter(world, station, player, remindIfNoRecord);
				} else {
					canOpen = onExit(world, station, player, remindIfNoRecord);
				}

				if (canOpen) {
					world.playSound(null, blockPos, isConcessionary(player) ? (isEntering ? entrySoundConcessionary : exitSoundConcessionary) : (isEntering ? entrySound : exitSound), SoundSource.BLOCKS, 1, 1);
				} else if (failSound != null) {
					world.playSound(null, blockPos, failSound, SoundSource.BLOCKS, 1, 1);
				}

				callback.accept(canOpen ? isConcessionary(player) ? EnumTicketBarrierOpen.OPEN_CONCESSIONARY : EnumTicketBarrierOpen.OPEN : EnumTicketBarrierOpen.CLOSED);
			}
		}, NearbyAreasResponse.class);
	}

	/**
	 * The player's transit card balance in Euro cents. Negative after a journey the card could not cover.
	 */
	public static long getBalance(Level world, Player player) {
		final MinecraftServer minecraftServer = world.getServer();
		return minecraftServer == null ? 0 : TransitCardPayments.getStore(minecraftServer).getBalance(player.getUUID());
	}

	/**
	 * Takes a fare or fine off the card and returns the balance afterwards.
	 */
	private static long charge(Level world, Player player, long cents) {
		final MinecraftServer minecraftServer = world.getServer();
		return minecraftServer == null ? 0 : TransitCardPayments.getStore(minecraftServer).charge(player.getUUID(), cents);
	}

	/**
	 * The fare evasion fine, at most once per {@link #FINE_COOLDOWN_MILLIS} per player.
	 *
	 * @return the cents actually charged: the fine, or {@code 0} within the cooldown
	 */
	private static long fine(Level world, Player player) {
		final long now = System.currentTimeMillis();
		final Long lastFine = LAST_FINE_MILLIS.get(player.getUUID());
		if (lastFine != null && now - lastFine < FINE_COOLDOWN_MILLIS) {
			return 0;
		}
		LAST_FINE_MILLIS.put(player.getUUID(), now);
		final long cents = Config.getServer().getFareEvasionFineCents();
		charge(world, player, cents);
		return cents;
	}

	private static int getPlayerScore(Level world, Player player, String objective, String title) {
		final Objective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		return scoreboardObjective == null ? 0 : world.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getGameProfile().getName()), scoreboardObjective).get();
	}

	private static void setPlayerScore(Level world, Player player, String objective, String title, int value) {
		final Objective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		if (scoreboardObjective != null) {
			world.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getGameProfile().getName()), scoreboardObjective).set(value);
		}
	}

	@Nullable
	private static Objective getOrCreateScoreboardObjective(Level world, String objective, String title) {
		try {
			return world.getScoreboard().addObjective(objective, ObjectiveCriteria.DUMMY, Component.literal(title), ObjectiveCriteria.RenderType.INTEGER, false, null);
		} catch (Exception ignored) {
		}
		return world.getScoreboard().getObjective(objective);
	}

	private static boolean onEnter(Level world, Station station, Player player, boolean remindIfNoRecord) {
		final boolean charging = EuroEconomyBridge.isAvailable();
		final int entryZone1 = getPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE);
		final int entryZone2 = getPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE);
		final int entryZone3 = getPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE);

		if (entered(entryZone1, entryZone2, entryZone3)) {
			if (remindIfNoRecord) {
				player.displayClientMessage(TranslationProvider.GUI_MTR_ALREADY_ENTERED.getText(), true);
				return false;
			} else {
				setPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE, 0);
				setPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE, 0);
				setPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE, 0);
				if (charging) {
					fine(world, player);
				}
			}
		}

		final long balance = getBalance(world, player);

		if (!charging || balance >= 0) {
			setPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE, encodeZone(station.getZone1()));
			setPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE, encodeZone(station.getZone2()));
			setPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE, encodeZone(station.getZone3()));
			player.displayClientMessage(charging
				? TranslationProvider.GUI_MTR_ENTER_BARRIER_CARD.getText(formatStationName(station), EuroEconomyBridge.format(balance))
				: TranslationProvider.GUI_MTR_ENTER_BARRIER_FREE.getText(formatStationName(station)), true);
			return true;
		} else {
			player.displayClientMessage(TranslationProvider.GUI_MTR_INSUFFICIENT_CARD_BALANCE.getText(EuroEconomyBridge.format(balance)), true);
			return false;
		}
	}

	private static boolean onExit(Level world, Station station, Player player, boolean remindIfNoRecord) {
		final boolean charging = EuroEconomyBridge.isAvailable();
		final int entryZone1 = getPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE);
		final int entryZone2 = getPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE);
		final int entryZone3 = getPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE);
		final boolean entered = entered(entryZone1, entryZone2, entryZone3);

		if (!entered && remindIfNoRecord) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_ALREADY_EXITED.getText(), true);
			return false;
		} else {
			setPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE, 0);
			setPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE, 0);
			setPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE, 0);

			if (!charging) {
				player.displayClientMessage(TranslationProvider.GUI_MTR_EXIT_BARRIER_FREE.getText(formatStationName(station)), true);
			} else if (entered) {
				final long fare = getFare(station, entryZone1, entryZone2, entryZone3, isConcessionary(player));
				final long balance = charge(world, player, fare);
				player.displayClientMessage(TranslationProvider.GUI_MTR_EXIT_BARRIER_CARD.getText(formatStationName(station), EuroEconomyBridge.format(fare), EuroEconomyBridge.format(balance)), true);
			} else {
				final long fine = fine(world, player);
				player.displayClientMessage(TranslationProvider.GUI_MTR_EXIT_BARRIER_CARD.getText(formatStationName(station), EuroEconomyBridge.format(fine), EuroEconomyBridge.format(getBalance(world, player))), true);
			}

			return true;
		}
	}

	/**
	 * The fare in Euro cents from the recorded entry zones to {@code station}: never negative, never above
	 * {@link TransitCardStore#LIMIT_CENTS}, whatever zone numbers a station was given.
	 */
	static long getFare(Station station, int entryZone1, int entryZone2, int entryZone3, boolean concessionary) {
		final long zonesCrossed = Math.abs(clampZone(station.getZone1()) - decodeZone(entryZone1)) + Math.abs(clampZone(station.getZone2()) - decodeZone(entryZone2)) + Math.abs(clampZone(station.getZone3()) - decodeZone(entryZone3));
		// At most 6,000,000 zones at EUR 1,000,000 each plus the base fare: well inside a long
		final long fare = Config.getServer().getBaseFareCents() + (long) Config.getServer().getZoneFareCents() * zonesCrossed;
		return Math.max(0, Math.min(concessionary ? (fare + 1) / 2 : fare, TransitCardStore.LIMIT_CENTS));
	}

	private static boolean isConcessionary(Player player) {
		return player.isCreative();
	}

	private static boolean entered(int entryZone1, int entryZone2, int entryZone3) {
		return entryZone1 != 0 && entryZone2 != 0 && entryZone3 != 0;
	}

	private static long clampZone(long zone) {
		return Math.max(-MAX_ZONE, Math.min(MAX_ZONE, zone));
	}

	private static int encodeZone(long zone) {
		final int clampedZone = (int) clampZone(zone);
		return clampedZone >= 0 ? clampedZone + 1 : clampedZone;
	}

	private static long decodeZone(int zone) {
		return clampZone(zone > 0 ? zone - 1 : zone);
	}

	private static String formatStationName(Station station) {
		return String.format("%s (%s/%s/%s)", station.getName().replace('|', ' '), station.getZone1(), station.getZone2(), station.getZone3());
	}

	public enum EnumTicketBarrierOpen implements StringRepresentable {

		CLOSED("closed"), PENDING("pending"), OPEN("open"), OPEN_CONCESSIONARY("open_concessionary");
		private final String name;

		EnumTicketBarrierOpen(String nameIn) {
			name = nameIn;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}
}
