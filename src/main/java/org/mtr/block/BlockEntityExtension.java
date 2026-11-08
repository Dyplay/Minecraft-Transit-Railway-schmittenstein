package org.mtr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class BlockEntityExtension extends BlockEntity {

	public BlockEntityExtension(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public final Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public final CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	/**
	 * Marks the block entity for saving and, on the server, sends its data to every client that has the chunk loaded.
	 * Vanilla {@link BlockEntity#setChanged()} only marks the chunk for saving, so changes made by a player (configuration screens, links made with the lift buttons
	 * link connector, dyeing a PIDS) would otherwise reach clients only when the chunk is next sent, for example after rejoining.
	 */
	@Override
	public void setChanged() {
		super.setChanged();
		if (level != null && !level.isClientSide()) {
			final BlockState blockState = getBlockState();
			level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected final void loadAdditional(CompoundTag nbtCompound, HolderLookup.Provider registries) {
		super.loadAdditional(nbtCompound, registries);
		readNbt(nbtCompound);
	}

	@Override
	protected final void saveAdditional(CompoundTag nbtCompound, HolderLookup.Provider registries) {
		super.saveAdditional(nbtCompound, registries);
		writeNbt(nbtCompound);
	}

	protected void readNbt(CompoundTag nbtCompound) {
	}

	protected void writeNbt(CompoundTag nbtCompound) {
	}
}
