// «Мои устройства» — список устройств аккаунта и отключение лишнего ПРЯМО В ПРИЛОЖЕНИИ.
//
// Зачем: до 07-08 приложение умело только ЗАНИМАТЬ место (POST /v1/client/devices), а освобождать
// его предлагало «в кабинете» — то есть во внешнем браузере, с отдельным входом, ровно в тот момент,
// когда VPN не поднялся (а у части людей и сайт не открывается). Человек, сменивший телефон,
// упирался в тупик: «Занято максимум устройств» → «Открыть кабинет» → первый запуск Chrome → вход.
// Ручки GET/DELETE /v1/client/devices у ядра были с самого начала, ими пользовался только кабинет.
//
// Диалог, а не отдельный экран: список короткий (лимит устройств — единицы), а действие ровно одно.
package org.amnezia.awg.mayak

import android.app.Activity
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.DeviceItem
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend

object MayakDevices {

    /**
     * Открыть список устройств. [onChanged] зовётся после успешного отключения — экрану, откуда
     * пришли, обычно нужно перерисовать «Устройства: N из M».
     *
     * Требует входа: без токена показываем то же сообщение, что и остальные места, требующие аккаунт.
     */
    fun <T> show(activity: T, onChanged: () -> Unit = {}) where T : Activity, T : LifecycleOwner {
        val store = KeystoreSecureStore(activity)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(activity, store))
        if (!session.hasToken()) {
            Toast.makeText(activity, R.string.mayak_settings_send_log_need_login, Toast.LENGTH_LONG).show()
            return
        }
        val backend = MayakBackend(
            HostProvider(MayakHostList.effective(activity, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(activity),
        )

        // Список тянется по сети: без этого диалога между нажатием и появлением списка было бы
        // несколько секунд тишины, неотличимой от «кнопка не сработала».
        val loading = AlertDialog.Builder(activity)
            .setTitle(R.string.mayak_devices_title)
            .setMessage(R.string.mayak_devices_loading)
            .setCancelable(true)
            .show()

        activity.lifecycleScope.launch {
            val devices = try {
                session.listDevices(backend)
            } catch (e: Exception) {
                loading.dismiss()
                Toast.makeText(activity, activity.getString(R.string.mayak_devices_err, human(activity, e)), Toast.LENGTH_LONG).show()
                return@launch
            }
            loading.dismiss()
            if (activity.isFinishing) return@launch
            showList(activity, session, backend, devices, onChanged)
        }
    }

    private fun <T> showList(
        activity: T,
        session: MayakSession,
        backend: MayakBackend,
        devices: List<DeviceItem>,
        onChanged: () -> Unit,
    ) where T : Activity, T : LifecycleOwner {
        if (devices.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.mayak_devices_title)
                .setMessage(R.string.mayak_devices_empty)
                .setPositiveButton(R.string.mayak_ok, null)
                .show()
            return
        }
        val self = session.deviceId()
        val view = activity.layoutInflater.inflate(R.layout.dialog_mayak_devices, null)
        val list = view.findViewById<LinearLayout>(R.id.mayak_devices_list)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.mayak_devices_title)
            .setView(view)
            .setNegativeButton(R.string.mayak_close, null)
            .create()
        for (d in devices) {
            list.addView(row(activity, list, d, self) {
                dialog.dismiss()
                confirmRevoke(activity, session, backend, d, self, onChanged)
            })
        }
        dialog.show()
    }

    /** Строка устройства: имя (+ пометка своего), когда последний раз выходило на связь, и «Отключить». */
    private fun row(
        activity: Activity,
        parent: LinearLayout,
        d: DeviceItem,
        selfId: Long,
        onRevoke: () -> Unit,
    ): View {
        val v = activity.layoutInflater.inflate(R.layout.item_mayak_device, parent, false)
        val name = d.label.ifBlank { activity.getString(R.string.mayak_devices_unnamed) }
        v.findViewById<TextView>(R.id.device_name).text = name
        val seen = d.lastSeenMs()
        val created = d.createdAtMs()
        val when_ = when {
            seen != null -> activity.getString(R.string.mayak_devices_last_seen, ago(seen))
            created != null -> activity.getString(R.string.mayak_devices_never, ago(created))
            else -> ""
        }
        // «это устройство» — во ВТОРОЙ строке, а не приклеенное к имени: имена телефонов длинные
        // («Google sdk_gphone64_x86_64 · Android 15»), и пометка уезжала под многоточие ровно у того
        // устройства, ради которого она и нужна (поймано на эмуляторе 07-08 до выката).
        val mine = if (d.id == selfId) activity.getString(R.string.mayak_devices_this) else ""
        val second = listOf(mine, when_).filter { it.isNotBlank() }.joinToString(" · ")
        val sub = v.findViewById<TextView>(R.id.device_seen)
        if (second.isBlank()) sub.visibility = View.GONE else sub.text = second
        v.findViewById<MaterialButton>(R.id.device_revoke).setOnClickListener { onRevoke() }
        return v
    }

    /** «5 минут назад», «вчера», «12 авг.» — словами системы, на языке телефона. */
    private fun ago(ms: Long): CharSequence =
        MayakTime.ago(ms)

    /**
     * Подтверждение с ЦЕНОЙ действия (правило проекта: опасная кнопка спрашивает и называет цену).
     * Для своего же устройства текст отдельный: там цена другая — оборвётся текущее подключение.
     */
    private fun <T> confirmRevoke(
        activity: T,
        session: MayakSession,
        backend: MayakBackend,
        device: DeviceItem,
        selfId: Long,
        onChanged: () -> Unit,
    ) where T : Activity, T : LifecycleOwner {
        val name = device.label.ifBlank { activity.getString(R.string.mayak_devices_unnamed) }
        val msg = if (device.id == selfId) R.string.mayak_devices_revoke_msg_self else R.string.mayak_devices_revoke_msg
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.mayak_devices_revoke_title, name))
            .setMessage(msg)
            .setPositiveButton(R.string.mayak_devices_revoke) { _, _ ->
                activity.lifecycleScope.launch {
                    try {
                        session.revokeDevice(backend, device.id)
                    } catch (e: Exception) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.mayak_devices_revoke_err, human(activity, e)),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    Toast.makeText(activity, R.string.mayak_devices_revoked, Toast.LENGTH_LONG).show()
                    onChanged()
                }
            }
            .setNegativeButton(R.string.mayak_cancel, null)
            .show()
    }

    /** Текст ошибки для человека: ядро прислало причину — показываем её; сетевой отказ называем
     *  словами (нет интернета / сервер молчит), а не именем Java-класса, как было до 2026-08-07:
     *  «Не удалось получить список: ни один домен ядра недоступен (2): …» человеку не говорит ничего. */
    private fun human(ctx: android.content.Context, e: Exception): String = when (e) {
        is MayakApiException -> e.message ?: "HTTP ${e.status}"
        is java.io.IOException -> ctx.getString(
            if (MayakNet.hasNetwork(ctx)) R.string.mayak_err_server_unreachable
            else R.string.mayak_status_no_network
        )
        else -> ctx.getString(R.string.mayak_err_generic)
    }
}
