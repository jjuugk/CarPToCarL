package io.github.shaonialife.carptocar.media

import android.content.Context
import io.github.shaonialife.carptocar.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 高德数据源用的转向箭头图标。
 *
 * ## 图标从哪来（别再自己画）
 *
 * 一开始这批图是 Canvas 现画的（`ManeuverArrow` 的前身），上手一看太糙：
 * 三角是等腰的、拐角是直角、环岛像钥匙孔，和 CarLife 投屏上那套差得远。
 *
 * 高德地图 APK 里**没有**现成的主诱导箭头资源——它的导航箭头是 native/GL 画的，
 * `res/drawable-xhdpi-v4/maneuver_icon_*.png` 只有 12 个 96px 的路口小示意图
 * （`across_turn_left` 那种带灰底的方框），放大到 256 必糊。
 *
 * 所以改用 **CarLife 引擎自己那套** `nsdk_drawable_rg_ic_turn_*`（240×240 PNG），
 * 从 `.tmp/carlife.apk` 提取，放进 `res/raw/`。三个理由：
 *
 * 1. 这就是车机现在收到的图——`0x10030` 的 `turnIconData` 由引擎从这同一批资源里读出，
 *    所以切到高德数据源后箭头样式**不会变**，用户视角里只是"数据源换了"；
 * 2. CarLife 的导航数据本来就来自高德，样式属于同一套设计语言；
 * 3. 240×240 原生尺寸，经 [NavigationIcon.render] 统一到 256×256，不糊。
 *
 * ## 关于"浅底看不见"
 *
 * 这批图是纯白无描边的（实测不透明像素平均 RGB=255,255,255），放在浅色媒体卡片上
 * 就是隐形。**不在素材里加描边**——一加就不是原图了，而且描边会把干净的图形压重。
 * 需要适配浅色场景时走传输层的「转向箭头加深色底」开关（默认关）：
 * 打开后由 [NavigationIcon.toCardJpeg] 把箭头压成「深底白箭头 JPEG」。
 *
 * ## 加载时机
 *
 * [prepare] 在 `AmapNaviSource.configure()` 里调一次（进程级，幂等）。
 * 12 张图总共 16KB，一次读完放内存，之后 [render] 是纯查表。
 * 没 prepare 就直接调 [render] 会拿到 [NavigationIcon.blank]——
 * 不会崩，只是没箭头；`configure` 一定早于 `setEnabled`，实际不会发生。
 */
internal object ManeuverArrow {
    /** 形状 → `res/raw` 图标。缺哪个就哪个形状不发图（只显示文字）。 */
    private val icons: Map<ArrowShape, Int> =
        mapOf(
            ArrowShape.STRAIGHT to R.raw.navi_turn_front,
            ArrowShape.LEFT to R.raw.navi_turn_left,
            ArrowShape.RIGHT to R.raw.navi_turn_right,
            ArrowShape.LEFT_FRONT to R.raw.navi_turn_left_front,
            ArrowShape.RIGHT_FRONT to R.raw.navi_turn_right_front,
            ArrowShape.LEFT_BACK to R.raw.navi_turn_left_back,
            ArrowShape.RIGHT_BACK to R.raw.navi_turn_right_back,
            ArrowShape.U_TURN to R.raw.navi_turn_back,
            ArrowShape.ROUNDABOUT_IN to R.raw.navi_turn_ring,
            // 驶出环岛没有方向信息（ICON 12 不带出口方位），用右侧出口图：
            // 中国是右行，绝大多数出口在右侧，总比发一张通用环岛图更有指向性。
            ArrowShape.ROUNDABOUT_OUT to R.raw.navi_turn_ring_right,
            ArrowShape.ARRIVE_DEST to R.raw.navi_turn_dest,
            ArrowShape.ARRIVE_TOLL to R.raw.navi_turn_tollgate,
        )

    private val cache = ConcurrentHashMap<ArrowShape, ByteArray>()

    @Volatile
    private var prepared = false

    /**
     * 读入全部图标并统一缩放。幂等，重复调用直接返回已加载数量。
     *
     * @return 成功加载的张数（正常是 [icons] 的大小）。小于它说明某张图解码失败，
     *   对应形状会退化成"只有文字没有箭头"——日志里能一眼看出来。
     */
    fun prepare(context: Context): Int {
        if (prepared) return cache.size
        synchronized(this) {
            if (prepared) return cache.size
            for ((shape, id) in icons) {
                // 读失败（资源损坏之类）只是这个形状没图，不该让整个数据源起不来。
                runCatching {
                    context.resources.openRawResource(id).use { raw ->
                        NavigationIcon.render(raw.readBytes())
                    }
                }.onSuccess { bytes ->
                    if (bytes.isNotEmpty() && bytes !== NavigationIcon.blank) cache[shape] = bytes
                }
            }
            prepared = true
        }
        return cache.size
    }

    /** 图标总数，用于"加载了几张 / 应该有 几张"的日志与自检。 */
    val expectedCount: Int get() = icons.size

    /** 已加载张数。 */
    val loadedCount: Int get() = cache.size

    /** 取某个转向码的箭头 PNG；无对应图标时返回透明图（车机清掉封面位）。 */
    fun render(turnCode: Int): ByteArray = cache[arrowShapeFor(turnCode)] ?: NavigationIcon.blank
}
