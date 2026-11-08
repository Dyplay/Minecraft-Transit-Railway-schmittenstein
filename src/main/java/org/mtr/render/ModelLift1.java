package org.mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.mtr.data.IGui;
import org.mtr.tool.Drawing;

import java.util.function.UnaryOperator;

/**
 * The built-in lift car: floor, walls, ceiling, ceiling light and sliding doors, sized to the lift's width, depth and height.
 * Ported from the 4.0.x {@code ModelLift1}, which the 4.1 rewrite left out, so lifts were simulated and could be ridden but had no car.
 * The geometry, texture layout and placement of every part are unchanged, so lift textures made for 4.0.x still fit.
 * Coordinates are in pixels with Y pointing down, as in vanilla entity models, so the caller's transformations must already include the vehicle flip
 * from {@link RenderVehicles#getStoredMatrixTransformations}.
 */
public final class ModelLift1 {

	private final int heightCount;
	private final int heightOffset;
	private final int width;
	private final int depth;
	private final boolean isDoubleSided;
	private final float doorLeftZ;
	private final float doorRightZ;

	private static final int TEXTURE_SIZE = 128;
	private static final float PI = (float) Math.PI;
	private static final float HALF_PI = PI / 2;
	private static final float DOOR_OPEN_DISTANCE = 12;

