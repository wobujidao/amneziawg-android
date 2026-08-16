// Экран «Сообщения» (SPEC-0047): всё, что мы человеку написали, — в одном месте.
//
// 🔴 Зачем экран, если есть уведомления. Уведомление живёт до первого смахивания, а шторку люди
// чистят пачками. Ровно те три вещи, ради которых ящик и заводился (доступ заканчивается, чек об
// оплате, приглашение в общий доступ), стоят денег и доступа — им нужно место, где их можно
// перечитать через день. Плюс полный текст показываем ТОЛЬКО здесь: в шторке заголовок нейтральный,
// а на заблокированном экране текста нет вовсе.
//
// Пусто и «не загрузилось» — РАЗНЫЕ состояния (правило проекта). Пустой ящик — это норма, о которой
// надо сказать словами; молчащая карточка читается как поломка.
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.MessageActions
import org.amnezia.awg.mayak.core.UserMessage

class MayakMessagesActivity : AppCompatActivity() {

    private val store by lazy { KeystoreSecureStore(this) }
    private val session by lazy { MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store)) }

    /** Что сейчас на экране: нужно, чтобы открыть карточку по id из уведомления после загрузки. */
    private var shown: List<UserMessage> = emptyList()

    /** Карточку какого сообщения открыть сразу (пришли из уведомления). 0 — просто открыть список. */
    private var openId: Long = 0

    /**
     * Что уже отметили прочитанным в ЭТОМ показе списка.
     *
     * Строка списка держит объект сообщения таким, каким он приехал с сервера (`read = false`), и
     * второй тап по той же строке отправил бы вторую отметку и второй раз убавил счётчик — бейдж
     * ушёл бы в минус на ровном месте.
     */
    private val markedRead = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_messages)
        MayakSystemBars.apply(this)
        applyInsets()

        openId = intent?.getLongExtra(EXTRA_MESSAGE_ID, 0L) ?: 0L

        findViewById<MaterialButton>(R.id.mayak_messages_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }
        findViewById<MaterialButton>(R.id.mayak_messages_reload).setOnClickListener { load() }
        findViewById<MaterialButton>(R.id.mayak_messages_retry).setOnClickListener { load() }
        findViewById<MaterialButton>(R.id.mayak_messages_login).setOnClickListener {
            startActivity(
                Intent(this, MayakActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }

        if (!session.hasToken()) {
            showNeedLogin()
            return
        }
        load()
    }

    /** Пришли по уведомлению, когда экран уже открыт — покажем ту карточку, а не прежнюю. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
        if (session.hasToken()) load()
    }

    private fun applyInsets() = MayakSystemBars.padForBars(findViewById(R.id.mayak_messages_content))

    // ===== Загрузка =====

    /**
     * Читаем ВЕСЬ ящик (since_id = 0): экран для того и открыт, чтобы перечитать старое. Анти-дребезг
     * тихих проверок здесь не применяем — человек пришёл именно за свежим.
     *
     * 🔴 Ходим через `MayakMessages.loadForScreen`, а НЕ мимо него своим запросом (как было до 13-08).
     * Причина не в красоте: пока этот экран забирал ящик сам, он был единственным путём, которым
     * сообщение попадало в телефон, — и ровно тем, где показ уведомления намеренно выключен. Забор и
     * показ разведены теперь в одном месте (шапка MayakMessages).
     */
    private fun load() {
        showState(getString(R.string.mayak_support_tickets_loading), retry = false)
        lifecycleScope.launch {
            try {
                render(MayakMessages.loadForScreen(this@MayakMessagesActivity))
            } catch (e: Exception) {
                findViewById<LinearLayout>(R.id.mayak_messages_list).removeAllViews()
                // «Не загрузилось» — это ОТДЕЛЬНОЕ состояние, с причиной словами. Причину берём у
                // ящика, а не у поддержки: её слова про «текст сохранён» и «обращение не найдено»
                // здесь были бы враньём (см. MayakMessages.failureText).
                showState(
                    MayakMessages.failureText(this@MayakMessagesActivity, e),
                    retry = MayakMessages.canRetry(e),
                )
            }
        }
    }

    private fun render(messages: List<UserMessage>) {
        shown = messages
        markedRead.clear() // список перечитан заново — отметки этого показа больше ни при чём
        val list = findViewById<LinearLayout>(R.id.mayak_messages_list)
        list.removeAllViews()
        if (messages.isEmpty()) {
            // Пусто — это честное «пока ничего», а не ошибка. Кнопки «Повторить» здесь быть не должно.
            showState(getString(R.string.mayak_messages_empty), retry = false)
            return
        }
        showState(null, retry = false)
        for (m in messages) list.addView(row(list, m))
        // Пришли из уведомления — сразу открываем ту самую карточку. Не нашли (успели прочитать в
        // кабинете и оно уехало) — просто остаёмся на списке, без сообщения об ошибке.
        if (openId > 0) {
            messages.firstOrNull { it.id == openId }?.let { openCard(it) }
            openId = 0
        }
    }

    private fun row(parent: LinearLayout, m: UserMessage): View {
        val v = layoutInflater.inflate(R.layout.item_mayak_message, parent, false)
        v.findViewById<TextView>(R.id.message_title).text = MayakMessages.title(this, m)
        v.findViewById<TextView>(R.id.message_preview).text = MayakMessages.body(this, m)
        val time = v.findViewById<TextView>(R.id.message_time)
        val ms = m.createdMs()
        if (ms != null) {
            time.text = ago(ms)
            time.visibility = View.VISIBLE
        } else {
            time.visibility = View.GONE
        }
        val badge = v.findViewById<TextView>(R.id.message_badge)
        badge.visibility = if (m.read) View.GONE else View.VISIBLE
        v.setOnClickListener { openCard(m); badge.visibility = View.GONE }
        return v
    }

    /** Состояние под списком. null — состояния нет (ящик на экране). */
    private fun showState(text: String?, retry: Boolean) {
        val state = findViewById<TextView>(R.id.mayak_messages_state)
        val retryButton = findViewById<MaterialButton>(R.id.mayak_messages_retry)
        if (text == null) {
            state.visibility = View.GONE
            retryButton.visibility = View.GONE
            return
        }
        state.text = text
        state.visibility = View.VISIBLE
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun showNeedLogin() {
        showState(getString(R.string.mayak_messages_err_login), retry = false)
        findViewById<MaterialButton>(R.id.mayak_messages_login).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.mayak_messages_reload).visibility = View.GONE
    }

    // ===== Карточка сообщения =====

    /**
     * Открыть карточку. Открытие И ЕСТЬ «прочитал»: отметка уходит на сервер отсюда, а счётчик
     * непрочитанного уменьшается сразу — бейдж, живущий своей жизнью до следующей сверки, читается
     * как поломка. Отказ сервера глотаем: он пришлёт свою правду при следующей проверке.
     */
    private fun openCard(m: UserMessage) {
        val view = layoutInflater.inflate(R.layout.sheet_mayak_message, null)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        view.findViewById<TextView>(R.id.mayak_message_title).text = MayakMessages.title(this, m)
        view.findViewById<TextView>(R.id.mayak_message_body).text = MayakMessages.body(this, m)
        val time = view.findViewById<TextView>(R.id.mayak_message_time)
        val ms = m.createdMs()
        if (ms != null) {
            time.text = ago(ms)
            time.visibility = View.VISIBLE
        } else {
            time.visibility = View.GONE
        }
        val action = view.findViewById<MaterialButton>(R.id.mayak_message_action)
        val label = actionLabel(m.action)
        if (label != null) {
            action.text = getString(label)
            action.visibility = View.VISIBLE
            action.setOnClickListener { sheet.dismiss(); runAction(m) }
        } else {
            action.visibility = View.GONE
        }
        view.findViewById<MaterialButton>(R.id.mayak_message_close).setOnClickListener { sheet.dismiss() }
        sheet.show()

        if (!m.read && markedRead.add(m.id)) {
            lifecycleScope.launch { MayakMessages.markRead(this@MayakMessagesActivity, m.id) }
            shown = shown.map { if (it.id == m.id) it.copy(read = true) else it }
        }
    }

    /** Название кнопки действия. null — действия нет (`none` или код, которого мы не знаем):
     *  кнопка, ведущая в никуда, хуже её отсутствия. */
    private fun actionLabel(action: String): Int? = when (action) {
        MessageActions.BILLING -> R.string.mayak_messages_action_billing
        MessageActions.SUPPORT -> R.string.mayak_messages_action_support
        MessageActions.GROUP -> R.string.mayak_messages_action_group
        MessageActions.DEVICES -> R.string.mayak_messages_action_devices
        MessageActions.CONNECT -> R.string.mayak_messages_action_connect
        MessageActions.SETTINGS -> R.string.mayak_messages_action_settings
        else -> null
    }

    /**
     * Куда ведёт кнопка. Наружу, в браузер, уходим ТОЛЬКО за оплатой — она живёт в кабинете.
     *
     * ⚠️ `group` тоже уводит в кабинет, и это временно: своего экрана общего доступа (дерево,
     * SPEC-0044) в приложении пока НЕТ. Когда он появится — менять надо здесь, одну строку; пока же
     * лучше открыть то место, где приглашение действительно можно принять, чем показать заглушку.
     */
    private fun runAction(m: UserMessage) {
        when (m.action) {
            MessageActions.BILLING, MessageActions.GROUP -> openUrl(MayakHostList.cabinetUrl(this))

            MessageActions.SUPPORT -> {
                val ticket = m.actionParam.trim().toLongOrNull() ?: 0L
                if (ticket > 0) {
                    startActivity(
                        Intent(this, MayakSupportThreadActivity::class.java)
                            .putExtra(MayakSupportThreadActivity.EXTRA_TICKET_ID, ticket)
                    )
                } else {
                    // Номера обращения нет — ведём в список своих обращений, а не в пустую нитку.
                    startActivity(Intent(this, MayakSupportActivity::class.java))
                }
                MayakTransitions.applyAxis(this)
            }

            MessageActions.DEVICES -> MayakDevices.show(this)

            MessageActions.SETTINGS -> {
                startActivity(Intent(this, MayakSettingsActivity::class.java))
                MayakTransitions.applyAxis(this)
            }

            MessageActions.CONNECT -> {
                startActivity(
                    Intent(this, MayakActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                MayakTransitions.applyAxisReverse(this)
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    // Время события приходит с СЕРВЕРА, а часы у телефона свои: без общей функции свежая
    // запись показывалась как «через 0 минут» (MayakTime).
    private fun ago(ms: Long): CharSequence = MayakTime.ago(ms)

    companion object {
        /** id сообщения, карточку которого открыть сразу (тап по уведомлению). */
        const val EXTRA_MESSAGE_ID = "mayak_message_id"

        /** Открыть экран «Сообщения» откуда угодно. */
        fun open(activity: android.app.Activity) {
            activity.startActivity(Intent(activity, MayakMessagesActivity::class.java))
            MayakTransitions.applyAxis(activity)
        }
    }
}
