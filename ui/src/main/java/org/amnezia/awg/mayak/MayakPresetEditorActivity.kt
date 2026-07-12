// SPEC-0028: полноэкранный редактор ОДНОГО пресета сплит-туннеля (аналог экрана BlancVPN
// «Режим VPN для приложений»). Экран самодостаточен: НЕ трогает MayakActivity/MayakPrefs/связь/бэкенд —
// принимает данные пресета через Intent-экстры и возвращает результат через setResult. Привязку
// (сохранение/загрузку пресетов) делает вызывающая сторона.
//
// Контракт запуска/возврата — см. companion ниже:
//   IN : EXTRA_ID(Long, 0=новый), EXTRA_NAME(String), EXTRA_MODE("all"|"exclude"|"include"),
//        EXTRA_APPS(ArrayList<String> выбранных пакетов), EXTRA_EDITABLE(Boolean).
//   OUT: RESULT_OK + EXTRA_NAME/EXTRA_MODE/EXTRA_APPS.
package org.amnezia.awg.mayak

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R

class MayakPresetEditorActivity : AppCompatActivity() {

    // Модель строки приложения (SPEC-0028): иконка+имя+пакет+состояние галочки.
    private class AppRow(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        var checked: Boolean,
    )

    private var editable = true
    private var mode = MODE_EXCLUDE

