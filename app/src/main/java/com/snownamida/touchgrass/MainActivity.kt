package com.snownamida.touchgrass

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.snownamida.touchgrass.debug.DebugLog
import com.snownamida.touchgrass.rules.Rule
import com.snownamida.touchgrass.rules.RuleStore
import com.snownamida.touchgrass.service.WatcherService
import com.snownamida.touchgrass.track.LimitConfig
import com.snownamida.touchgrass.track.Limits
import com.snownamida.touchgrass.track.StatsStore
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var suppressRadio = false
    private var suppressDebug = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<RadioGroup>(R.id.radio_mode).setOnCheckedChangeListener { _, checkedId ->
            if (suppressRadio) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.radio_capture -> AppState.Mode.CAPTURE
                R.id.radio_watch -> AppState.Mode.WATCH
                else -> AppState.Mode.OFF
            }
            if (mode != AppState.Mode.OFF && WatcherService.instance == null) {
                val msg = if (isEnabledInSettings())
                    "开关是开的，但服务没连上：去把「🌱咋还在刷」的开关关一次再开"
                else "请先开启无障碍服务"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                refresh()
                return@setOnCheckedChangeListener
            }
            AppState.setMode(this, mode)
        }

        findViewById<Button>(R.id.btn_save_limits).setOnClickListener {
            fun value(id: Int) = findViewById<EditText>(id).text.toString().trim().toIntOrNull() ?: 0
            LimitConfig.save(
                this,
                Limits(
                    sessionMin = value(R.id.edit_limit_session),
                    dailyMin = value(R.id.edit_limit_daily),
                    weeklyMin = value(R.id.edit_limit_weekly),
                    remindMin = value(R.id.edit_limit_remind),
                )
            )
            Toast.makeText(this, "额度已保存", Toast.LENGTH_SHORT).show()
            refresh()
        }

        findViewById<CheckBox>(R.id.chk_debug).setOnCheckedChangeListener { _, checked ->
            if (!suppressDebug) AppState.setDebug(this, checked)
        }

        findViewById<Button>(R.id.btn_export_rules).setOnClickListener {
            val text = RuleStore.exportJson(this)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("rules", text))
            Toast.makeText(this, "规则 JSON 已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_import_rules).setOnClickListener {
            val input = EditText(this)
            input.hint = "把规则 JSON 粘贴到这里"
            input.minLines = 4
            AlertDialog.Builder(this)
                .setTitle("导入规则")
                .setView(input)
                .setPositiveButton("导入") { _, _ ->
                    val n = RuleStore.importJson(this, input.text.toString())
                    Toast.makeText(
                        this,
                        if (n < 0) "解析失败：不是有效的规则 JSON" else "已导入 $n 条规则（同应用自动合并）",
                        Toast.LENGTH_SHORT
                    ).show()
                    WatcherService.instance?.reloadRules()
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.btn_builtin_rules).setOnClickListener {
            val n = importBuiltinRules()
            Toast.makeText(
                this,
                if (n < 0) "内置规则载入失败" else "已载入 $n 条内置规则",
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }

        // 首次启动且没有任何规则时，自动载入内置规则，开箱即用
        val prefs = getSharedPreferences("touchgrass", MODE_PRIVATE)
        if (!prefs.getBoolean("builtinRulesLoaded", false)) {
            prefs.edit().putBoolean("builtinRulesLoaded", true).apply()
            if (RuleStore.load(this).isEmpty() && importBuiltinRules() > 0) {
                Toast.makeText(this, "已载入内置规则（B 站竖屏 / 小红书视频），开箱即用", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btn_view_log).setOnClickListener {
            val text = DebugLog.dump().ifEmpty { "（暂无日志：服务运行后会记录模式切换、前台变化和每次判定）" }
            AlertDialog.Builder(this)
                .setTitle("判定日志（新的在最下）")
                .setMessage(text)
                .setPositiveButton("复制") { _, _ ->
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("log", text))
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("清空") { _, _ -> DebugLog.clear() }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 系统设置里我们的无障碍开关是否处于打开状态（不等于服务真的连上了）。 */
    private fun isEnabledInSettings(): Boolean {
        val cn = ComponentName(this, WatcherService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val serviceOn = WatcherService.instance != null
        val statusView = findViewById<TextView>(R.id.text_service_status)
        val accBtn = findViewById<Button>(R.id.btn_accessibility)
        when {
            serviceOn -> {
                statusView.text = "无障碍服务：✅ 运行中"
                accBtn.visibility = View.GONE
            }
            isEnabledInSettings() -> {
                statusView.text = "无障碍服务：⚠️ 开关已开，但服务没连上\n（覆盖安装新版后常见，把开关关一次再开即可）"
                accBtn.text = "去重启无障碍开关"
                accBtn.visibility = View.VISIBLE
            }
            else -> {
                statusView.text = "无障碍服务：❌ 未开启（必需）"
                accBtn.text = "去开启无障碍服务"
                accBtn.visibility = View.VISIBLE
            }
        }

        suppressDebug = true
        findViewById<CheckBox>(R.id.chk_debug).isChecked = AppState.isDebug(this)
        suppressDebug = false

        suppressRadio = true
        findViewById<RadioGroup>(R.id.radio_mode).check(
            when (AppState.getMode(this)) {
                AppState.Mode.CAPTURE -> R.id.radio_capture
                AppState.Mode.WATCH -> R.id.radio_watch
                AppState.Mode.OFF -> R.id.radio_off
            }
        )
        suppressRadio = false

        val rules = RuleStore.load(this)
        val limits = LimitConfig.load(this)

        fun fillLimit(id: Int, v: Int) {
            val e = findViewById<EditText>(id)
            if (!e.hasFocus()) e.setText(v.toString())
        }
        fillLimit(R.id.edit_limit_session, limits.sessionMin)
        fillLimit(R.id.edit_limit_daily, limits.dailyMin)
        fillLimit(R.id.edit_limit_weekly, limits.weeklyMin)
        fillLimit(R.id.edit_limit_remind, limits.remindMin)

        val byRule = StatsStore.todayByRule(this)
        val todaySec = byRule.values.sum()
        val weekSec = StatsStore.weekTotalSeconds(this)
        val sb = StringBuilder("今日 ${fmtDur(todaySec)}${quota(limits.dailyMin)}")
        sb.append("\n本周 ${fmtDur(weekSec)}${quota(limits.weeklyMin)}")
        for (r in rules) {
            val s = byRule[r.id] ?: 0L
            if (s > 0) sb.append("\n· ${r.name}：${fmtDur(s)}")
        }
        findViewById<TextView>(R.id.text_stats).text = sb.toString()

        // 近 7 天文本柱状图
        val days = StatsStore.totalsByDay(this, 7)
        val max = maxOf(days.maxOf { it.second }, 1L)
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFmt = SimpleDateFormat("MM-dd E", Locale.CHINA)
        findViewById<TextView>(R.id.text_chart).text = days.joinToString("\n") { (day, sec) ->
            val label = runCatching { labelFmt.format(parseFmt.parse(day)!!) }.getOrDefault(day)
            val bar = if (sec == 0L) "·" else "█".repeat(maxOf(1, (sec * 10 / max).toInt()))
            "$label $bar ${sec / 60}分"
        }

        val list = findViewById<LinearLayout>(R.id.list_rules)
        list.removeAllViews()
        if (rules.isEmpty()) {
            val tv = TextView(this)
            tv.textSize = 13f
            tv.text = "还没有规则。开启捕捉模式，去目标 app 的短视频页面点 ✓、普通页面点 ✕，然后点「生成规则」。"
            list.addView(tv)
        } else {
            val inflater = LayoutInflater.from(this)
            for (r in rules) {
                val item = inflater.inflate(R.layout.item_rule, list, false)
                item.findViewById<TextView>(R.id.text_rule_name).text = r.name
                item.findViewById<TextView>(R.id.text_rule_desc).text = r.describe()
                item.findViewById<Button>(R.id.btn_detail_rule).setOnClickListener {
                    showRuleDetail(r)
                }
                item.findViewById<Button>(R.id.btn_delete_rule).setOnClickListener {
                    RuleStore.remove(this, r.id)
                    WatcherService.instance?.reloadRules()
                    refresh()
                }
                list.addView(item)
            }
        }
    }

    private fun importBuiltinRules(): Int = try {
        val text = assets.open("default_rules.json").bufferedReader().use { it.readText() }
        val n = RuleStore.importJson(this, text)
        WatcherService.instance?.reloadRules()
        n
    } catch (e: Exception) {
        -1
    }

    private fun fmtDur(sec: Long): String =
        if (sec >= 3600) "${sec / 3600} 小时 ${(sec % 3600) / 60} 分"
        else "${sec / 60} 分 ${sec % 60} 秒"

    private fun quota(min: Int): String = if (min > 0) "（额度 $min 分）" else ""

    private fun showRuleDetail(rule: Rule) {
        val text = rule.detailText()
        AlertDialog.Builder(this)
            .setTitle(rule.name)
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("rule", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
