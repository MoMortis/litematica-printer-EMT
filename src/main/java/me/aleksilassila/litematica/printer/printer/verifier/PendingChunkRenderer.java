package me.aleksilassila.litematica.printer.printer.verifier;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.SchematicPlacementAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.joml.Matrix4f;

import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

//#if MC >= 260100
//$$ import com.mojang.blaze3d.buffers.GpuBufferSlice;
//$$ import net.minecraft.client.renderer.state.level.CameraRenderState;
//$$ import org.joml.Matrix4fc;
//#endif

/**
 * 初扫描阶段把"尚未验证的区块"以绿色线框（区块柱尺寸：16 x 原理图 Y 包络 x 16）
 * 标记在世界里，直观指示验证进度与待验证区域的位置。
 *
 * <p>渲染要点：
 * <ul>
 *   <li>通过 malilib 的 {@code registerWorldLastRenderer} 注册（两版本同源 API），
 *       不 mixin litematica 的渲染器——两版本世界渲染管线差异由 malilib 抽象屏蔽；</li>
 *   <li>顶点为<b>相机相对坐标</b>并使用无深度/无剔除的调试线管线，
 *       因此渲染距离之外、被地形遮挡的待验证区块同样可见；</li>
 *   <li>数据来自验证器的写时复制快照（{@code getPendingSnapshot()}），
 *       渲染线程无锁读取，与主线程的派发互不干扰；</li>
 *   <li>绘制调用序列与 litematica 自身的错误高亮
 *       （{@code RenderContext} + 批量线 + {@code draw}）逐一对齐，两版本行为一致。</li>
 * </ul>
 */
public class PendingChunkRenderer implements IRenderer {
    /** 单帧渲染的待验证区块上限（防御性：超大图初始时避免逐帧顶点量失控） */
    private static final int MAX_BOXES_PER_FRAME = 16384;
    private static final Color4f GREEN = Color4f.fromColor(0x55FF55, 1.0F);
    private static final float LINE_WIDTH = 2.0F;

    private static PendingChunkRenderer instance;

    // 如果不注册无法渲染
    public static void init() {
        if (instance == null) {
            instance = new PendingChunkRenderer();
            RenderEventHandler.getInstance().registerWorldLastRenderer(instance);
        }
    }

    @Override
    //#if MC >= 260100
    //$$ public void onRenderWorldLast(RenderTarget fb, Matrix4fc matrices, CameraRenderState cameraState, Frustum culling, RenderBuffers buffers, GpuBufferSlice terrainFog, Vector4f fogColor, ProfilerFiller profiler) {
    //#else
    public void onRenderWorldLast(Matrix4f matrices, Matrix4f projMatrix) {
    //#endif
        this.render();
    }

    private void render() {
        if (!Configs.Core.VERIFIER_OPTIMIZED.getBooleanValue()) {
            return;
        }
        // 与错误高亮共用同一总开关
        if (!fi.dy.masa.litematica.config.Configs.InfoOverlays.VERIFIER_OVERLAY_ENABLED.getBooleanValue()
                || !fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_RENDERING.getBooleanValue()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        int totalBoxes = 0;
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            // 经 accessor 读取，避免为从未验证的放置凭空创建验证器实例
            SchematicVerifier verifier = ((SchematicPlacementAccessor) placement).printer$getVerifier();
            if (!(verifier instanceof OptimizedSchematicVerifier optimized)) {
                continue;
            }
            if (!optimized.isScanStarted() || optimized.isFinished()) {
                continue; // 仅初扫描进行中显示待验证区块
            }
            if (!optimized.isBoundToCurrentDimension()) {
                continue; // 其它维度不渲染原维度的待验证区块
            }
            long[] snapshot = optimized.getPendingSnapshot();
            if (snapshot.length == 0) {
                continue;
            }
            totalBoxes += this.renderPlacement(optimized, snapshot, mc);
            if (totalBoxes >= MAX_BOXES_PER_FRAME) {
                break;
            }
        }
    }

    private int renderPlacement(OptimizedSchematicVerifier verifier, long[] snapshot, Minecraft mc) {
        int yMin = verifier.getRenderMinY();
        int yMax = verifier.getRenderMaxY();
        int cap = Math.min(MAX_BOXES_PER_FRAME, snapshot.length);
        Vec3 cam = RenderUtils.camPos();
        RenderContext ctx = new RenderContext(
                () -> "litematica_printer:pending_chunks", MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL);
        try {
            BufferBuilder buffer = ctx.getBuilder();
            // 顶点为相机相对坐标（与 litematica 错误高亮的 Simple 变体一致），无深度无剔除，
            // 因此渲染距离之外、被地形遮挡的待验证区块同样可见
            for (int i = 0; i < cap; i++) {
                long key = snapshot[i];
                float x0 = (OptimizedSchematicVerifier.chunkX(key) << 4) - (float) cam.x;
                float z0 = (OptimizedSchematicVerifier.chunkZ(key) << 4) - (float) cam.z;
                float x1 = x0 + 16.0F;
                float z1 = z0 + 16.0F;
                float y0 = yMin - (float) cam.y;
                float y1 = yMax - (float) cam.y;
                RenderUtils.drawBoxAllEdgesBatchedLines(x0, y0, z0, x1, y1, z1, GREEN, LINE_WIDTH, buffer);
            }
            MeshData mesh = buffer.build();
            ctx.draw(mesh, false, true);
            mesh.close();
        } catch (Exception e) {
            Reference.LOGGER.error("Pending chunk rendering failed: {}", e.getLocalizedMessage());
        } finally {
            ctx.reset();
        }
        return cap;
    }
}
