package org.mtr.mod.data;

import org.mtr.core.data.Station;
import org.mtr.core.operation.NearbyAreasRequest;
import org.mtr.core.operation.NearbyAreasResponse;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.ScoreboardCriteria;
import org.mtr.mapping.mapper.ScoreboardHelper;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.config.Config;
import org.mtr.mod.generated.lang.TranslationProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Fares are paid from a transit card, loaded with Euro from the Euro Economy bank at ticket machines (see
 * {@link EuroEconomyBridge}). The card balance lives in a scoreboard objective, in Euro cents.
 * <p>
 * Emeralds are no longer taken. The old emerald-bought balance ({@code mtr_balance}) is left alone and ignored: it was
 * never paid for in Euro, so it is not carried over onto the card, where it could be refunded into the bank.
 */
public class TicketSystem {

	public static final String BALANCE_OBJECTIVE = "mtr_card_cents";
	public static final String BALANCE_OBJECTIVE_TITLE = "Transit Card (cents)";
	private static final String ENTRY_ZONE_1_OBJECTIVE = "mtr_entry_zone_1";
	private static final String ENTRY_ZONE_1_TITLE = "Entry Zone 1";
	private static final String ENTRY_ZONE_2_OBJECTIVE = "mtr_entry_zone_2";
	private static final String ENTRY_ZONE_2_TITLE = "Entry Zone 2";
	private static final String ENTRY_ZONE_3_OBJECTIVE = "mtr_entry_zone_3";
	private static final String ENTRY_ZONE_3_TITLE = "Entry Zone 3";