	private static final ModelPart MAIN = createPart(cubes -> cubes.texOffs(0, 34).addBox(-8, 0, -8, 16, 0, 16));
	private static final ModelPart MAIN_CEILING = createPart(cubes -> cubes.texOffs(79, 44).addBox(-8, -32, -8, 16, 0, 16));
	private static final ModelPart MAIN_EDGE = createPart(cubes -> cubes
		.texOffs(18, 44).addBox(-4, 0, -8, 8, 0, 6)
		.texOffs(76, 33).addBox(-4, -32, -3, 8, 32, 1)
		.texOffs(28, 52).addBox(-4, -13, -4, 8, 1, 1)
		.texOffs(26, 50).addBox(-4, -2, -4, 8, 1, 1)
	);
	private static final ModelPart MAIN_EDGE_WALL = createPart(cubes -> cubes.texOffs(76, 33).addBox(-4, -40, -3, 8, 8, 1));
	private static final ModelPart MAIN_EDGE_CEILING = createPart(cubes -> cubes.texOffs(97, 44).addBox(-4, -32, -8, 8, 0, 6));
	private static final ModelPart MAIN_CORNER = createPart(
		cubes -> cubes
			.texOffs(20, 44).addBox(2, 0, -8, 6, 0, 6)
			.texOffs(112, 62).addBox(3, -32, -3, 5, 32, 1)
			.texOffs(29, 52).addBox(5, -13, -4, 3, 1, 1)
			.texOffs(27, 50).addBox(5, -2, -4, 3, 1, 1)
			.texOffs(104, 68).addBox(2, -32, -3, 1, 32, 1),
		new RotatedCubes(-HALF_PI, cubes -> cubes
			.texOffs(36, 50).addBox(-8, -2, -4, 3, 1, 1)
			.texOffs(38, 52).addBox(-8, -13, -4, 3, 1, 1)
			.texOffs(112, 62).addBox(-8, -32, -3, 5, 32, 1)
		)
	);
	private static final ModelPart MAIN_CORNER_WALL = createPart(
		cubes -> cubes
			.texOffs(112, 62).addBox(3, -40, -3, 5, 8, 1)
			.texOffs(104, 68).addBox(2, -40, -3, 1, 8, 1),
		new RotatedCubes(-HALF_PI, cubes -> cubes.texOffs(112, 62).addBox(-8, -40, -3, 5, 8, 1))
	);
	private static final ModelPart MAIN_CORNER_CEILING = createPart(cubes -> cubes.texOffs(99, 44).addBox(2, -32, -8, 6, 0, 6));
	private static final ModelPart MAIN_EXTERIOR = createPart(cubes -> cubes.texOffs(0, 17).addBox(-8, 0, -8, 16, 1, 16));
	private static final ModelPart MAIN_EXTERIOR_CEILING = createPart(cubes -> cubes.texOffs(0, 0).addBox(-8, -33, -8, 16, 1, 16));
	private static final ModelPart MAIN_EXTERIOR_EDGE = createPart(cubes -> cubes.texOffs(18, 27).addBox(-4, 0, -8, 8, 1, 6));
	private static final ModelPart MAIN_EXTERIOR_EDGE_CEILING = createPart(cubes -> cubes.texOffs(18, 10).addBox(-4, -33, -8, 8, 1, 6));
	private static final ModelPart MAIN_EXTERIOR_CORNER = createPart(cubes -> cubes.texOffs(20, 27).addBox(2, 0, -8, 6, 1, 6));
	private static final ModelPart MAIN_EXTERIOR_CORNER_CEILING = createPart(cubes -> cubes.texOffs(20, 10).addBox(2, -33, -8, 6, 1, 6));
	private static final ModelPart MAIN_LIGHT = createPart(cubes -> cubes.texOffs(79, 28).addBox(-8, -32.5F, -8, 16, 0, 16));
	private static final ModelPart DOOR = createPart(cubes -> cubes
		.texOffs(90, 66).addBox(-16, -32, 4, 4, 32, 3)
		.texOffs(14, 84).addBox(12, -32, 4, 4, 32, 3)
		.texOffs(20, 101).addBox(-16, 0, 0, 32, 0, 8)
	);
	private static final ModelPart DOOR_LEFT = createPart(cubes -> cubes.texOffs(52, 68).addBox(-12, -32, 6, 12, 32, 0));
	private static final ModelPart DOOR_RIGHT = createPart(cubes -> cubes.texOffs(28, 68).addBox(0, -32, 6, 12, 32, 0));
	private static final ModelPart DOOR_WALL = createPart(cubes -> cubes.texOffs(48, 0).addBox(-16, -40, 4, 32, 8, 3));
	private static final ModelPart DOOR_CEILING = createPart(cubes -> cubes.texOffs(20, 101).addBox(-16, -32, 0, 32, 0, 8));
	private static final ModelPart DOOR_EXTERIOR = createPart(cubes -> cubes
		.texOffs(0, 84).addBox(-16, -32, 4, 4, 32, 3)
		.texOffs(76, 66).addBox(12, -32, 4, 4, 32, 3)
		.texOffs(28, 109).addBox(-16, 0, 0, 32, 1, 8)
	);
	private static final ModelPart DOOR_LEFT_EXTERIOR = createPart(cubes -> cubes.texOffs(0, 50).addBox(-12, -32, 6, 12, 32, 2));
	private static final ModelPart DOOR_RIGHT_EXTERIOR = createPart(cubes -> cubes.texOffs(48, 34).addBox(0, -32, 6, 12, 32, 2));
	private static final ModelPart DOOR_WALL_EXTERIOR = createPart(cubes -> cubes.texOffs(48, 17).addBox(-16, -40, 5, 32, 8, 3));
	private static final ModelPart DOOR_CEILING_EXTERIOR = createPart(cubes -> cubes.texOffs(0, 119).addBox(-16, -33, 0, 32, 1, 8));
	private static final ModelPart WALL_PATCH = createPart(
		UnaryOperator.identity(),
		new RotatedCubes(-HALF_PI, cubes -> cubes
			.texOffs(108, 95).addBox(0, -32, 13, 4, 32, 1)
			.texOffs(30, 50).addBox(0, -2, 12, 4, 1, 1)
			.texOffs(32, 52).addBox(0, -13, 12, 4, 1, 1)
		),
		new RotatedCubes(HALF_PI, cubes -> cubes
			.texOffs(108, 95).addBox(-4, -32, 13, 4, 32, 1)
			.texOffs(30, 50).addBox(-4, -2, 12, 4, 1, 1)
			.texOffs(32, 52).addBox(-4, -13, 12, 4, 1, 1)
		)
	);
	private static final ModelPart WALL_PATCH_WALL = createPart(
		UnaryOperator.identity(),
		new RotatedCubes(-HALF_PI, cubes -> cubes.texOffs(108, 95).addBox(0, -40, 13, 4, 8, 1)),
		new RotatedCubes(HALF_PI, cubes -> cubes.texOffs(108, 95).addBox(-4, -40, 13, 4, 8, 1))
	);

