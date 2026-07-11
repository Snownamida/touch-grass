package com.snownamida.touchgrass

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
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

    private var suppressMode = false
    private var suppressDebug = false
    private var suppressNotif = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val saveLimitsRunnable = Runnable { saveLimitsFromFields() }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)!!.use {
                    it.write(RuleStore.exportJson(this).toByteArray())
                }
            }.onSuccess { toast("规则已导出为文件") }
                .onFailure { toast("导出失败：${it.message}") }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }.getOrNull()
            if (text == null) toast("读取文件失败") else importRules(text)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButtonToggleGroup>(R.id.toggle_mode)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || suppressMode) return@addOnButtonCheckedListener
                val mode = when (checkedId) {
                    R.id.btn_mode_capture -> AppState.Mode.CAPTURE
                    R.id.btn_mode_watch -> AppState.Mode.WATCH
                    else -> AppState.Mode.OFF
                }
                if (mode != AppState.Mode.OFF && WatcherService.instance == null) {
                    toast(
                        if (isEnabledInSettings())
                            "开关是开的，但服务没连上：去把「🌱咋还在刷」的开关关一次再开"
                        else "请先开启无障碍服务"
                    )
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    refresh()
                    return@addOnButtonCheckedListener
                }
                AppState.setMode(this, mode)
                refresh()
            }

        // 额度改完即存：停顿 600ms 落盘，空值不动原配置
        val limitWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                uiHandler.removeCallbacks(saveLimitsRunnable)
                uiHandler.postDelayed(saveLimitsRunnable, 600L)
            }
        }
        listOf(
            R.id.edit_limit_session, R.id.edit_limit_daily,
            R.id.edit_limit_weekly, R.id.edit_limit_remind,
        ).forEach { findViewById<EditText>(it).addTextChangedListener(limitWatcher) }

        findViewById<Button>(R.id.btn_export_rules).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("导出规则")
                .setItems(arrayOf("存为文件…", "复制到剪贴板")) { _, which ->
                    if (which == 0) {
                        exportLauncher.launch("touch-grass-rules.json")
                    } else {
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("rules", RuleStore.exportJson(this)))
                        toast("规则 JSON 已复制到剪贴板")
                    }
                }
                .show()
        }

        findViewById<Button>(R.id.btn_import_rules).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("导入规则")
                .setItems(arrayOf("从文件选择…", "粘贴 JSON")) { _, which ->
                    if (which == 0) {
                        importLauncher.launch(arrayOf("*/*"))
                    } else {
                        val input = EditText(this)
                        input.hint = "把规则 JSON 粘贴到这里"
                        input.minLines = 4
                        AlertDialog.Builder(this)
                            .setTitle("粘贴规则 JSON")
                            .setView(input)
                            .setPositiveButton("导入") { _, _ -> importRules(input.text.toString()) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                .show()
        }

        findViewById<Button>(R.id.btn_builtin_rules).setOnClickListener {
            val n = importBuiltinRules()
            toast(if (n < 0) "内置规则载入失败" else "已载入 $n 条内置规则")
            refresh()
        }

        findViewById<MaterialSwitch>(R.id.switch_notif).setOnCheckedChangeListener { _, checked ->
            if (suppressNotif) return@setOnCheckedChangeListener
            AppState.setNotifTimer(this, checked)
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        findViewById<MaterialSwitch>(R.id.switch_debug).setOnCheckedChangeListener { _, checked ->
            if (!suppressDebug) AppState.setDebug(this, checked)
        }

        findViewById<Button>(R.id.btn_view_log).setOnClickListener {
            val text = DebugLog.dump().ifEmpty { "（暂无日志：服务运行后会记录模式切换、前台变化和每次判定）" }
            AlertDialog.Builder(this)
                .setTitle("判定日志（新的在最下）")
                .setMessage(text)
                .setPositiveButton("复制") { _, _ ->
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("log", text))
                    toast("已复制到剪贴板")
                }
                .setNeutralButton("清空") { _, _ -> DebugLog.clear() }
                .setNegativeButton("关闭", null)
                .show()
        }

        // 首次启动且没有任何规则时，自动载入内置规则，开箱即用
        val prefs = getSharedPreferences("touchgrass", MODE_PRIVATE)
        if (!prefs.getBoolean("builtinRulesLoaded", false)) {
            prefs.edit().putBoolean("builtinRulesLoaded", true).apply()
            if (RuleStore.load(this).isEmpty() && importBuiltinRules() > 0) {
                Toast.makeText(this, "已载入内置规则（B 站 / 小红书 / TikTok），开箱即用", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        // 刚改完就切走也不丢：立即落盘
        uiHandler.removeCallbacks(saveLimitsRunnable)
        saveLimitsFromFields()
        super.onPause()
    }

    private fun saveLimitsFromFields() {
        val cur = LimitConfig.load(this)
        fun v(id: Int, fallback: Int) =
            findViewById<EditText>(id).text.toString().trim().toIntOrNull() ?: fallback
        val next = Limits(
            sessionMin = v(R.id.edit_limit_session, cur.sessionMin),
            dailyMin = v(R.id.edit_limit_daily, cur.dailyMin),
            weeklyMin = v(R.id.edit_limit_weekly, cur.weeklyMin),
            remindMin = v(R.id.edit_limit_remind, cur.remindMin),
        )
        if (next != cur) {
            LimitConfig.save(this, next)
            findViewById<TextView>(R.id.text_today_caption).text = "今日" + quota(next.dailyMin)
            findViewById<TextView>(R.id.text_week_caption).text = "本周" + quota(next.weeklyMin)
        }
    }

    private fun refresh() {
        // 服务状态（三态：运行中 / 开关开着但没连上 / 未开启）
        val serviceOn = WatcherService.instance != null
        val statusView = findViewById<TextView>(R.id.text_service_status)
        val accBtn = findViewById<Button>(R.id.btn_accessibility)
        when {
            serviceOn -> {
                statusView.text = "✅ 无障碍服务运行中"
                accBtn.visibility = View.GONE
            }
            isEnabledInSettings() -> {
                statusView.text = "⚠️ 开关已开但服务没连上\n覆盖安装后常见，关一次再开即可"
                accBtn.text = "去重启开关"
                accBtn.visibility = View.VISIBLE
            }
            else -> {
                statusView.text = "❌ 无障碍服务未开启（必需）"
                accBtn.text = "去开启"
                accBtn.visibility = View.VISIBLE
            }
        }

        // 模式
        suppressMode = true
        val mode = AppState.getMode(this)
        findViewById<MaterialButtonToggleGroup>(R.id.toggle_mode).check(
            when (mode) {
                AppState.Mode.CAPTURE -> R.id.btn_mode_capture
                AppState.Mode.WATCH -> R.id.btn_mode_watch
                AppState.Mode.OFF -> R.id.btn_mode_off
            }
        )
        suppressMode = false
        findViewById<TextView>(R.id.text_mode_hint).text = when (mode) {
            AppState.Mode.OFF -> "当前不监测、不采样"
            AppState.Mode.CAPTURE -> "去目标 app，用悬浮面板采样：短视频页 ✓、普通页 ✕，然后生成规则"
            AppState.Mode.WATCH -> "刷到短视频页面自动计时，超额全屏拦截"
        }

        suppressDebug = true
        findViewById<MaterialSwitch>(R.id.switch_debug).isChecked = AppState.isDebug(this)
        suppressDebug = false

        suppressNotif = true
        findViewById<MaterialSwitch>(R.id.switch_notif).isChecked = AppState.isNotifTimer(this)
        suppressNotif = false

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

        // 统计
        val byRule = StatsStore.todayByRule(this)
        val todaySec = byRule.values.sum()
        val weekSec = StatsStore.weekTotalSeconds(this)
        findViewById<TextView>(R.id.text_today_big).text = fmtBig(todaySec)
        findViewById<TextView>(R.id.text_today_caption).text = "今日" + quota(limits.dailyMin)
        findViewById<TextView>(R.id.text_week_big).text = fmtBig(weekSec)
        findViewById<TextView>(R.id.text_week_caption).text = "本周" + quota(limits.weeklyMin)

        val ruleLines = rules.mapNotNull { r ->
            val s = byRule[r.id] ?: 0L
            if (s > 0) "· ${r.name}：${fmtBig(s)}" else null
        }
        val statsRules = findViewById<TextView>(R.id.text_stats_rules)
        statsRules.visibility = if (ruleLines.isEmpty()) View.GONE else View.VISIBLE
        statsRules.text = ruleLines.joinToString("\n")

        val days = StatsStore.totalsByDay(this, 7)
        val max = maxOf(days.maxOf { it.second }, 1L)
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFmt = SimpleDateFormat("MM-dd E", Locale.CHINA)
        findViewById<TextView>(R.id.text_chart).text = days.joinToString("\n") { (day, sec) ->
            val label = runCatching { labelFmt.format(parseFmt.parse(day)!!) }.getOrDefault(day)
            val bar = if (sec == 0L) "·" else "█".repeat(maxOf(1, (sec * 10 / max).toInt()))
            "$label $bar ${sec / 60}分"
        }

        // 规则列表
        val list = findViewById<LinearLayout>(R.id.list_rules)
        list.removeAllViews()
        if (rules.isEmpty()) {
            val tv = TextView(this)
            tv.textSize = 13f
            tv.text = "还没有规则。点「内置」载入默认规则，或开捕捉模式自己采样生成。"
            list.addView(tv)
        } else {
            val inflater = LayoutInflater.from(this)
            for (r in rules) {
                val item = inflater.inflate(R.layout.item_rule, list, false)
                item.findViewById<TextView>(R.id.text_rule_name).text = r.name
                item.findViewById<TextView>(R.id.text_rule_desc).text = r.describe()
                item.findViewById<Button>(R.id.btn_detail_rule).setOnClickListener { showRuleDetail(r) }
                item.findViewById<Button>(R.id.btn_delete_rule).setOnClickListener {
                    RuleStore.remove(this, r.id)
                    WatcherService.instance?.reloadRules()
                    refresh()
                }
                list.addView(item)
            }
        }
    }

    private fun importRules(text: String) {
        val n = RuleStore.importJson(this, text)
        toast(if (n < 0) "解析失败：不是有效的规则 JSON" else "已导入 $n 条规则（同应用自动合并）")
        WatcherService.instance?.reloadRules()
        refresh()
    }

    private fun importBuiltinRules(): Int = try {
        val text = assets.open("default_rules.json").bufferedReader().use { it.readText() }
        val n = RuleStore.importJson(this, text)
        WatcherService.instance?.reloadRules()
        n
    } catch (e: Exception) {
        -1
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

    private fun fmtBig(sec: Long): String =
        if (sec >= 3600) "${sec / 3600} 时 ${(sec % 3600) / 60} 分" else "${sec / 60} 分"

    private fun quota(min: Int): String = if (min > 0) " · 额度 $min 分" else ""

    private fun showRuleDetail(rule: Rule) {
        val text = rule.detailText()
        AlertDialog.Builder(this)
            .setTitle(rule.name)
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("rule", text))
                toast("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
