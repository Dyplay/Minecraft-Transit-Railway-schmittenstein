package org.mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

import java.util.function.Consumer;

/**
 * Fare collection and player balance management.
 * Handles ticket barriers, balance tracking via scoreboards, zone-based fares, and fare evasion consequences.
 * <p>
 * Fares are paid from a transit card, loaded with Euro from the Euro Economy bank at ticket machines (see
 * {@link EuroEconomyBridge}). The card balance lives in a scoreboard objective, in Euro cents. Emeralds are no longer
 * taken. The old emerald-bought balance ({@code mtr_balance}) is left alone and ignored: it was never paid for in Euro,
 * so it is not carried over onto the card, where it could be refunded into the bank.
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
	public static int getBalance(Level world, Player player) {
		return getBalance(world, player.getGameProfile().getName());
	}

	/**
	 * By name, for payments that settle after the player may have logged out (the scoreboard is keyed by name).
	 */
	public static int getBalance(Level world, String playerName) {
		return getPlayerScore(world, playerName, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE);
	}

	public static void addBalance(Level world, Player player, int cents) {
		addBalance(world, player.getGameProfile().getName(), cents);
	}

	public static void addBalance(Level world, String playerName, int cents) {
		incrementPlayerScore(world, playerName, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE, cents);
	}

	private static int getPlayerScore(Level world, Player player, String objective, String title) {
		return getPlayerScore(world, player.getGameProfile().getName(), objective, title);
	}

	private static int getPlayerScore(Level world, String playerName, String objective, String title) {
		final Objective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		return scoreboardObjective == null ? 0 : world.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(playerName), scoreboardObjective).get();
	}

	private static void setPlayerScore(Level world, Player player, String objective, String title, int value) {
		final Objective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		if (scoreboardObjective != null) {
			world.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getGameProfile().getName()), scoreboardObjective).set(value);
		}
	}

	private static void incrementPlayerScore(Level world, Player player, String objective, String title, int value) {
		incrementPlayerScore(world, player.getGameProfile().getName(), objective, title, value);
	}

	private static void incrementPlayerScore(Level world, String playerName, String objective, String title, int value) {
		final Objective scoreboardObjective = getOrCreateScoreboardObjective(world, objective, title);
		if (scoreboardObjective != null) {
			world.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(playerName), scoreboardObjective).add(value);
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
				incrementPlayerScore(world, player, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE, -Config.getServer().getFareEvasionFineCents());
			}
		}

		final int balance = getPlayerScore(world, player, BALANCE_OBJECTIVE, BALANCE_OBJECTIVE_TITLE);

		if (balance >= 0) {
			setPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE, encodeZone((int) station.getZone1()));
			setPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE, encodeZone((int) station.getZone2()));
			setPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE, encodeZone((int) station.getZone3()));
			player.displayClientMessage(TranslationProvider.GUI_MTR_ENTER_BARRIER_CARD.getText(formatStationName(station), EuroEconomyBridge.format(balance)), true);
			return true;
		} else {
			player.displayClientMessage(TranslationProvider.GUI_MTR_INSUFFICIENT_CARD_BALANCE.getText(EuroEconomyBridge.format(balance)), true);
			return false;
		}
	}

	private static boolean onExit(Level world, Station station, Player player, boolean remindIfNoRecord) {
		final int entryZone1 = getPlayerScore(world, player, ENTRY_ZONE_1_OBJECTIVE, ENTRY_ZONE_1_TITLE);
		final int entryZone2 = getPlayerScore(world, player, ENTRY_ZONE_2_OBJECTIVE, ENTRY_ZONE_2_TITLE);
		final int entryZone3 = getPlayerScore(world, player, ENTRY_ZONE_3_OBJECTIVE, ENTRY_ZONE_3_TITLE);
		final boolean entered = entered(entryZone1, entryZone2, entryZone3);

		if (!entered && remindIfNoRecord) {
			player.displayClientMessage(TranslationProvider.GUI_MTR_ALREADY_EXITED.getText(), true);
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
			player.displayClientMessage(TranslationProvider.GUI_MTR_EXIT_BARRIER_CARD.getText(formatStationName(station), EuroEconomyBridge.format(finalFare), EuroEconomyBridge.format(balance)), true);
			return true;
		}
	}

	private static boolean isConcessionary(Player player) {
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
