package com.jiesa.xvideocatcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Module settings UI. Opened from the launcher icon on this package — not from inside X.
 *
 * LSPosed still loads the hook APK into X; this activity only runs in *our* process so the
 * user can flip preferences without adb. [ModuleSettings] copies the value into libxposed
 * remote preferences, which the hook reads inside X.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val sw = Switch(this).apply {
            isChecked = ModuleSettings.isDiagEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                ModuleSettings.setDiagEnabled(this@SettingsActivity, checked)
                val msg = if (checked) {
                    "已开启 · ${DiagLog.path()}"
                } else {
                    "已关闭诊断日志"
                }
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(label)
        row.addView(sw)
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "改完后下次打开分享面板即生效，无需重启 X。"
            setTextColor(Color.parseColor("#757575"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(28), 0, 0)
        })

        setContentView(root)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