    // Полный список приложений (backing) и отфильтрованный по поиску (виден в RecyclerView).
    private val allApps = ArrayList<AppRow>()
    private val shownApps = ArrayList<AppRow>()

    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameField: TextInputEditText
    private lateinit var counter: TextView
    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchField: TextInputEditText
    private lateinit var listArea: View
    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var allHint: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var radioAll: MaterialRadioButton
    private lateinit var radioExclude: MaterialRadioButton
    private lateinit var radioInclude: MaterialRadioButton
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_preset_editor)
        MayakSystemBars.apply(this) // контраст иконок статус-бара/навбара под тему

        // Edge-to-edge: отступаем контент на высоту статус-бара сверху и навбара снизу (как в настройках).
        val content = findViewById<View>(R.id.mayak_preset_content)
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = baseTop + bars.top, bottom = baseBottom + bars.bottom)
            insets
        }

        // Считываем входные экстры.
        val startName = intent.getStringExtra(EXTRA_NAME) ?: ""
        mode = intent.getStringExtra(EXTRA_MODE)?.takeIf { it in VALID_MODES } ?: MODE_EXCLUDE
        editable = intent.getBooleanExtra(EXTRA_EDITABLE, true)
        val selected = intent.getStringArrayListExtra(EXTRA_APPS)?.toHashSet() ?: HashSet()

        nameLayout = findViewById(R.id.mayak_preset_name_layout)
        nameField = findViewById(R.id.mayak_preset_name)
        counter = findViewById(R.id.mayak_preset_counter)
        searchLayout = findViewById(R.id.mayak_preset_search_layout)
        searchField = findViewById(R.id.mayak_preset_search)
        listArea = findViewById(R.id.mayak_preset_list_area)
        recycler = findViewById(R.id.mayak_preset_recycler)
        progress = findViewById(R.id.mayak_preset_progress)
        allHint = findViewById(R.id.mayak_preset_all_hint)
        saveButton = findViewById(R.id.mayak_preset_save)
        radioAll = findViewById(R.id.mayak_preset_radio_all)
        radioExclude = findViewById(R.id.mayak_preset_radio_exclude)
        radioInclude = findViewById(R.id.mayak_preset_radio_include)

        nameField.setText(startName)
        // Системный пресет (editable=false): имя только для чтения, а «Сохранить» = сохранить свою копию.
        if (!editable) {
            nameField.isEnabled = false
            nameLayout.isEnabled = false
            saveButton.setText(R.string.mayak_preset_editor_save_copy)
        }

        findViewById<MaterialButton>(R.id.mayak_preset_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }

        // Выбор режима: клик по строке. Мьютекс ведём вручную (строки с подзаголовками ≠ RadioGroup).
        findViewById<View>(R.id.mayak_preset_row_all).setOnClickListener { selectMode(MODE_ALL) }
        findViewById<View>(R.id.mayak_preset_row_exclude).setOnClickListener { selectMode(MODE_EXCLUDE) }
        findViewById<View>(R.id.mayak_preset_row_include).setOnClickListener { selectMode(MODE_INCLUDE) }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        saveButton.setOnClickListener { save() }

        // Начальное состояние UI режима (обновит видимость списка/счётчик).
        applyModeUi()
        loadApps(selected)
    }

    /** Пометить выбранный режим и обновить UI. */
    private fun selectMode(newMode: String) {
        mode = newMode
        applyModeUi()
    }

    /** Синхронизировать радиокнопки, видимость списка/поиска и счётчик с текущим режимом. */
    private fun applyModeUi() {
        radioAll.isChecked = mode == MODE_ALL
        radioExclude.isChecked = mode == MODE_EXCLUDE
        radioInclude.isChecked = mode == MODE_INCLUDE

        // Режим «Все»: выбирать нечего — прячем поиск и список, показываем подсказку.
        val needList = mode != MODE_ALL
        searchLayout.visibility = if (needList) View.VISIBLE else View.GONE
        recycler.visibility = if (needList) View.VISIBLE else View.GONE
        allHint.visibility = if (mode == MODE_ALL) View.VISIBLE else View.GONE
        counter.visibility = if (needList) View.VISIBLE else View.GONE
        updateCounter()
    }

    /** Загрузка установленных интернет-приложений ВНЕ главного потока (подход из AppListDialogFragment). */
    private fun loadApps(selected: Set<String>) {
        progress.visibility = View.VISIBLE
        val pm = packageManager
        val self = packageName
        lifecycleScope.launch(Dispatchers.Default) {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val infos = getPackagesHoldingPermissions(pm, arrayOf(Manifest.permission.INTERNET))
                    val out = ArrayList<AppRow>(infos.size)
                    for (info in infos) {
                        val pkg = info.packageName
                        if (pkg == self) continue // не показываем себя
                        val appInfo = info.applicationInfo
                        val icon = appInfo?.loadIcon(pm) ?: pm.defaultActivityIcon
                        val label = appInfo?.loadLabel(pm)?.toString() ?: pkg
                        out.add(AppRow(pkg, label, icon, selected.contains(pkg)))
                    }
                    out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                    out
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MayakPresetEditorActivity,
                        getString(R.string.error_fetching_apps, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                ArrayList<AppRow>()
            }
            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(loaded)
                progress.visibility = View.GONE
                applyFilter(searchField.text?.toString().orEmpty())
                updateCounter()
            }
        }
    }

    /** Список пакетов с разрешением INTERNET (совместимо с TIRAMISU+ и старым API). */
    private fun getPackagesHoldingPermissions(pm: PackageManager, permissions: Array<String>): List<PackageInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackagesHoldingPermissions(permissions, PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackagesHoldingPermissions(permissions, 0)
        }
    }

    /** Фильтр списка по названию (регистронезависимо). */
    private fun applyFilter(query: String) {
        val q = query.trim()
        shownApps.clear()
        if (q.isEmpty()) {
            shownApps.addAll(allApps)
        } else {
            for (app in allApps) if (app.label.contains(q, ignoreCase = true)) shownApps.add(app)
        }
        adapter.notifyDataSetChanged()
    }

    /** «Выбрано X из Y»: X — отмеченные по ВСЕМ приложениям, Y — всего приложений. */
    private fun updateCounter() {
        val total = allApps.size
        val checked = allApps.count { it.checked }
        counter.text = getString(R.string.mayak_preset_editor_counter, checked, total)
    }

    /** Возврат результата вызывающей стороне. Для режима «Все» набор приложений пуст. */
    private fun save() {
        val name = nameField.text?.toString()?.trim().orEmpty()
        val apps = if (mode == MODE_ALL) ArrayList()
        else ArrayList(allApps.filter { it.checked }.map { it.packageName })
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_MODE, mode)
                .putStringArrayListExtra(EXTRA_APPS, apps),
        )
        finish()
        MayakTransitions.applyAxisReverse(this)
    }

    // Адаптер списка приложений: иконка+имя+галочка; клик по строке переключает галочку.
    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: TextView = view.findViewById(R.id.app_name)
            val pkg: TextView = view.findViewById(R.id.app_pkg)
            val check: MaterialCheckBox = view.findViewById(R.id.app_check)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val app = shownApps[pos]
                    app.checked = !app.checked
                    check.isChecked = app.checked
                    updateCounter()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mayak_app_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = shownApps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.label
            holder.pkg.text = app.packageName
            holder.check.isChecked = app.checked
        }

        override fun getItemCount(): Int = shownApps.size
    }

    companion object {
        const val EXTRA_ID = "mayak_preset_id"
        const val EXTRA_NAME = "mayak_preset_name"
        const val EXTRA_MODE = "mayak_preset_mode"
        const val EXTRA_APPS = "mayak_preset_apps"
        const val EXTRA_EDITABLE = "mayak_preset_editable"

        const val MODE_ALL = "all"
        const val MODE_EXCLUDE = "exclude"
        const val MODE_INCLUDE = "include"
        private val VALID_MODES = setOf(MODE_ALL, MODE_EXCLUDE, MODE_INCLUDE)

        /** Собрать Intent для запуска редактора одного пресета. */
        fun intent(
            context: Context,
            id: Long,
            name: String,
            mode: String,
            apps: List<String>,
            editable: Boolean,
        ): Intent = Intent(context, MayakPresetEditorActivity::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_MODE, mode)
            .putStringArrayListExtra(EXTRA_APPS, ArrayList(apps))
            .putExtra(EXTRA_EDITABLE, editable)
    }
}
