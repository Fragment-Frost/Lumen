package top.fpvp.lumen.render;

import net.minecraft.client.renderer.GlStateManager;

import java.nio.FloatBuffer;

/**
 * 世界坐标系的视锥体（6 个平面），从当前的 GL 矩阵直接构建。
 *
 * <p><b>为什么不用原版的 {@code Frustum}</b>：原版 {@code Frustum.isBoxInFrustum}
 * 先把包围盒各角减掉一个“摄像机位置”再交给 {@code ClippingHelper} 测试。
 * 但 {@code ClippingHelper} 里的平面方程来自 {@code 模型视图 × 投影}，落在
 * <b>视图空间</b>，而不是“世界坐标减摄像机”的空间。两者只有当模型视图的旋转
 * 部分是单位矩阵时才等价 —— 也就是说，这个减法只在正前方朝 ±Z 轴时凑巧成立。
 * 实际游戏中摄像机总是带俯仰/偏航，第三人称还会把摄像机拉远、躺下会额外抬高、
 * 视角摇晃会加小幅平移，于是原版这套判定在边缘位置本来就偏宽松（这也是原版
 * 偶发“物体在视野边缘突然消失/出现”的原因之一）。
 *
 * <p>我们没法复用这个有偏差的判定：一旦把它用在遮挡剔除上，误剔的代价是
 * 物体成片消失，比不优化严重得多。所以这里自己算：
 * <b>把 {@code 投影 × 模型视图} 乘成一个 combo 矩阵，再从 combo 矩阵提取
 * 6 个世界空间平面</b>。这样包围盒直接拿世界坐标测试，不需要任何摄像机位置，
 * 也就没有第三人称/躺下/摇晃/变焦带来的偏差。
 *
 * <p>热路径零分配：矩阵与平面都是复用数组，{@link FloatBuffer} 只创建一次。
 */
public final class FrustumCache {

    /** 平面：left, right, bottom, top, near, far。每个平面 4 个分量 (a,b,c,d)。 */
    private final float[] planes = new float[6 * 4];

    /** combo = projection × modelview，列主序（与 OpenGL 一致）。 */
    private final float[] combo = new float[16];

    private final float[] projection = new float[16];

    private final float[] modelview = new float[16];

    private java.nio.FloatBuffer projBuf;

    private java.nio.FloatBuffer mvBuf;

    private boolean valid;

    private static final FrustumCache INSTANCE = new FrustumCache();

    private FrustumCache() {
    }

    public static FrustumCache get() {
        return INSTANCE;
    }

    /**
     * 用当前 GL 矩阵重建视锥平面。
     *
     * <p>必须在 {@code setupCameraTransform} <b>之后</b>调用：此时投影矩阵与
     * 模型视图矩阵都已经反映本帧的摄像机朝向与位置。调用点选在
     * {@code EntityRenderer.renderWorldPass} 里原版 {@code ClippingHelperImpl.init()}
     * 之前 —— 那里正好是“矩阵已就绪、世界尚未开始渲染”的唯一时刻。
     */
    private void ensureBuffers() {
        if (projBuf == null) {
            // 直接分配堆内 FloatBuffer，而不是用 BufferUtils（它需要 LWJGL 的
            // 直接内存路径）。16 个 float 用堆内缓冲区足以，且不会占用直接内存配额。
            projBuf = java.nio.FloatBuffer.allocate(16);
            mvBuf = java.nio.FloatBuffer.allocate(16);
        }
    }

    /**
     * 用当前 GL 矩阵重建视锥平面。
     *
     * <p>必须在 {@code setupCameraTransform} <b>之后</b>调用：此时投影矩阵与
     * 模型视图矩阵都已经反映本帧的摄像机朝向与位置。调用点选在
     * {@code EntityRenderer.renderWorldPass} 里原版 {@code ClippingHelperImpl.init()}
     * 之前 —— 那里正好是“矩阵已就绪、世界尚未开始渲染”的唯一时刻。
     */
    public void rebuild() {
        ensureBuffers();
        try {
            projBuf.clear();
            mvBuf.clear();
            GlStateManager.getFloat(2983, projBuf); // GL_PROJECTION
            GlStateManager.getFloat(2982, mvBuf);   // GL_MODELVIEW
            projBuf.flip();
            mvBuf.flip();
            projBuf.get(projection);
            mvBuf.get(modelview);
        } catch (RuntimeException e) {
            // 没有 GL 上下文（例如某些离屏/无头路径）：退化为“全部可见”。
            valid = false;
            return;
        }

        multiply(combo, projection, modelview);
        extractPlanes();
        valid = true;
    }