	private ModelLift1(int height, int width, int depth, boolean isDoubleSided, double doorValue1, double doorValue2) {
		heightCount = height - 4;
		heightOffset = -heightCount * 8;
		this.width = width;
		this.depth = depth;
		this.isDoubleSided = isDoubleSided;
		doorLeftZ = getDoorOffset(doorValue1);
		doorRightZ = getDoorOffset(doorValue2);
	}

	/**
	 * Schedules the lift car for this frame: the ceiling light, the interior (lit as if the lights are on) and the exterior.
	 *
	 * @param storedMatrixTransformations the lift's position and rotation, from {@link RenderVehicles#getStoredMatrixTransformations}
	 * @param texture                     the lift style's texture
	 * @param light                       the light level outside the lift, for the exterior
	 * @param doorValue1                  how far the doors on the first side are open, from {@code 0} (closed) to {@code 1} (open)
	 * @param doorValue2                  how far the doors on the second side are open, from {@code 0} (closed) to {@code 1} (open)
	 * @param height                      the height of the lift in half blocks
	 * @param width                       the width of the lift in blocks
	 * @param depth                       the depth of the lift in blocks
	 * @param isDoubleSided               whether the lift has doors on both sides
	 */
	public static void render(StoredMatrixTransformations storedMatrixTransformations, ResourceLocation texture, int light, double doorValue1, double doorValue2, int height, int width, int depth, boolean isDoubleSided) {
		final ModelLift1 modelLift = new ModelLift1(height, width, depth, isDoubleSided, doorValue1, doorValue2);
		final StoredMatrixTransformations storedMatrixTransformationsNew = storedMatrixTransformations.copy();
		storedMatrixTransformationsNew.add(matrixStack -> matrixStack.translate(0, -1.5, 0));
		modelLift.schedule(storedMatrixTransformationsNew, texture, QueuedRenderLayer.LIGHT, Stage.LIGHT, IGui.DEFAULT_LIGHT);
		modelLift.schedule(storedMatrixTransformationsNew, texture, QueuedRenderLayer.INTERIOR, Stage.INTERIOR, IGui.MAX_LIGHT_INTERIOR);
		modelLift.schedule(storedMatrixTransformationsNew, texture, QueuedRenderLayer.EXTERIOR, Stage.EXTERIOR, light);
	}

	private void schedule(StoredMatrixTransformations storedMatrixTransformations, ResourceLocation texture, QueuedRenderLayer queuedRenderLayer, Stage stage, int light) {
		MainRenderer.scheduleRender(texture, false, queuedRenderLayer, (matrixStack, vertexConsumer, offset) -> {
			storedMatrixTransformations.transform(matrixStack, offset);
			render(new PartRenderer(matrixStack, vertexConsumer, light), stage);
			matrixStack.popPose();
		});
	}