	public static void passThrough(World world, BlockPos blockPos, PlayerEntity player, boolean isEntrance, boolean isExit, SoundEvent entrySound, SoundEvent entrySoundConcessionary, SoundEvent exitSound, SoundEvent exitSoundConcessionary, @Nullable SoundEvent failSound, boolean remindIfNoRecord, Consumer<EnumTicketBarrierOpen> callback) {
		Init.sendMessageC2S(OperationProcessor.NEARBY_STATIONS, world.getServer(), world, new NearbyAreasRequest<>(Init.blockPosToPosition(blockPos), 0), nearbyAreasResponse -> {
			final ObjectImmutableList<Station> stations = nearbyAreasResponse.getStations();
			if (stations.isEmpty()) {
				callback.accept(EnumTicketBarrierOpen.CLOSED);
			} else {
				final Station station = stations.get(0);
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
					world.playSound((PlayerEntity) null, blockPos, isConcessionary(player) ? (isEntering ? entrySoundConcessionary : exitSoundConcessionary) : (isEntering ? entrySound : exitSound), SoundCategory.BLOCKS, 1, 1);
				} else if (failSound != null) {
					world.playSound((PlayerEntity) null, blockPos, failSound, SoundCategory.BLOCKS, 1, 1);
				}

				callback.accept(canOpen ? isConcessionary(player) ? EnumTicketBarrierOpen.OPEN_CONCESSIONARY : EnumTicketBarrierOpen.OPEN : EnumTicketBarrierOpen.CLOSED);
			}
		}, NearbyAreasResponse.class);
	}

	/**
	 * The player's transit card balance in Euro cents. Negative after a journey the card could not cover.
	 */
	public static int getBalance(World world, PlayerEntity player) {
		return getBalance(world, player.getGameProfile().getName());
	}

	/**
	 * By name, for payments that settle after the player may have logged out (the scoreboard is keyed by name).
	 */
	public static int getBalance(World world, String playerName) {
		return getPlayerScore(world, playerName, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE);
	}

	public static void addBalance(World world, PlayerEntity player, int cents) {
		addBalance(world, player.getGameProfile().getName(), cents);
	}

	public static void addBalance(World world, String playerName, int cents) {
		incrementPlayerScore(world, playerName, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE, cents);
	}

	private static int getPlayerScore(World world, PlayerEntity player, String objective, String title) {
		return getPlayerScore(world, player.getGameProfile().getName(), objective, title);
	}

	private static int getPlayerScore(World world, String playerName, String objective, String title) {
		final ScoreboardObjective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		return scoreboardObjective == null ? 0 : ScoreboardHelper.getPlayerScore(world.getScoreboard(), playerName, scoreboardObjective);
	}

	private static void setPlayerScore(World world, PlayerEntity player, String objective, String title, int value) {
		final ScoreboardObjective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		if (scoreboardObjective != null) {
			ScoreboardHelper.setPlayerScore(world.getScoreboard(), player.getGameProfile().getName(), scoreboardObjective, value);
		}
	}

	private static void incrementPlayerScore(World world, PlayerEntity player, String objective, String title, int value) {
		incrementPlayerScore(world, player.getGameProfile().getName(), objective, title, value);
	}

	private static void incrementPlayerScore(World world, String playerName, String objective, String title, int value) {
		final ScoreboardObjective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		if (scoreboardObjective != null) {
			ScoreboardHelper.incrementPlayerScore(world.getScoreboard(), playerName, scoreboardObjective, value);
		}
	}

	@Nullable
	private static ScoreboardObjective getOrCreateScoreboardObjective(World world, String objective, String title) {
		try {
			return ScoreboardHelper.addObjective(world.getScoreboard(), objective, ScoreboardCriteria.DUMMY, new Text(TextHelper.literal(title).data), ScoreboardCriterionRenderType.INTEGER);
		} catch (Exception ignored) {
		}
		return ScoreboardHelper.getScoreboardObjective(world.getScoreboard(), objective);
	}

	private static boolean onEnter(World world, Station station, PlayerEntity player, boolean remindIfNoRecord) {
		final int entryZone1 = getPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE);
		final int entryZone2 = getPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE);
		final int entryZone3 = getPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE);

		if (entered(entryZone1, entryZone2, entryZone3)) {
			if (remindIfNoRecord) {
				player.sendMessage(TranslationProvider.GUI_MTR_ALREADY_ENTERED.getText(), true);
				return false;
			} else {
				setPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE, 0);
				setPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE, 0);
				setPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE, 0);
				incrementPlayerScore(world, player, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE, -Config.getServer().getFareEvasionFineCents());
			}
		}

		final int balance = getPlayerScore(world, player, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE);

		if (balance >= 0) {
			setPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE, encodeZone((int) station.getZone1()));
			setPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE, encodeZone((int) station.getZone2()));
			setPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE, encodeZone((int) station.getZone3()));
			player.sendMessage(TranslationProvider.GUI_MTR_ENTER_BARRIER_CARD.getText(formatStationName(station), EuroEconomyBridge.format(balance)), true);
			return true;
		} else {
			player.sendMessage(TranslationProvider.GUI_MTR_INSUFFICIENT_CARD_BALANCE.getText(EuroEconomyBridge.format(balance)), true);
			return false;
		}
	}

	private static boolean onExit(World world, Station station, PlayerEntity player, boolean remindIfNoRecord) {
		final int entryZone1 = getPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE);
		final int entryZone2 = getPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE);
		final int entryZone3 = getPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE);
		final boolean entered = entered(entryZone1, entryZone2, entryZone3);

		if (!entered && remindIfNoRecord) {
			player.sendMessage(TranslationProvider.GUI_MTR_ALREADY_EXITED.getText(), true);
			return false;
		} else {
			final long zonesCrossed = Math.abs(station.getZone1() - decodeZone(entryZone1)) + Math.abs(station.getZone2() - decodeZone(entryZone2)) + Math.abs(station.getZone3() - decodeZone(entryZone3));
			final long fare = Config.getServer().getBaseFareCents() + (long) Config.getServer().getZoneFareCents() * zonesCrossed;
			// Capped so a journey across absurd zone numbers cannot overflow the card's int score
			final long finalFare = Math.min(entered ? isConcessionary(player) ? (fare + 1) / 2 : fare : Config.getServer().getFareEvasionFineCents(), Integer.MAX_VALUE / 2);
			setPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE, 0);
			setPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE, 0);
			setPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE, 0);
			incrementPlayerScore(world, player, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE, (int) -finalFare);
			final int balance = getPlayerScore(world, player, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE);
			player.sendMessage(TranslationProvider.GUI_MTR_EXIT_BARRIER_CARD.getText(formatStationName(station), EuroEconomyBridge.format(finalFare), EuroEconomyBridge.format(balance)), true);
			return true;
		}
	}

	private static boolean isConcessionary(PlayerEntity player) {
		return player.isCreative();
	}

	private static boolean entered(int entryZone1, int entryZone2, int entryZone3) {
		return entryZone1 != 0 && entryZone2 != 0 && entryZone3 != 0;
	}

	private static int encodeZone(int zone) {
		return zone >= 0 ? zone + 1 : zone;
	}

	private static int decodeZone(int zone) {
		return zone > 0 ? zone - 1 : zone;
	}

	private static String formatStationName(Station station) {
		return String.format("%s (%s/%s/%s)", station.getName().replace('|', ' '), station.getZone1(), station.getZone2(), station.getZone3());
	}

	public enum EnumTicketBarrierOpen implements StringIdentifiable {

		CLOSED("closed"), PENDING("pending"), OPEN("open"), OPEN_CONCESSIONARY("open_concessionary");
		private final String name;

		EnumTicketBarrierOpen(String nameIn) {
			name = nameIn;
		}

		@Nonnull
		@Override
		public String asString2() {
			return name;
		}
	}
}