    /**
     * 列主序矩阵乘法 {@code out = a × b}。
     *
     * <p>约定：{@code m[col * 4 + row]}。于是
     * {@code out[r][c] = Σ a[r][k] * b[k][c]}，
     * 即 {@code out[c * 4 + r] = Σ a[k * 4 + r] * b[c * 4 + k]}。
     */
    private static void multiply(float[] out, float[] a, float[] b) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + r] * b[c * 4 + k];
                }
                out[c * 4 + r] = sum;
            }
        }
    }

    /**
     * 从 combo 矩阵提取 6 个平面。
     *
     * <p>裁剪空间下可见区域是 {@code -w <= x,y,z <= w}。世界点 p 经 combo 变换后
     * 得到裁剪坐标 {@code (x,y,z,w)}，于是：
     * <ul>
     *   <li>左侧面：{@code x >= -w} ⇔ {@code x + w >= 0}</li>
     *   <li>右侧面：{@code x <=  w} ⇔ {@code w - x >= 0}</li>
     *   <li>下/上/近/远 同理</li>
     * </ul>
     * 把 {@code x/y/z} 用 combo 的行向量展开，就得到世界空间平面系数。
     * 平面内部判定为 {@code a*x + b*y + c*z + d >= 0}。
     */
    private void extractPlanes() {
        setPlane(0, 0);   // left:   row3 + row0
        setPlane(1, 1);   // right:  row3 - row0
        setPlane(2, 2);   // bottom: row3 + row1
        setPlane(3, 3);   // top:    row3 - row1
        setPlane(4, 4);   // near:   row3 + row2
        setPlane(5, 5);   // far:    row3 - row2
    }

    /**
     * @param index 平面下标
     * @param which 0=left(+row0) 1=right(-row0) 2=bottom(+row1) 3=top(-row1) 4=near(+row2) 5=far(-row2)
     */
    private void setPlane(int index, int which) {
        int rowA = which >> 1;          // 0=x, 1=y, 2=z
        int sign = (which & 1) == 0 ? 1 : -1;
        int base = index * 4;
        for (int comp = 0; comp < 4; comp++) {
            // combo 的第 comp 列、第 rowA 行 = combo[comp * 4 + rowA]
            float axis = combo[comp * 4 + rowA];
            float w = combo[comp * 4 + 3];
            planes[base + comp] = axis * sign + w;
        }
        normalize(base);
    }

    /** 归一化，避免平面系数随投影矩阵缩放漂移带来的精度问题。 */
    private void normalize(int base) {
        float a = planes[base];
        float b = planes[base + 1];
        float c = planes[base + 2];
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len > 1.0E-6F) {
            float inv = 1.0F / len;
            planes[base] *= inv;
            planes[base + 1] *= inv;
            planes[base + 2] *= inv;
            planes[base + 3] *= inv;
        }
    }

    public boolean isValid() {
        return valid;
    }

    /** 帧开始时标记失效，避免在矩阵未更新的一帧里用旧数据剔除。 */
    public void invalidate() {
        valid = false;
    }

    /**
     * 世界空间 AABB 是否与视锥相交。
     *
     * <p>对每个平面取“最外侧顶点”（p-vertex）：若该顶点都在平面外侧，
     * 说明整个盒子都在外侧，可以剔除。这是标准且保守的判定 ——
     * 只会漏剔，不会误剔。
     */
    public boolean isBoxVisible(double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
        if (!valid) {
            // 视锥未知时一律视为可见。宁可少省一点，也不能凭空让物体消失。
            return true;
        }
        for (int i = 0; i < 6; i++) {
            int base = i * 4;
            float a = planes[base];
            float b = planes[base + 1];
            float c = planes[base + 2];
            float d = planes[base + 3];
            // 选沿法线方向最远的角点
            double px = a >= 0 ? maxX : minX;
            double py = b >= 0 ? maxY : minY;
            double pz = c >= 0 ? maxZ : minZ;
            if (a * px + b * py + c * pz + d < 0.0D) {
                return false;
            }
        }
        return true;
    }
}
