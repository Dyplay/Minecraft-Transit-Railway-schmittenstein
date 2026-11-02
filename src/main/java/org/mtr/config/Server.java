package org.mtr.config;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.generated.config.ServerSchema;

public final class Server extends ServerSchema {

	public Server(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
	}

	public int getWebserverPort() {
		return (int) webserverPort;
	}

	public boolean getUseThreadedSimulation() {
		return useThreadedSimulation;
	}

	public boolean getUseThreadedFileLoading() {
		return useThreadedFileLoading;
	}

	public boolean forceShutDownStrayThreads() {
		return forceShutDownStrayThreads;
	}

	/**
	 * Euro cents charged for any journey, on top of {@link #getZoneFareCents()} per zone crossed.
	 */
	public int getBaseFareCents() {
		return clampCents(baseFareCents);
	}

	public int getZoneFareCents() {
		return clampCents(zoneFareCents);
	}

	/**
	 * Euro cents charged for exiting without having entered, or entering twice.
	 */
	public int getFareEvasionFineCents() {
		return clampCents(fareEvasionFineCents);
	}

	private static int clampCents(long cents) {
		return (int) Math.max(0, Math.min(cents, 100_000_000));
	}
}
