package org.mtr.screen;

import gg.essential.elementa.components.ScrollComponent;
import gg.essential.elementa.components.UIWrappedText;
import gg.essential.elementa.constraints.*;
import org.mtr.data.EuroEconomyBridge;
import org.mtr.data.TransitCardStore;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.packet.PacketAddBalance;
import org.mtr.packet.PacketOpenTicketMachineScreen;
import org.mtr.packet.PacketRefundBalance;
import org.mtr.registry.RegistryClient;
import org.mtr.tool.GuiHelper;
import org.mtr.widget.BackgroundComponent;
import org.mtr.widget.ButtonComponent;
import org.mtr.widget.ScrollPanelComponent;

import java.awt.*;

/**
 * Loads the transit card with Euro from the Euro Economy bank, or refunds it there, from a vertically stacked list of
 * preset amounts. It opens with the card balance and fills in the bank balance when the server has it; after a button
 * is pressed, every button stays disabled until the server sends the settled balances (see {@link #setState}).
 */
public final class TicketMachineScreen extends WindowBase {

	private int cardBalance;
	private int bankState;
	private int bankBalance;
	private String bankError;
	/**
	 * The server still has a payment for this player waiting for the bank.
	 */
	private boolean paymentPending;
	/**
	 * A button was pressed and the server has not answered yet.
	 */
	private boolean awaitingServer;

	private final UIWrappedText cardBalanceText;
	private final UIWrappedText bankBalanceText;
	private final UIWrappedText pendingText;
	private final ButtonComponent[] topUpButtons = new ButtonComponent[PacketAddBalance.getButtonCount()];
	private final ButtonComponent refundButton;

	/**
	 * @param cardBalance    transit card balance in Euro cents
	 * @param bankState      a {@code PacketOpenTicketMachineScreen.BANK_*} state
	 * @param bankBalance    bank balance in cents, if available
	 * @param bankError      why the bank could not be reached, if unavailable
	 * @param paymentPending a top-up or refund is still waiting for the bank
	 */
	public TicketMachineScreen(int cardBalance, int bankState, int bankBalance, String bankError, boolean paymentPending) {
		final BackgroundComponent backgroundComponent = new BackgroundComponent(getWindow(), ObjectImmutableList.of());

		cardBalanceText = (UIWrappedText) new UIWrappedText("", false)
			.setChildOf(backgroundComponent)
			.setWidth(new RelativeConstraint())
			.setColor(new Color(GuiHelper.MINECRAFT_GUI_TITLE_TEXT_COLOR));

		bankBalanceText = (UIWrappedText) new UIWrappedText("", false)
			.setChildOf(backgroundComponent)
			.setY(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new RelativeConstraint())
			.setColor(new Color(GuiHelper.MINECRAFT_GUI_TITLE_TEXT_COLOR));

		pendingText = (UIWrappedText) new UIWrappedText("", false)
			.setChildOf(backgroundComponent)
			.setY(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new RelativeConstraint())
			.setColor(new Color(GuiHelper.MINECRAFT_GUI_TITLE_TEXT_COLOR));

		final ScrollComponent scrollComponent = ((ScrollPanelComponent) new ScrollPanelComponent(true)
			.setChildOf(backgroundComponent)
			.setY(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new RelativeConstraint())
			.setHeight(new SubtractiveConstraint(new FillConstraint(), new PixelConstraint(GuiHelper.DEFAULT_PADDING * 2)))).contentContainer;

		for (int i = 0; i < topUpButtons.length; i++) {
			topUpButtons[i] = (ButtonComponent) new ButtonComponent(false)
				.setChildOf(scrollComponent)
				.setY(new SiblingConstraint())
				.setWidth(new RelativeConstraint());

			topUpButtons[i].setText(TranslationProvider.GUI_MTR_ADD_CARD_VALUE.getString(EuroEconomyBridge.format(PacketAddBalance.getAddBalanceAmount(i))));
			final int index = i;
			topUpButtons[i].onClick(() -> {
				RegistryClient.sendPacketToServer(new PacketAddBalance(index));
				awaitingServer = true;
				updateComponents();
			});
		}

		refundButton = (ButtonComponent) new ButtonComponent(false)
			.setChildOf(scrollComponent)
			.setY(new SiblingConstraint())
			.setWidth(new RelativeConstraint());

		refundButton.onClick(() -> {
			RegistryClient.sendPacketToServer(new PacketRefundBalance());
			awaitingServer = true;
			updateComponents();
		});

		setState(cardBalance, bankState, bankBalance, bankError, paymentPending);
	}

	/**
	 * Shows what the server sent, and enables the buttons again.
	 */
	public void setState(int cardBalance, int bankState, int bankBalance, String bankError, boolean paymentPending) {
		this.cardBalance = cardBalance;
		this.bankState = bankState;
		this.bankBalance = bankBalance;
		this.bankError = bankError;
		this.paymentPending = paymentPending;
		awaitingServer = false;
		updateComponents();
	}

	private void updateComponents() {
		final boolean bankAvailable = bankState == PacketOpenTicketMachineScreen.BANK_AVAILABLE;
		final boolean canPay = bankAvailable && !paymentPending && !awaitingServer;

		cardBalanceText.setText(TranslationProvider.GUI_MTR_CARD_BALANCE.getString(EuroEconomyBridge.format(cardBalance)));
		bankBalanceText.setText(switch (bankState) {
			case PacketOpenTicketMachineScreen.BANK_AVAILABLE -> TranslationProvider.GUI_MTR_BANK_BALANCE.getString(EuroEconomyBridge.format(bankBalance));
			case PacketOpenTicketMachineScreen.BANK_UNAVAILABLE -> TranslationProvider.GUI_MTR_BANK_UNAVAILABLE.getString(bankError);
			default -> TranslationProvider.GUI_MTR_BANK_LOADING.getString();
		});
		pendingText.setText(paymentPending ? TranslationProvider.GUI_MTR_CARD_PAYMENT_PENDING.getString() : "");

		for (int i = 0; i < topUpButtons.length; i++) {
			final int amount = PacketAddBalance.getAddBalanceAmount(i);
			topUpButtons[i].setDisabled(!canPay || bankBalance < amount || (long) cardBalance + amount > TransitCardStore.LIMIT_CENTS);
		}

		refundButton.setText(TranslationProvider.GUI_MTR_REFUND_CARD_VALUE.getString(EuroEconomyBridge.format(Math.max(0, cardBalance))));
		refundButton.setDisabled(!canPay || cardBalance <= 0);
	}
}