	private void render(PartRenderer partRenderer, Stage stage) {
		final boolean isInterior = stage == Stage.INTERIOR;

		for (int i = 0; i <= width; i++) {
			for (int j = 0; j <= depth; j++) {
				final float x = (i - width / 2F) * 16;
				final float z = (j - depth / 2F) * 16;

				final boolean edge1X = i == 0;
				final boolean edge2X = i == width;
				final boolean edge1Z = j == 0;
				final boolean edge2Z = j == depth;
				final boolean inside = !edge1X && !edge2X && !edge1Z && !edge2Z;

				if (stage == Stage.LIGHT) {
					if (inside) {
						partRenderer.render(MAIN_LIGHT, x, heightOffset, z, 0);
					}
					continue;
				}

				if (inside) {
					partRenderer.render(isInterior ? MAIN : MAIN_EXTERIOR, x, 0, z, 0);
					partRenderer.render(isInterior ? MAIN_CEILING : MAIN_EXTERIOR_CEILING, x, heightOffset, z, 0);
				}

				if (edge1X && !edge2X && !edge1Z && !edge2Z) {
					renderEdge(partRenderer, isInterior, x, z - 4, -HALF_PI);
					renderEdge(partRenderer, isInterior, x, z + 4, -HALF_PI);
				}
				if (!edge1X && edge2X && !edge1Z && !edge2Z) {
					renderEdge(partRenderer, isInterior, x, z - 4, HALF_PI);
					renderEdge(partRenderer, isInterior, x, z + 4, HALF_PI);
				}
				if (!edge1X && !edge2X && !edge1Z && edge2Z && !isDoubleSided) {
					renderEdge(partRenderer, isInterior, x - 4, z, 0);
					renderEdge(partRenderer, isInterior, x + 4, z, 0);
				}

				final boolean hasBackCorners = width > 2 || !isDoubleSided;
				if (edge1X && !edge2X && !edge1Z && edge2Z && hasBackCorners) {
					renderCorner(partRenderer, isInterior, x, z, 0);
				}
				if (!edge1X && edge2X && !edge1Z && edge2Z && hasBackCorners) {
					renderCorner(partRenderer, isInterior, x, z, HALF_PI);
				}
				if (edge1X && !edge2X && edge1Z && !edge2Z && width > 2) {
					renderCorner(partRenderer, isInterior, x, z, -HALF_PI);
				}
				if (!edge1X && edge2X && edge1Z && !edge2Z && width > 2) {
					renderCorner(partRenderer, isInterior, x, z, PI);
				}
			}
		}

		if (stage == Stage.LIGHT) {
			return;
		}

		final float frontZ = 8 - depth * 8;
		final float backZ = -frontZ;
		final ModelPart doorLeft = isInterior ? DOOR_LEFT : DOOR_LEFT_EXTERIOR;
		final ModelPart doorRight = isInterior ? DOOR_RIGHT : DOOR_RIGHT_EXTERIOR;
		final ModelPart door = isInterior ? DOOR : DOOR_EXTERIOR;
		final ModelPart doorWall = isInterior ? DOOR_WALL : DOOR_WALL_EXTERIOR;
		final ModelPart doorCeiling = isInterior ? DOOR_CEILING : DOOR_CEILING_EXTERIOR;

		partRenderer.render(doorLeft, doorLeftZ, 0, frontZ, PI);
		partRenderer.render(doorRight, -doorLeftZ, 0, frontZ, PI);
		partRenderer.render(door, 0, 0, frontZ, PI);
		renderWall(partRenderer, doorWall, 0, frontZ, PI);
		partRenderer.render(doorCeiling, 0, heightOffset, frontZ, PI);

		if (isDoubleSided) {
			partRenderer.render(doorLeft, -doorRightZ, 0, backZ, 0);
			partRenderer.render(doorRight, doorRightZ, 0, backZ, 0);
			partRenderer.render(door, 0, 0, backZ, 0);
			renderWall(partRenderer, doorWall, 0, backZ, 0);
			partRenderer.render(doorCeiling, 0, heightOffset, backZ, 0);
		}

		if (isInterior && width == 2) {
			partRenderer.render(WALL_PATCH, 0, 0, frontZ, PI);
			renderWall(partRenderer, WALL_PATCH_WALL, 0, frontZ, PI);
			if (isDoubleSided) {
				partRenderer.render(WALL_PATCH, 0, 0, backZ, 0);
				renderWall(partRenderer, WALL_PATCH_WALL, 0, backZ, 0);
			}
		}

		// Wall pieces either side of the doors on wider lifts
		for (int i = 1; i < width - 2; i++) {
			final float x1 = i * 8 - width * 8 + 4;
			final float x2 = -x1;
			renderEdge(partRenderer, isInterior, x1, -depth * 8, PI);
			renderEdge(partRenderer, isInterior, x2, -depth * 8, PI);
			if (isDoubleSided) {
				renderEdge(partRenderer, isInterior, x1, depth * 8, 0);
				renderEdge(partRenderer, isInterior, x2, depth * 8, 0);
			}
		}
	}

