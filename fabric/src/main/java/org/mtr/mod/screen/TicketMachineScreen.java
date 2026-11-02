package org.mtr.mod.screen;

import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.EuroEconomyBridge;
import org.mtr.mod.data.IGui;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.packet.PacketAddBalance;
import org.mtr.mod.packet.PacketRefundBalance;

/**
 * Loads the transit card from the Euro Economy bank, or refunds it there. The bank balance is a snapshot from when
 * the machine was opened; the server checks it again when a button is pressed.
 */
public class TicketMachineScreen extends MTRScreenBase implements IGui {

	private final int cardBalance;
	private final boolean bankAvailable;
	private final int bankBalance;
	private final ButtonWidgetExtension[] topUpButtons = new ButtonWidgetExtension[PacketAddBalance.getButtonCount()];
	private final ButtonWidgetExtension refundButton;
	private final MutableText cardBalanceText;
	private final MutableText bankBalanceText;

	private static final int BUTTON_WIDTH = 80;

	public TicketMachineScreen(int cardBalance, boolean bankAvailable, int bankBalance, String bankError) {
		super();
		this.cardBalance = cardBalance;
		this.bankAvailable = bankAvailable;
		this.bankBalance = bankBalance;

		for (int i = 0; i < topUpButtons.length; i++) {
			final int index = i;
			topUpButtons[i] = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TranslationProvider.GUI_MTR_ADD_VALUE.getMutableText(), button -> {
				InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketAddBalance(index));
				MinecraftClient.getInstance().openScreen(null);
			});
		}

		refundButton = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TranslationProvider.GUI_MTR_REFUND_CARD.getMutableText(), button -> {
			InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRefundBalance());
			MinecraftClient.getInstance().openScreen(null);
		});

		cardBalanceText = TranslationProvider.GUI_MTR_CARD_BALANCE.getMutableText(EuroEconomyBridge.format(cardBalance));
		bankBalanceText = bankAvailable ? TranslationProvider.GUI_MTR_BANK_BALANCE.getMutableText(EuroEconomyBridge.format(bankBalance)) : TranslationProvider.GUI_MTR_BANK_UNAVAILABLE.getMutableText(bankError);
	}

	@Override
	protected void init2() {
		super.init2();

		for (int i = 0; i < topUpButtons.length; i++) {
			IDrawing.setPositionAndWidth(topUpButtons[i], width - BUTTON_WIDTH, SQUARE_SIZE * (i + 1), BUTTON_WIDTH - TEXT_FIELD_PADDING);
		}
		IDrawing.setPositionAndWidth(refundButton, width - BUTTON_WIDTH, SQUARE_SIZE * (topUpButtons.length + 1), BUTTON_WIDTH - TEXT_FIELD_PADDING);

		for (final ButtonWidgetExtension button : topUpButtons) {
			addChild(new ClickableWidget(button));
		}
		addChild(new ClickableWidget(refundButton));
	}

	@Override
	public void tick2() {
		for (int i = 0; i < topUpButtons.length; i++) {
			topUpButtons[i].active = bankAvailable && bankBalance >= PacketAddBalance.getAddAmount(i);
		}
		refundButton.active = bankAvailable && cardBalance > 0;
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		graphicsHolder.drawText(cardBalanceText, TEXT_PADDING, TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		graphicsHolder.drawText(bankBalanceText, width - TEXT_PADDING - GraphicsHolder.getTextWidth(bankBalanceText), TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());

		for (int i = 0; i < topUpButtons.length; i++) {
			graphicsHolder.drawText(TranslationProvider.GUI_MTR_ADD_CARD_VALUE.getMutableText(EuroEconomyBridge.format(PacketAddBalance.getAddAmount(i))), TEXT_PADDING, (i + 1) * SQUARE_SIZE + TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		}
		graphicsHolder.drawText(TranslationProvider.GUI_MTR_REFUND_CARD_VALUE.getMutableText(EuroEconomyBridge.format(Math.max(0, cardBalance))), TEXT_PADDING, (topUpButtons.length + 1) * SQUARE_SIZE + TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());

		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}
}
