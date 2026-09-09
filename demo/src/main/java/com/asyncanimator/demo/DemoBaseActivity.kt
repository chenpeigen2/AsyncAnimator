package com.asyncanimator.demo

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.asyncanimator.demo.widget.DemoStyle
import com.asyncanimator.core.Trace

/**
 * DemoBaseActivity — 所有 Demo Activity 的基类（美化版）。
 *
 * <p>统一布局：
 * <pre>
 * ┌─────────────────────────────────────┐
 * │ ▍标题（主色 accent 竖条）+ 章节       │
 * │ 中部：可视化区（子类填充）            │
 * │ 底部：深色 TRACE 日志卡片             │
 * └─────────────────────────────────────┘
 * </pre>
 */
abstract class DemoBaseActivity : AppCompatActivity() {

    protected lateinit var titleView: TextView
    protected lateinit var sectionView: TextView
    protected lateinit var contentContainer: FrameLayout
    protected lateinit var logView: TextView
    protected lateinit var scrollView: ScrollView

    /** 子类覆盖：demo 标题 */
    abstract val demoTitle: String

    /** 子类覆盖：对应分析文档章节 */
    abstract val docSection: String

    /** 子类覆盖：创建中央可视化 View */
    protected abstract fun createContentView(): View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DemoStyle.BG_PAGE)
            setPadding(DemoStyle.dp(this@DemoBaseActivity, 12f),
                DemoStyle.dp(this@DemoBaseActivity, 10f),
                DemoStyle.dp(this@DemoBaseActivity, 12f),
                DemoStyle.dp(this@DemoBaseActivity, 10f))
        }

        // ── 顶部：返回箭头 + 标题 + 章节（NoActionBar 自绘头部）─────────
        val backView = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(DemoStyle.PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(DemoStyle.dp(this@DemoBaseActivity, 4f),
                DemoStyle.dp(this@DemoBaseActivity, 8f),
                DemoStyle.dp(this@DemoBaseActivity, 14f),
                DemoStyle.dp(this@DemoBaseActivity, 8f))
            setOnClickListener { finish() }
        }
        titleView = TextView(this).apply {
            textSize = 17f
            setTextColor(DemoStyle.INK)
            setTypeface(typeface, Typeface.BOLD)
            text = demoTitle
        }
        sectionView = TextView(this).apply {
            textSize = 11f
            setTextColor(DemoStyle.GRAY)
            text = "对应分析文档：$docSection"
            setPadding(0, DemoStyle.dp(this@DemoBaseActivity, 2f), 0, 0)
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(sectionView)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(backView)
            addView(titleCol)
            setPadding(0, 0, 0, DemoStyle.dp(this@DemoBaseActivity, 8f))
        }
        root.addView(header)

        // ── 底部：日志区（先构造——createContentView 里的 log() 依赖 logView 已初始化）──
        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(DemoStyle.LOG_TEXT)
            movementMethod = ScrollingMovementMethod()
            text = "Trace 日志区\n"
            setPadding(DemoStyle.dp(this@DemoBaseActivity, 12f),
                DemoStyle.dp(this@DemoBaseActivity, 8f),
                DemoStyle.dp(this@DemoBaseActivity, 12f),
                DemoStyle.dp(this@DemoBaseActivity, 8f))
        }
        scrollView = ScrollView(this).apply {
            addView(logView)
            background = DemoStyle.roundRect(DemoStyle.BG_LOG, 12f, this@DemoBaseActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                DemoStyle.dp(this@DemoBaseActivity, 130f)
            ).apply { topMargin = DemoStyle.dp(this@DemoBaseActivity, 8f) }
        }

        // ── 中部：可视化区（白卡片）─────────────────────────
        contentContainer = FrameLayout(this).apply {
            background = DemoStyle.roundRect(DemoStyle.BG_CARD, 14f, this@DemoBaseActivity)
            elevation = DemoStyle.dp(this@DemoBaseActivity, 1.5f).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val contentView = createContentView()
        contentContainer.addView(contentView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            val m = DemoStyle.dp(this@DemoBaseActivity, 12f)
            setMargins(m, m, m, m)
        })
        root.addView(contentContainer)

        root.addView(scrollView)

        setContentView(root)

        // 重定向 Trace 输出到 logView
        redirectTraceToLogView()
    }

    /** 子类调用的便捷日志方法。 */
    protected fun log(msg: String) {
        runOnUiThread {
            logView.append("> $msg\n")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun redirectTraceToLogView() {
        // 简化：每次 Trace 输出到 stderr 时通过 System.setErr 捕获
        val originalErr = System.err
        val redirectStream = object : java.io.PrintStream(originalErr) {
            override fun println(x: String?) {
                super.println(x)
                if (x != null && x.contains("Trace")) {
                    runOnUiThread {
                        logView.append("$x\n")
                        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
        System.setErr(redirectStream)
    }
}
