package me.tg.cameramod.client;

import net.minecraft.client.model.*;
import net.minecraft.client.render.entity.model.EntityModel;

public class CameraEntityModel extends EntityModel<CameraEntityRenderState> {
    private final ModelPart head;
    private final ModelPart leg1;
    private final ModelPart leg2;
    private final ModelPart leg3;

    public CameraEntityModel(ModelPart root) {
        super(root);
        ModelPart bbMain = root.getChild("bb_main");
        this.head = bbMain.getChild("head");
        this.leg1 = bbMain.getChild("cube_r1");
        this.leg2 = bbMain.getChild("cube_r2");
        this.leg3 = bbMain.getChild("cube_r3");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData modelData = new ModelData();
        ModelPartData modelPartData = modelData.getRoot();
        ModelPartData bbMain = modelPartData.addChild("bb_main",
                ModelPartBuilder.create(),
                ModelTransform.origin(0.0F, 24.0F, 0.0F));

        // Head (camera box) — separate part so we can rotate it
        bbMain.addChild("head",
                ModelPartBuilder.create()
                        .uv(0, 0).cuboid(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new Dilation(0.0F)),
                ModelTransform.origin(0.0F, -18.0F, 0.0F));

        // Legs (tripod) — static
        bbMain.addChild("cube_r1",
                ModelPartBuilder.create()
                        .uv(16, 16).cuboid(-1.0F, -19.0F, -1.0F, 2.0F, 19.0F, 2.0F, new Dilation(0.0F)),
                ModelTransform.of(5.0F, 0.0F, -4.0F, -0.0872F, -0.0038F, -0.1307F));

        bbMain.addChild("cube_r2",
                ModelPartBuilder.create()
                        .uv(8, 16).cuboid(-1.0F, -19.0F, -1.0F, 2.0F, 19.0F, 2.0F, new Dilation(0.0F)),
                ModelTransform.of(-5.0F, 0.0F, -4.0F, -0.0872F, 0.0038F, 0.1307F));

        bbMain.addChild("cube_r3",
                ModelPartBuilder.create()
                        .uv(0, 16).cuboid(-1.0F, -19.0F, -1.0F, 2.0F, 19.0F, 2.0F, new Dilation(0.0F)),
                ModelTransform.of(0.0F, 0.0F, 6.0F, 0.1745F, 0.0F, 0.0F));

        return TexturedModelData.of(modelData, 64, 64);
    }

    @Override
    public void setAngles(CameraEntityRenderState state) {
        super.setAngles(state);
        // Rotate head to match entity's look direction
        this.head.yaw = state.relativeHeadYaw * ((float) Math.PI / 180.0F);
        this.head.pitch = state.pitch * ((float) Math.PI / 180.0F);

        // Stretch head along Z axis based on zoom level
        this.head.zScale = Math.max(state.zoomLevel, 0.25f);

        // Show/hide tripod legs
        this.leg1.visible = state.showLegs;
        this.leg2.visible = state.showLegs;
        this.leg3.visible = state.showLegs;
    }
}
