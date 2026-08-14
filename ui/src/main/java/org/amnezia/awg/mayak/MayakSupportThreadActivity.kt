// Переписка по одному обращению: наши ответы и возможность дописать.
//
// Зачем отдельный экран. До 08-08 обращение уезжало письмом, и в приложении от него не оставалось
// НИЧЕГО: на вопрос «а что там с моим вопросом» экран отвечал молчанием, а человек писал второе
// обращение о том же. Теперь обращение — сущность в базе (ядро: support_tickets.go), и клиент читает
// её тем же токеном.
//
// 🔒 Открытие нитки = «человек это прочитал»: ядро тем же запросом двигает user_seen_at, по которому
// гаснет пометка «есть новый ответ». Поэтому дёргать GET нитки «на всякий случай» в фоне нельзя —
// это погасило бы пометку, которую человек не видел.
package org.amnezia.awg.mayak

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.SupportLimits
import org.amnezia.awg.mayak.core.SupportMessage
import org.amnezia.awg.mayak.core.SupportThread
import org.amnezia.awg.mayak.core.canRetrySameText
import org.amnezia.awg.mayak.core.supportFailure

class MayakSupportThreadActivity : AppCompatActivity() {

    private val store by lazy { KeystoreSecureStore(this) }
    private val session by lazy { MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store)) }

    private var ticketId: Long = 0
    private var sending = false

    private fun backend(): MayakBackend =
        MayakBackend(
            HostProvider(MayakHostList.effective(this, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(this),
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_support_thread)
        MayakSystemBars.apply(this)
        applyInsets()

        ticketId = intent?.getLongExtra(EXTRA_TICKET_ID, 0L) ?: 0L
        findViewById<TextView>(R.id.mayak_thread_title).text =
            getString(R.string.mayak_support_thread_title, ticketId)

        findViewById<MaterialButton>(R.id.mayak_thread_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }
        // Потолок счётчика — из ОДНОГО места с проверкой перед отправкой (в XML то же число стоит
        // только ради превью): разъедутся — счётчик разрешит то, что мы потом отвергнем.
        findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.mayak_thread_input_layout
        ).counterMaxLength = SupportLimits.MAX_CHARS
        findViewById<MaterialButton>(R.id.mayak_thread_send).setOnClickListener { sendReply() }
        findViewById<MaterialButton>(R.id.mayak_thread_retry).setOnClickListener { sendReply() }

        if (savedInstanceState == null) {
            drafts().getString(draftKey(), null)?.let { input().setText(it) }
        }
        // Номера обращения нет (пришли не оттуда) — читать нечего, и врать про «загружаем» не будем.
        if (ticketId <= 0) {
            showState(getString(R.string.mayak_support_err_not_found))
            return
        }
        load()
    }

    /** Черновик дополнения — на диск, по ключу обращения: в разных нитках недописанное разное. */
    override fun onPause() {
        super.onPause()
        val text = input().text?.toString().orEmpty()
        drafts().edit().apply {
            if (text.isBlank()) remove(draftKey()) else putString(draftKey(), text)
        }.apply()
    }

    private fun applyInsets() = MayakSystemBars.padForBars(findViewById(R.id.mayak_thread_content))

    private fun input(): TextInputEditText = findViewById(R.id.mayak_thread_input)

    private fun drafts() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun draftKey() = "reply_$ticketId"

    private fun load() {
        showState(getString(R.string.mayak_support_tickets_loading))
        lifecycleScope.launch {
            try {
                render(session.supportThread(backend(), ticketId))
            } catch (e: Exception) {
                showState(
                    getString(
                        R.string.mayak_support_thread_err,
                        MayakSupport.failureText(this@MayakSupportThreadActivity, e),
                    )
                )
            }
        }
    }

    private fun render(thread: SupportThread) {
        findViewById<TextView>(R.id.mayak_thread_subject).text = thread.ticket.subject
        val when_ = thread.ticket.lastMessageMs() ?: thread.ticket.createdMs()
        findViewById<TextView>(R.id.mayak_thread_status).text = when {
            when_ != null -> getString(
                R.string.mayak_support_ticket_meta, thread.ticket.statusText, ago(when_)
            )
            else -> thread.ticket.statusText
        }
        val list = findViewById<LinearLayout>(R.id.mayak_thread_messages)
        list.removeAllViews()
        for (m in thread.messages) list.addView(messageView(list, m))
        showState(null)
        // Дописать можно, только когда нитка прочитана: поле поверх неизвестного состояния означало бы
        // «пишите в пустоту» (а при 404 — в чужое обращение).
        findViewById<View>(R.id.mayak_thread_reply_card).visibility = View.VISIBLE
    }

    private fun messageView(parent: LinearLayout, m: SupportMessage): View {
        val v = layoutInflater.inflate(R.layout.item_mayak_support_message, parent, false)
        // Имя автора («Вы» / «Поддержка») собрало ЯДРО: логина ответившего админа в клиенте нет.
        val ms = m.createdMs()
        v.findViewById<TextView>(R.id.support_msg_author).text = when {
            ms != null -> getString(R.string.mayak_support_ticket_meta, m.authorName, ago(ms))
            else -> m.authorName
        }
        v.findViewById<TextView>(R.id.support_msg_body).text = m.body
        v.setBackgroundResource(
            if (m.mine()) R.drawable.mayak_bg_support_msg_mine else R.drawable.mayak_bg_support_msg_them
        )
        return v
    }

    /**
     * Дописать в обращение. Нижнего порога длины нет (как и у ядра): «да», «помогло» — полноценные
     * ответы на наш же вопрос. При провале текст ОСТАЁТСЯ в поле и предлагается «Повторить».
     */
    private fun sendReply() {
        if (sending) return
        val text = input().text?.toString()?.trim().orEmpty()
        SupportLimits.replyProblem(text)?.let { problem ->
            showReplyState(MayakSupport.localProblemText(this, problem), retry = false)
            input().requestFocus()
            return
        }
        val send = findViewById<MaterialButton>(R.id.mayak_thread_send)
        sending = true
        send.isEnabled = false
        send.setText(R.string.mayak_support_sending)
        showReplyState(getString(R.string.mayak_support_sending), retry = false)
        lifecycleScope.launch {
            try {
                session.replySupport(backend(), ticketId, text)
                input().setText("")
                drafts().edit().remove(draftKey()).apply()
                showReplyState(getString(R.string.mayak_support_reply_sent), retry = false)
                // Перечитываем нитку: своё дописанное сообщение человек должен УВИДЕТЬ в переписке, а
                // не только прочитать «отправлено» (иначе непонятно, дошло ли оно на самом деле).
                //
                // runCatching намеренно: сообщение УЖЕ записано ядром, и если перечитывание не удалось
                // (сеть мигнула сразу после отправки), сказать «не ушло» означало бы соврать в другую
                // сторону — человек напишет то же самое второй раз.
                runCatching { render(session.supportThread(backend(), ticketId)) }
            } catch (e: Exception) {
                showReplyState(
                    MayakSupport.failureText(this@MayakSupportThreadActivity, e),
                    retry = supportFailure(e).canRetrySameText,
                )
            } finally {
                sending = false
                send.isEnabled = true
                send.setText(R.string.mayak_support_send)
            }
        }
    }

    /** Состояние ЗАГРУЗКИ нитки. null — нитка на экране, состояние не нужно. */
    private fun showState(text: String?) {
        val state = findViewById<TextView>(R.id.mayak_thread_state)
        if (text == null) {
            state.visibility = View.GONE
            return
        }
        state.text = text
        state.visibility = View.VISIBLE
    }

    /** Состояние ОТПРАВКИ дополнения (отдельно от загрузки: это разные вещи в разных карточках). */
    private fun showReplyState(text: String?, retry: Boolean) {
        val state = findViewById<TextView>(R.id.mayak_thread_reply_state)
        val retryButton = findViewById<MaterialButton>(R.id.mayak_thread_retry)
        if (text == null) {
            state.visibility = View.GONE
            retryButton.visibility = View.GONE
            return
        }
        state.text = text
        state.visibility = View.VISIBLE
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun ago(ms: Long): CharSequence = android.text.format.DateUtils.getRelativeTimeSpanString(
        ms, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
    )

    companion object {
        const val EXTRA_TICKET_ID = "mayak_support_ticket_id"
        private const val PREFS = "mayak_support"
    }
}