	private void renderEdge(PartRenderer partRenderer, boolean isInterior, float x, float z, float rotationY) {
		partRenderer.render(isInterior ? MAIN_EDGE : MAIN_EXTERIOR_EDGE, x, 0, z, rotationY);
		if (isInterior) {
			renderWall(partRenderer, MAIN_EDGE_WALL, x, z, rotationY);
		}
		partRenderer.render(isInterior ? MAIN_EDGE_CEILING : MAIN_EXTERIOR_EDGE_CEILING, x, heightOffset, z, rotationY);
	}

	private void renderCorner(PartRenderer partRenderer, boolean isInterior, float x, float z, float rotationY) {
		partRenderer.render(isInterior ? MAIN_CORNER : MAIN_EXTERIOR_CORNER, x, 0, z, rotationY);
		if (isInterior) {
			renderWall(partRenderer, MAIN_CORNER_WALL, x, z, rotationY);
		}
		partRenderer.render(isInterior ? MAIN_CORNER_CEILING : MAIN_EXTERIOR_CORNER_CEILING, x, heightOffset, z, rotationY);
	}

	/**
	 * Stacks a wall piece once for every half block the lift is taller than the base model, which is two blocks tall.
	 */
	private void renderWall(PartRenderer partRenderer, ModelPart modelPart, float x, float z, float rotationY) {
		for (int i = 0; i < heightCount; i++) {
			partRenderer.render(modelPart, x, -i * 8, z, rotationY);
		}
	}

	private static float getDoorOffset(double doorValue) {
		return (float) (DOOR_OPEN_DISTANCE * Math.clamp(doorValue, 0, 1));
	}

	/**
	 * Builds a part whose cubes sit on the model's floor (24 pixels below its origin), optionally with groups of cubes turned about the vertical axis.
	 */
	private static ModelPart createPart(UnaryOperator<CubeListBuilder> cubes, RotatedCubes... rotatedCubesList) {
		final MeshDefinition meshDefinition = new MeshDefinition();
		final PartDefinition body = meshDefinition.getRoot().addOrReplaceChild("body", cubes.apply(CubeListBuilder.create()), PartPose.offsetAndRotation(0, 24, 0, 0, 0, 0));
		for (int i = 0; i < rotatedCubesList.length; i++) {
			body.addOrReplaceChild("rotated_" + i, rotatedCubesList[i].cubes.apply(CubeListBuilder.create()), PartPose.offsetAndRotation(0, 0, 0, 0, rotatedCubesList[i].rotationY, 0));
		}
		return LayerDefinition.create(meshDefinition, TEXTURE_SIZE, TEXTURE_SIZE).bakeRoot();
	}

	private enum Stage {LIGHT, INTERIOR, EXTERIOR}

	private record RotatedCubes(float rotationY, UnaryOperator<CubeListBuilder> cubes) {
	}

	private record PartRenderer(PoseStack matrixStack, VertexConsumer vertexConsumer, int light) {

		/**
		 * Draws a part with its origin moved to the given position (in pixels) and turned about the vertical axis.
		 */
		private void render(ModelPart modelPart, float x, float y, float z, float rotationY) {
			matrixStack.pushPose();
			matrixStack.translate(x / 16, y / 16, z / 16);
			Drawing.rotateYRadians(matrixStack, rotationY);
			modelPart.render(matrixStack, vertexConsumer, light, OverlayTexture.NO_OVERLAY);
			matrixStack.popPose();
		}
	}
}
