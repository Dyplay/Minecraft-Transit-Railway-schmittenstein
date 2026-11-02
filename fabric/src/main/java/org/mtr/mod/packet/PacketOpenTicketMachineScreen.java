package org.mtr.mod.packet;

import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;

public final class PacketOpenTicketMachineScreen extends PacketHandler {

	private final int cardBalance;
	private final boolean bankAvailable;
	private final int bankBalance;
	private final String bankError;

	public PacketOpenTicketMachineScreen(PacketBufferReceiver packetBufferReceiver) {
		cardBalance = packetBufferReceiver.readInt();
		bankAvailable = packetBufferReceiver.readBoolean();
		bankBalance = packetBufferReceiver.readInt();
		bankError = packetBufferReceiver.readString();
	}

	/**
	 * @param cardBalance transit card balance in Euro cents
	 * @param bankBalance Euro Economy bank balance in cents, or {@code null} if the bank could not be reached
	 * @param bankError   why the bank could not be reached
	 */
	public PacketOpenTicketMachineScreen(int cardBalance, Integer bankBalance, String bankError) {
		this.cardBalance = cardBalance;
		bankAvailable = bankBalance != null;
		this.bankBalance = bankBalance == null ? 0 : bankBalance;
		this.bankError = bankError == null ? "" : bankError;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(cardBalance);
		packetBufferSender.writeBoolean(bankAvailable);
		packetBufferSender.writeInt(bankBalance);
		packetBufferSender.writeString(bankError);
	}

	@Override
	public void runClient() {
		ClientPacketHelper.openTicketMachineScreen(cardBalance, bankAvailable, bankBalance, bankError);
	}
}
