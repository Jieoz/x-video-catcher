package com.jiesa.xvideocatcher

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Module settings UI. Opened from the launcher icon on this package — not from inside X.
 *
 * The switch is stored in libxposed remote preferences through the Xposed **service**
 * ([ModuleRuntime]), which is the writable interface available in this (the module app) process.
 * The hook inside X reads that value once at startup through the read-only hook interface.
 *
 * Writing the switch through the hook interface from here — what every build before this one did —
 * silently failed: [com.jiesa.xvideocatcher.hook.XVideoCatcherModule.framework] is an uninitialised
 * `lateinit` in this process. The service binds asynchronously a moment after the process starts,
 * so the switch stays disabled with a "连接框架中" note until [ModuleRuntime.serviceReady] is true.
 */
class SettingsActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var toggle: Switch
    private lateinit var hint: TextView
    private var retries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleRuntime.startServiceBinding()

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        root.addView(TextView(this).apply {
            text = "X Video Catcher"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        root.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(24))
        })

        root.addView(TextView(this).apply {
            text = "诊断日志"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        root.addView(TextView(this).apply {
            text = "开启后写入 Download/XVideoCatcher/xvc-diag-日期.txt，供排查下载问题。默认关闭，不持续收集。"
            setTextColor(Color.parseColor("#BDBDBD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = "记录诊断日志"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toggle = Switch(this)
        row.addView(label)
        row.addView(toggle)
        root.addView(row)

        hint = TextView(this).apply {
            setTextColor(Color.parseColor("#757575"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(16), 0, dp(24))
        }
        root.addView(hint)

        root.addView(TextView(this).apply {
            text = "打开系统下载"
            setTextColor(Color.parseColor("#8AB4F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { openDownloads() }
        })

        setContentView(root)
        refreshState()
    }

    /**
     * Reflect the current service state. The service binds asynchronously, so re-check a few times
     * with a short backoff instead of deciding once at [onCreate].
     */
    private fun refreshState() {
        val ready = ModuleRuntime.serviceReady()
        setControlsWithoutCallback(
            ready,
            ready && ModuleRuntime.readSwitch(ModuleSettings.PREF_NAME, ModuleSettings.KEY_DIAG_ENABLED),
        )
        hint.text = if (ready) {
            "开关在 X 启动时读取一次：改完请强制停止 X 再打开才生效。"
        } else {
            "正在连接 LSPosed 框架…若长时间显示此状态，请确认模块已在 LSPosed 中激活。"
        }
        if (!ready && retries < 10) {
            retries++
            main.postDelayed({ refreshState() }, 300)
        }
    }

    private fun setControlsWithoutCallback(enabled: Boolean, checked: Boolean) {
        toggle.setOnCheckedChangeListener(null)
        toggle.isEnabled = enabled
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { button, isChecked -> onToggle(button, isChecked) }
    }

    private fun onToggle(button: CompoundButton, checked: Boolean) {
        val ok = ModuleRuntime.writeSwitch(
            ModuleSettings.PREF_NAME, ModuleSettings.KEY_DIAG_ENABLED, checked,
        )
        if (!ok) {
            setControlsWithoutCallback(button.isEnabled, !checked)
            Toast.makeText(this, "开关没写上，框架未连接或模块未激活。", Toast.LENGTH_LONG).show()
            return
        }
        val msg = if (checked) {
            "已开启。请强制停止 X 再重新打开，日志出现在 Download/XVideoCatcher/。"
        } else {
            "已关闭。X 下次重启后停止记录。"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (t: Throwable) {
            Toast.makeText(this, "打不开系统下载", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
