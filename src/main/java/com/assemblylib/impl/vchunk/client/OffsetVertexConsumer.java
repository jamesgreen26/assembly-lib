package com.assemblylib.impl.vchunk.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * A {@link VertexConsumer} that shifts every emitted position by a constant offset before passing it
 * to a delegate, and forwards everything else untouched.
 *
 * <p>Needed only for the fluid pass of {@link AssemblyBakedMesh}. Vanilla's
 * {@code LiquidBlockRenderer} bakes fluid vertices at coordinates masked to a 16-block section
 * ({@code pos.getX() & 15}, ...) because, in normal chunk rendering, the section's origin is applied
 * separately by the caller's {@code PoseStack}. The assembly mesh has no such per-section transform —
 * it bakes one flat buffer at full assembly-local coordinates — so a fluid at local (20, y, 3) would
 * otherwise render at (4, y, 3). Feeding {@code renderLiquid} through this consumer with the offset
 * set to {@code pos & ~15} (the section origin that was masked away) restores the true local position.
 */
public final class OffsetVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private double ox;
    private double oy;
    private double oz;

    public OffsetVertexConsumer(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    /** Set the offset added to every subsequent vertex position (the masked-away section origin). */
    public void setOffset(double ox, double oy, double oz) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex((float) (x + ox), (float) (y + oy), (float) (z + oz));
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }
}
