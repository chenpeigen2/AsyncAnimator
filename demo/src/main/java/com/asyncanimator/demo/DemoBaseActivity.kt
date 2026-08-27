package com.asyncanimator.demo

import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.asyncanimator.util.Trace

/**
 * DemoBaseActivity — 所有 Demo Activity 的基类。
 *
 * <p>提供统一布局结构：
 * <pre>
 * ┌─────────────────────────────────────┐
 * │ 顶部：demo 标题 + 对应章节            │
 * │ 中部：可视化区（子类填充）            │
 * │ 底部：日志区（实时 trace 输出）       │
 * └─────────────────────────────────────┘
 * </pre>
 *
 * <p>日志区把 {@link com.asyncanimator.util.Trace} 的 stderr 输出重定向到 TextView。
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
            setPadding(16, 16, 16, 16)
        }

        // 顶部：标题 + 章节
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = demoTitle
        }
        sectionView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            text = "对应分析文档：$docSection"
        }
        header.addView(titleView)
        header.addView(sectionView)
        root.addView(header)

        // 中部：可视化区
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val contentView = createContentView()
        contentContainer.addView(contentView)
        root.addView(contentContainer)

        // 底部：日志区
        logView = TextView(this).apply {
            textSize = 10f
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setTextColor(Color.parseColor("#333333"))
            movementMethod = ScrollingMovementMethod()
            text = "Trace 日志区\n"
        }
        scrollView = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 280)
        }
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