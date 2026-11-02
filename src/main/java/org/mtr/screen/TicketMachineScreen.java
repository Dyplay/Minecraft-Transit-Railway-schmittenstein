package org.mtr.screen;

import gg.essential.elementa.components.ScrollComponent;
import gg.essential.elementa.components.UIWrappedText;
import gg.essential.elementa.constraints.*;
import org.mtr.data.EuroEconomyBridge;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.packet.PacketAddBalance;
import org.mtr.packet.PacketRefundBalance;
import org.mtr.registry.RegistryClient;
import org.mtr.tool.GuiHelper;
import org.mtr.widget.BackgroundComponent;
import org.mtr.widget.ButtonComponent;
import org.mtr.widget.ScrollPanelComponent;

import java.awt.*;

/**
 * Loads the transit card with Euro from the Euro Economy bank, or refunds it there, from a vertically stacked list of
 * preset amounts. Balances come from the server; after a button is pressed the buttons stay disabled until the server
 * answers with the settled balances (see {@link #setBalances}).
 */
public final class TicketMachineScreen extends WindowBase {

	private int cardBalance;
	private boolean bankAvailable;
	private int bankBalance;
	private String bankError;
	/**
	 * A top-up or refund is in flight.
	 */
	private boolean pending;

	private final UIWrappedText cardBalanceText;
	private final UIWrappedText bankBalanceText;
	private final ButtonComponent[] topUpButtons = new ButtonComponent[PacketAddBalance.getButtonCount()];
	private final ButtonComponent refundButton;

	/**
	 * @param cardBalance   transit card balance in Euro cents
	 * @param bankAvailable whether the Euro Economy bank could be reached
	 * @param bankBalance   bank balance in cents, if available
	 * @param bankError     why the bank could not be reached, if not available
	 */
	public TicketMachineScreen(int cardBalance, boolean bankAvailable, int bankBalance, String bankError) {
		this.cardBalance = cardBalance;
		this.bankAvailable = bankAvailable;
		this.bankBalance = bankBalance;
		this.bankError = bankError;
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
				pending = true;
				updateComponents();
			});
		}

		refundButton = (ButtonComponent) new ButtonComponent(false)
			.setChildOf(scrollComponent)
			.setY(new SiblingConstraint())
			.setWidth(new RelativeConstraint());

		refundButton.onClick(() -> {
			RegistryClient.sendPacketToServer(new PacketRefundBalance());
			pending = true;
			updateComponents();
		});

		updateComponents();
	}

	/**
	 * Shows the balances the server settled on after a top-up or refund, and enables the buttons again.
	 */
	public void setBalances(int cardBalance, boolean bankAvailable, int bankBalance, String bankError) {
		this.cardBalance = cardBalance;
		this.bankAvailable = bankAvailable;
		this.bankBalance = bankBalance;
		this.bankError = bankError;
		pending = false;
		updateComponents();
	}

	private void updateComponents() {
		cardBalanceText.setText(TranslationProvider.GUI_MTR_CARD_BALANCE.getString(EuroEconomyBridge.format(cardBalance)));
		bankBalanceText.setText(bankAvailable ? TranslationProvider.GUI_MTR_BANK_BALANCE.getString(EuroEconomyBridge.format(bankBalance)) : TranslationProvider.GUI_MTR_BANK_UNAVAILABLE.getString(bankError));

		for (int i = 0; i < topUpButtons.length; i++) {
			topUpButtons[i].setDisabled(pending || !bankAvailable || bankBalance < PacketAddBalance.getAddBalanceAmount(i));
		}

		refundButton.setText(TranslationProvider.GUI_MTR_REFUND_CARD_VALUE.getString(EuroEconomyBridge.format(Math.max(0, cardBalance))));
		refundButton.setDisabled(pending || !bankAvailable || cardBalance <= 0);
	}
}
