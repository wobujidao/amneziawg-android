// Экран «Поддержка»: ФОРМА обращения вместо `mailto:`, свои обращения и запасные пути.
//
// 🔴 Что тут чинится. Единственной кнопкой «написать» в приложении был `mailto:`. У человека без
// настроенного почтового клиента она не делает НИЧЕГО — система молча проглатывает нажатие. То есть
// в единственной ситуации, ради которой поддержку и открывают («у меня не работает»), путь был
// пустой, и узнать об этом мы не могли по построению: тот, кто не смог написать, не пишет и о том,
// что не смог. В кабинете это закрыли формой 08-08, здесь — тем же способом и той же ручкой ядра.
//
// Три честных состояния отправки, и ни одного «сделаем вид». Отправляется — кнопка занята и говорит
// об этом; отправлено — НОМЕР обращения и куда придёт ответ; не ушло — причина СЛОВАМИ и «Повторить»,
// а текст человека остаётся в поле. Экран «отправлено» поверх неотправленного — это ровно тот дефект,
// который месяц держал регистрацию мёртвой («код отправлен» при погашенной почте).
//
// Контекст (тариф, срок доступа, устройства, версия приложения) собирает СЕРВЕР из базы по сессии.
// Клиент его не присылает и не дублирует: два контекста в одном обращении означали бы, что один из
// них врёт, и разбирающему пришлось бы решать, какой.
package org.amnezia.awg.mayak

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.SupportFailure
import org.amnezia.awg.mayak.core.SupportLimits
import org.amnezia.awg.mayak.core.SupportTicket
import org.amnezia.awg.mayak.core.SupportTopics
import org.amnezia.awg.mayak.core.canRetrySameText
import org.amnezia.awg.mayak.core.supportFailure
import org.amnezia.awg.mayak.core.supportResendAsOther

class MayakSupportActivity : AppCompatActivity() {

