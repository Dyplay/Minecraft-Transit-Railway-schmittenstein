package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.Init;
import org.mtr.mod.data.EuroEconomyBridge;
import org.mtr.mod.data.TicketSystem;
import org.mtr.mod.packet.PacketOpenTicketMachineScreen;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public class BlockTicketMachine extends BlockDirectionalDoubleBlockBase {

	public BlockTicketMachine(BlockSettings blockSettings) {
		super(blockSettings);
	}

	@Nonnull
	@Override
	public ActionResult onUse2(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (!world.isClient()) {
			final MinecraftServer minecraftServer = world.getServer();
			final UUID uuid = player.getUuid();
			final int cardBalance = TicketSystem.getBalance(world, player);
			// The screen shows the bank balance too, so it opens once the Euro Economy answers
			EuroEconomyBridge.getBalance(uuid).whenComplete((bankBalance, throwable) -> minecraftServer.execute(() -> {
				final ServerPlayerEntity serverPlayerEntity = minecraftServer.getPlayerManager().getPlayer(uuid);
				if (serverPlayerEntity != null) {
					Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketOpenTicketMachineScreen(
							cardBalance,
							throwable == null ? bankBalance : null,
							throwable == null ? "" : EuroEconomyBridge.getErrorMessage(throwable)
					));
				}
			}));
		}
		return ActionResult.SUCCESS;
	}

	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		final Direction facing = IBlock.getStatePropertySafe(state, FACING);
		final int height = IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER ? 14 : 16;
		return IBlock.getVoxelShapeByDirection(0, 0, 2, 16, height, 14, facing);
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(HALF);
	}
}
