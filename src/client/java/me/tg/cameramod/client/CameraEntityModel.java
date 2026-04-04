// Made with Blockbench 4.12.6
// Exported for Minecraft version 1.17+ for Yarn
// Paste this class into your mod and generate all required imports

package me.tg.cameramod.client;

import net.minecraft.client.model.*;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;

public class CameraEntityModel extends EntityModel<EntityRenderState> {
	static ModelPart bb_main = null;
	public CameraEntityModel(ModelPart root) {
        super(root);
        bb_main = root.getChild("bb_main");
	}
	public static TexturedModelData getTexturedModelData() {
		ModelData modelData = new ModelData();
		ModelPartData modelPartData = modelData.getRoot();
		ModelPartData bb_main = modelPartData.addChild("bb_main", ModelPartBuilder.create().uv(0, 0).cuboid(-4.0F, -26.0F, -4.0F, 8.0F, 8.0F, 8.0F, new Dilation(0.0F)), ModelTransform.origin(0.0F, 24.0F, 0.0F));

		ModelPartData cube_r1 = bb_main.addChild("cube_r1", ModelPartBuilder.create().uv(16, 16).cuboid(-1.0F, -19.0F, -1.0F, 2.0F, 19.0F, 2.0F, new Dilation(0.0F)), ModelTransform.of(5.0F, 0.0F, -4.0F, -0.0872F, -0.0038F, -0.1307F));

		ModelPartData cube_r2 = bb_main.addChild("cube_r2", ModelPartBuilder.create().uv(8, 16).cuboid(-1.0F, -19.0F, -1.0F, 2.0F, 19.0F, 2.0F, new Dilation(0.0F)), ModelTransform.of(-5.0F, 0.0F, -4.0F, -0.0872F, 0.0038F, 0.1307F));

		ModelPartData cube_r3 = bb_main.addChild("cube_r3", ModelPartBuilder.create().uv(0, 16).cuboid(-1.0F, -19.0F, -1.0F, 2.0F, 19.0F, 2.0F, new Dilation(0.0F)), ModelTransform.of(0.0F, 0.0F, 6.0F, 0.1745F, 0.0F, 0.0F));
		return TexturedModelData.of(modelData, 64, 64);
	}
}