    private val store by lazy { KeystoreSecureStore(this) }
    private val session by lazy { MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store)) }

    /** Выбранная тема — КОД ядра (SupportTopics.CODES). По умолчанию первая в списке. */
    private var topic: String = SupportTopics.CODES.first()

    /** Идёт отправка: второй тап по «Отправить» не должен заводить второе обращение тем же текстом. */
    private var sending = false

    /** Номер только что принятого обращения (0 — карточку «принято» не показываем) и адрес ответа.
     *  Держим полями, чтобы поворот экрана не съел единственное подтверждение «обращение ушло». */
    private var sentTicketId: Long = 0
    private var sentReplyTo: String = ""

    private fun backend(): MayakBackend =
        MayakBackend(
            HostProvider(MayakHostList.effective(this, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(this),
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_support)
        MayakSystemBars.apply(this)
        applyInsets()

        findViewById<MaterialButton>(R.id.mayak_support_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }

        // Тема пережила поворот экрана? Восстанавливаем её ДО первой отрисовки кнопки, иначе человек
        // после поворота отправил бы обращение с чужой темой, ничего об этом не зная.
        savedInstanceState?.getString(STATE_TOPIC)?.let { if (it in SupportTopics.CODES) topic = it }
        // Потолок счётчика под полем — из ОДНОГО места с проверкой перед отправкой (в XML он стоит
        // тем же числом только ради превью в студии): разъедутся — счётчик разрешит то, что мы отвергнем.
        findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.mayak_support_input_layout
        ).counterMaxLength = SupportLimits.MAX_CHARS
        renderTopic()
        findViewById<MaterialButton>(R.id.mayak_support_topic).setOnClickListener { showTopicDialog() }
        findViewById<MaterialButton>(R.id.mayak_support_send).setOnClickListener { send() }
        findViewById<MaterialButton>(R.id.mayak_support_retry).setOnClickListener { send() }
        findViewById<MaterialButton>(R.id.mayak_support_write_more).setOnClickListener { showFormAgain() }
        findViewById<MaterialButton>(R.id.mayak_support_tickets_reload).setOnClickListener { loadTickets() }

        // Запасные пути. Адрес — текстом (его можно выделить пальцем) + кнопкой в буфер: это тот путь,
        // который работает, даже когда не работает ничего нашего.
        findViewById<TextView>(R.id.mayak_support_email_line).text =
            getString(R.string.mayak_support_email_line, MayakSupport.email)
        findViewById<MaterialButton>(R.id.mayak_support_copy_email).setOnClickListener {
            MayakSupport.copyEmail(this, R.string.mayak_support_email_copied)
        }
        findViewById<MaterialButton>(R.id.mayak_support_mailto).setOnClickListener {
            MayakSupport.writeToSupport(this, session.email())
        }
        // Справка живёт в реестре доменов и может быть не задана — тогда прячем кнопку, а не показываем
        // неработающую (то же правило, что в настройках).
        val help = findViewById<MaterialButton>(R.id.mayak_support_help)
        if (MayakHostList.helpUrl(this) != null) {
            help.setOnClickListener { MayakSupport.openHelp(this) }
        } else {
            help.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.mayak_support_login).setOnClickListener {
            startActivity(
                Intent(this, MayakActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }

        // Черновик с прошлого раза (ушли с экрана, процесс убили, отправка не прошла) — возвращаем.
        // Поворот экрана переживает сама система (freezesText), это про случаи ЖЁСТЧЕ поворота.
        if (savedInstanceState == null) {
            drafts().getString(K_DRAFT, null)?.let { input().setText(it) }
        }

        // Обращение приняли, а потом человек повернул телефон: карточку «принято» показываем снова,
        // иначе он видит пустую форму и не знает, ушло ли (спросить об этом ему как раз некого).
        if (savedInstanceState?.getBoolean(STATE_SENT_SHOWN) == true) {
            showSentCard(
                savedInstanceState.getLong(STATE_SENT_ID, 0L),
                savedInstanceState.getString(STATE_SENT_REPLY_TO).orEmpty(),
            )
        }

        if (!session.hasToken()) showLoginRequired()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TOPIC, topic)
        outState.putBoolean(
            STATE_SENT_SHOWN,
            findViewById<View>(R.id.mayak_support_sent_card).visibility == View.VISIBLE,
        )
        outState.putLong(STATE_SENT_ID, sentTicketId)
        outState.putString(STATE_SENT_REPLY_TO, sentReplyTo)
    }

    override fun onResume() {
        super.onResume()
        // Список перечитываем на каждый показ: вернулись из переписки — пометка «есть ответ» там уже
        // погасла, и оставить её здесь означало бы врать про непрочитанное.
        if (session.hasToken()) loadTickets()
    }

    /**
     * Черновик на диск. Написанное человеком в момент, когда у него всё сломалось, — самое дорогое
     * на этом экране: он это уже один раз сформулировал. Обычные prefs (не SecureStore): это его же
     * текст, который он и так видит на экране, а не секрет.
     */
    override fun onPause() {
        super.onPause()
        val text = input().text?.toString().orEmpty()
        drafts().edit().apply {
            if (text.isBlank()) remove(K_DRAFT) else putString(K_DRAFT, text)
        }.apply()
    }

    private fun applyInsets() {
        val content = findViewById<View>(R.id.mayak_support_content)
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = baseTop + bars.top, bottom = baseBottom + bars.bottom)
            insets
        }
    }

    private fun input(): TextInputEditText = findViewById(R.id.mayak_support_input)

    private fun drafts() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Название темы человеку. Код есть, а перевода нет (ядро завело новую) → показываем сам код. */
    private fun topicLabel(code: String): String = when (code) {
        "connect" -> getString(R.string.mayak_support_topic_connect)
        "speed" -> getString(R.string.mayak_support_topic_speed)
        "payment" -> getString(R.string.mayak_support_topic_payment)
        "account" -> getString(R.string.mayak_support_topic_account)
        "app" -> getString(R.string.mayak_support_topic_app)
        SupportTopics.OTHER -> getString(R.string.mayak_support_topic_other)
        else -> code
    }

    private fun renderTopic() {
        findViewById<MaterialButton>(R.id.mayak_support_topic).text =
            getString(R.string.mayak_support_topic_label, topicLabel(topic))
    }

    private fun showTopicDialog() {
        val codes = SupportTopics.CODES
        val labels = codes.map { topicLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.mayak_support_topic_choose)
            .setSingleChoiceItems(labels, codes.indexOf(topic).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                topic = codes[which]
                renderTopic()
            }
            .setNegativeButton(R.string.mayak_cancel, null)
            .show()
    }

    // ===== Отправка =====

    /**
     * Отправить обращение. Локальная проверка длины — ТЕМИ ЖЕ порогами, что у ядра (SupportLimits):
     * «слишком коротко» человек узнаёт сразу, а не после круга по РФ-сотовой.
     *
     * `asOther` — второй заход после отказа `unknown_topic`: списки тем разъехались, и мы уносим тот
     * же текст темой «Другое», вместо того чтобы упереть человека в отказ, которого он не может
     * исправить (других тем на экране нет). См. supportResendAsOther в :core.
     */
    private fun send(asOther: Boolean = false) {
        if (sending) return
        if (!session.hasToken()) {
            showLoginRequired()
            return
        }
        val text = input().text?.toString()?.trim().orEmpty()
        SupportLimits.firstMessageProblem(text)?.let { problem ->
            showState(MayakSupport.localProblemText(this, problem), retry = false)
            findViewById<com.google.android.material.textfield.TextInputLayout>(
                R.id.mayak_support_input_layout
            ).error = null
            input().requestFocus()
            return
        }

        val send = findViewById<MaterialButton>(R.id.mayak_support_send)
        sending = true
        send.isEnabled = false
        send.setText(R.string.mayak_support_sending)
        showState(getString(R.string.mayak_support_sending), retry = false)

        lifecycleScope.launch {
            // Переотправку темой «Другое» пускаем ПОСЛЕ finally, а не из catch: иначе внешний finally
            // снял бы флаг `sending` и разблокировал кнопку, пока второй запрос ещё в полёте — и один
            // тап человека мог бы превратиться в два обращения.
            var resendAsOther = false
            try {
                val sent = session.createSupportTicket(backend(), if (asOther) SupportTopics.OTHER else topic, text)
                onSent(sent.replyTo)
            } catch (e: Exception) {
                val failure = supportFailure(e)
                // Тема не понравилась ядру — уходим на «Другое» ОДИН раз (asOther защищает от цикла).
                if (!asOther && supportResendAsOther(failure)) {
                    resendAsOther = true
                } else {
                    showState(MayakSupport.failureText(this@MayakSupportActivity, e), retry = failure.canRetrySameText)
                    // Канала отправки у ядра нет вовсе — довозим человека до запасного пути глазами,
                    // а не оставляем искать его самому под тремя карточками.
                    if (failure == SupportFailure.CHANNEL_OFF) scrollToOtherWays()
                    if (failure == SupportFailure.NEED_LOGIN) showLoginRequired()
                }
            } finally {
                sending = false
                send.isEnabled = true
                send.setText(R.string.mayak_support_send)
            }
            if (resendAsOther) send(asOther = true)
        }
    }

    /**
     * Обращение принято. Номер ядро в ответе на создание НЕ отдаёт (там только status и reply_to),
     * поэтому берём его из списка своих обращений — свежайшее и есть только что созданное. Список не
     * прочитался (сеть мигнула) — говорим «обращение принято» БЕЗ номера: выдуманный номер хуже
     * отсутствующего, а обращение уже у нас.
     */
    private suspend fun onSent(replyTo: String) {
        input().setText("")
        drafts().edit().remove(K_DRAFT).apply()
        showState(null, retry = false)

        val fresh = runCatching { session.supportTickets(backend()) }.getOrNull()
        showSentCard(fresh?.tickets?.maxByOrNull { it.id }?.id ?: 0L, replyTo)
        fresh?.let { renderTickets(it.tickets) } ?: loadTickets()
    }

    /**
     * Карточка «принято». Отдельной функцией, потому что её надо показывать ДВАЖДЫ: сразу после
     * отправки и после поворота экрана. Без второго вызова человек, повернувший телефон, видел бы
     * пустую форму и не знал, ушло ли его обращение — а спросить об этом ему как раз некого.
     *
     * ticketId = 0 — номер узнать не удалось (список не прочитался). Тогда говорим «обращение принято»
     * БЕЗ номера: выдуманный номер хуже отсутствующего, а обращение у нас уже есть.
     */
    private fun showSentCard(ticketId: Long, replyTo: String) {
        sentTicketId = ticketId
        sentReplyTo = replyTo
        findViewById<View>(R.id.mayak_support_form_card).visibility = View.GONE
        findViewById<TextView>(R.id.mayak_support_sent_title).text =
            if (ticketId > 0) getString(R.string.mayak_support_sent_title, ticketId)
            else getString(R.string.mayak_support_sent_title_nonum)
        // Куда придёт ответ — ЕГО адресом, а не «ответим на почту» (на какую?). Пусто (у аккаунта нет
        // почты) → говорим правду: ответ будет здесь же.
        findViewById<TextView>(R.id.mayak_support_sent_body).text =
            if (replyTo.isNotBlank()) getString(R.string.mayak_support_sent_reply_to, replyTo)
            else getString(R.string.mayak_support_sent_reply_here)
        val open = findViewById<MaterialButton>(R.id.mayak_support_open_ticket)
        if (ticketId > 0) {
            open.visibility = View.VISIBLE
            open.setOnClickListener { openThread(ticketId) }
        } else {
            open.visibility = View.GONE
        }
        findViewById<View>(R.id.mayak_support_sent_card).visibility = View.VISIBLE
    }

    private fun showFormAgain() {
        sentTicketId = 0
        sentReplyTo = ""
        findViewById<View>(R.id.mayak_support_sent_card).visibility = View.GONE
        findViewById<View>(R.id.mayak_support_form_card).visibility = View.VISIBLE
        input().requestFocus()
    }

    /** Состояние отправки словами. null — состояния нет (строку и кнопку «Повторить» убираем). */
    private fun showState(text: String?, retry: Boolean) {
        val state = findViewById<TextView>(R.id.mayak_support_state)
        val retryButton = findViewById<MaterialButton>(R.id.mayak_support_retry)
        if (text == null) {
            state.visibility = View.GONE
            retryButton.visibility = View.GONE
            return
        }
        state.text = text
        state.visibility = View.VISIBLE
        // «Повторить» показываем только там, где повтор может сработать: кнопка, которая всегда
        // возвращает одну и ту же ошибку, — это тупик с иллюзией действия (canRetrySameText в :core).
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun scrollToOtherWays() {
        val scroll = findViewById<android.widget.ScrollView>(R.id.mayak_support_scroll)
        val card = findViewById<View>(R.id.mayak_support_other_card)
        scroll.post { scroll.smoothScrollTo(0, card.top) }
    }

    /** Не вошли (или сессия отозвана): форма бесполезна, но экран не тупик — запасные пути остаются. */
    private fun showLoginRequired() {
        findViewById<View>(R.id.mayak_support_form_card).visibility = View.GONE
        findViewById<View>(R.id.mayak_support_sent_card).visibility = View.GONE
        findViewById<View>(R.id.mayak_support_tickets_card).visibility = View.GONE
        findViewById<View>(R.id.mayak_support_login_card).visibility = View.VISIBLE
    }

    // ===== Мои обращения =====

    private fun loadTickets() {
        val state = findViewById<TextView>(R.id.mayak_support_tickets_state)
        state.visibility = View.VISIBLE
        state.setText(R.string.mayak_support_tickets_loading)
        lifecycleScope.launch {
            try {
                renderTickets(session.supportTickets(backend()).tickets)
            } catch (e: Exception) {
                findViewById<LinearLayout>(R.id.mayak_support_tickets_list).removeAllViews()
                state.text = getString(
                    R.string.mayak_support_tickets_err,
                    MayakSupport.failureText(this@MayakSupportActivity, e),
                )
                if (supportFailure(e) == SupportFailure.NEED_LOGIN) showLoginRequired()
            }
        }
    }

    private fun renderTickets(tickets: List<SupportTicket>) {
        val list = findViewById<LinearLayout>(R.id.mayak_support_tickets_list)
        val state = findViewById<TextView>(R.id.mayak_support_tickets_state)
        list.removeAllViews()
        if (tickets.isEmpty()) {
            // Пустое состояние — СЛОВАМИ. Молчащая карточка читается как «не загрузилось».
            state.setText(R.string.mayak_support_tickets_empty)
            state.visibility = View.VISIBLE
            return
        }
        state.visibility = View.GONE
        for (t in tickets) list.addView(ticketRow(list, t))
    }

    private fun ticketRow(parent: LinearLayout, t: SupportTicket): View {
        val v = layoutInflater.inflate(R.layout.item_mayak_support_ticket, parent, false)
        v.findViewById<TextView>(R.id.support_ticket_subject).text =
            t.subject.ifBlank { topicLabel(t.topic) }
        // Статус словами берём с ядра (status_text) — вторая копия перевода в клиенте разъехалась бы
        // с белым списком статусов. Время — относительное, языком системы.
        val when_ = t.lastMessageMs() ?: t.createdMs()
        v.findViewById<TextView>(R.id.support_ticket_meta).text = when {
            when_ != null -> getString(R.string.mayak_support_ticket_meta, t.statusText, ago(when_))
            else -> t.statusText
        }
        v.findViewById<TextView>(R.id.support_ticket_badge).visibility =
            if (t.newAnswer) View.VISIBLE else View.GONE
        v.setOnClickListener { openThread(t.id) }
        return v
    }

    private fun openThread(id: Long) {
        startActivity(
            Intent(this, MayakSupportThreadActivity::class.java)
                .putExtra(MayakSupportThreadActivity.EXTRA_TICKET_ID, id)
        )
        MayakTransitions.applyAxis(this)
    }

    private fun ago(ms: Long): CharSequence = android.text.format.DateUtils.getRelativeTimeSpanString(
        ms, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
    )

    companion object {
        private const val PREFS = "mayak_support"
        private const val K_DRAFT = "draft"
        private const val STATE_TOPIC = "topic"
        private const val STATE_SENT_SHOWN = "sent_shown"
        private const val STATE_SENT_ID = "sent_id"
        private const val STATE_SENT_REPLY_TO = "sent_reply_to"
    }
}
