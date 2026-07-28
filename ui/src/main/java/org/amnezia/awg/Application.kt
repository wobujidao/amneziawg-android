/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.os.SystemClock
import org.amnezia.awg.mayak.GoTunnel
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.android.material.color.DynamicColors
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.AwgQuickBackend
import org.amnezia.awg.configStore.FileConfigStore
import org.amnezia.awg.model.TunnelManager
import org.amnezia.awg.util.NetworkState
import org.amnezia.awg.util.NetworkType
import org.amnezia.awg.util.RootShell
import org.amnezia.awg.util.ToolsInstaller
import org.amnezia.awg.util.UserKnobs
import org.amnezia.awg.util.applicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import java.util.Locale

class Application : android.app.Application() {
    private val futureBackend = CompletableDeferred<Backend>()
    private val coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private lateinit var rootShell: RootShell
    private lateinit var preferencesDataStore: DataStore<Preferences>
    private lateinit var toolsInstaller: ToolsInstaller
    private lateinit var tunnelManager: TunnelManager
    private lateinit var networkState: NetworkState

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)
        if (BuildConfig.MIN_SDK_VERSION > Build.VERSION.SDK_INT) {
            @Suppress("UnsafeImplicitIntentLaunch")
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            System.exit(0)
        }
    }

    private suspend fun determineBackend(): Backend {
        var backend: Backend? = null
        if (UserKnobs.enableKernelModule.first() && AwgQuickBackend.hasKernelSupport()) {
            try {
                rootShell.start()
                val awgQuickBackend = AwgQuickBackend(applicationContext, rootShell, toolsInstaller)
                awgQuickBackend.setMultipleTunnels(UserKnobs.multipleTunnels.first())
                backend = awgQuickBackend
                UserKnobs.multipleTunnels.onEach {
                    awgQuickBackend.setMultipleTunnels(it)
                }.launchIn(coroutineScope)
            } catch (ignored: Exception) {
            }
        }
        if (backend == null) {
            backend = GoBackend(applicationContext)
            // Always-On VPN: система стартует наш VpnService (в т.ч. на буте) → поднимаем туннель «Маяка».
            // Апстримный restoreState(true) для нас no-op (конфиги в /connect, не в FileConfigStore) — вместо
            // него F3-автоподключение из сохранённого на диске конфига (без сети). Гейт — MayakPrefs.autoConnect.
            GoBackend.setAlwaysOnCallback {
                get().applicationScope.launch {
                    org.amnezia.awg.mayak.MayakAutoConnect.bringUpIfEnabled(get())
                }
            }
        }
        return backend
    }

    override fun onCreate() {
        Log.i(TAG, USER_AGENT)
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        rootShell = RootShell(applicationContext)
        toolsInstaller = ToolsInstaller(applicationContext, rootShell)
        preferencesDataStore = PreferenceDataStoreFactory.create { applicationContext.preferencesDataStoreFile("settings") }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runBlocking {
                AppCompatDelegate.setDefaultNightMode(if (UserKnobs.darkTheme.first()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            UserKnobs.darkTheme.onEach {
                val newMode = if (it) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                if (AppCompatDelegate.getDefaultNightMode() != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                }
            }.launchIn(coroutineScope)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        tunnelManager = TunnelManager(FileConfigStore(applicationContext))
        tunnelManager.onCreate()

        // Initialize network state monitor for auto-reconnection
        networkState = NetworkState(applicationContext) { oldType, newType ->
            Log.i(TAG, "NetworkState callback: Network changed: $oldType -> $newType")
            onNetworkChange(oldType, newType)
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                backend = determineBackend()
                futureBackend.complete(backend!!)
                networkState.bindNetworkListener()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(VmPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }

        // Тихий еженедельный телеметри-бикон (не-ПДн: версия/модель/ОС/локаль/источник + счётчики).
        // Идемпотентно (ExistingPeriodicWorkPolicy.KEEP) → безопасно на каждом старте. Без UI/эффекта;
        // если не вошёл или сбой — тихо no-op. См. MayakTelemetryWorker / TELEMETRY-DISCLOSURE.md.
        runCatching { org.amnezia.awg.mayak.MayakTelemetryWorker.enqueue(this) }
    }

    override fun onTerminate() {
        networkState.unbindNetworkListener()
        coroutineScope.cancel()
        super.onTerminate()
    }

    /**
     * Called when network changes (e.g., WiFi to Mobile or vice versa).
     * Reconnects active tunnels to ensure VPN connection works on new network.
     */
    // Когда в последний раз переподнимали туннель по смене сети (SystemClock) + минимальный
    // промежуток. События смены сети приходят пачкой, а дёргать туннель на каждое — вредно.
    private var lastRebindAt = 0L

    private fun onNetworkChange(oldType: NetworkType, newType: NetworkType) {
        Log.i(TAG, "onNetworkChange called: $oldType -> $newType")
        
        if (newType == NetworkType.NONE) {
            Log.i(TAG, "Network lost, waiting for new connection...")
            return
        }

        // ИСТОРИЯ, без неё этот код читается неправильно.
        //
        // Апстримный обработчик (PR #53) на КАЖДУЮ смену сети дёргал TunnelManager DOWN→UP. У нас конфиг
        // приходит из /connect и живёт в MayakActivity/GoTunnel, а НЕ в FileConfigStore — поэтому DOWN
        // срабатывал, а UP не мог случиться в принципе: на хендовере соты VPN отваливался навсегда
        // (баг владельца 2026-07-06). Его отключили с формулировкой «WireGuard роумит сам».
        //
        // Диаг-лог #66 (28-07) показал, что «сам» — только про МЯГКИЙ хендовер. Живой случай «зашёл в
        // лифт»: сеть пропала (onLost), появилась ДРУГАЯ (новый id), и дальше девять рукопожатий за 45
        // секунд без единого ответа — сокет остался на умершей сети. Помог только ручной переподключение.
        // Для человека это выглядит как «интернета нет», хотя приложение показывает «подключено».
        //
        // Поэтому переподнимаем — но НЕ старым способом: конфиг теперь лежит рядом с туннелем
        // (GoTunnel.lastConfText), переподъём идёт без сети и без похода в ядро, и работает в фоне.
        // Если наш туннель не поднят, rebindAfterNetworkChange() сам ничего не делает.
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebindAt < REBIND_DEBOUNCE_MS) {
            // Смена сети приходит пачкой (validated=false → validated=true и т.п.). Дёргать туннель на
            // каждое событие — вернуть себе тот же баг 06-07, только другим путём.
            Log.i(TAG, "Network change ($oldType -> $newType): переподъём пропущен, был ${now - lastRebindAt} мс назад")
            return
        }
        lastRebindAt = now
        val changedAtEpoch = System.currentTimeMillis()
        coroutineScope.launch(Dispatchers.IO) {
            // Фора движку: сначала даём ему самому переехать на новую сеть. Если получилось —
            // рукопожатие обновится, и переподъём не понадобится. Только если за это время связи с
            // сервером так и нет, лечим руками. В логе владельца #66 движок за 45 секунд не справился.
            kotlinx.coroutines.delay(REBIND_GRACE_MS)
            runCatching { GoTunnel.rebindAfterNetworkChange(changedAtEpoch) }
                .onSuccess { if (it) Log.i(TAG, "Network change ($oldType -> $newType): туннель переподнят") }
                .onFailure { Log.w(TAG, "Network change: переподъём не удался: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        private const val REBIND_DEBOUNCE_MS = 3_000L

        // Сколько ждём, прежде чем чинить туннель руками. Столько движку хватает на пару попыток
        // рукопожатия (он повторяет раз в ~5 с) — если за это время сервер не ответил, сам он уже
        // не оживёт, а человек в это время смотрит на «подключено» без интернета.
        private const val REBIND_GRACE_MS = 10_000L
        val USER_AGENT = String.format(Locale.ENGLISH, "AmneziaWG/%s (Android %d; %s; %s; %s %s; %s)", BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown ABI", Build.BOARD, Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT)
        private const val TAG = "AmneziaWG/Application"
        private lateinit var weakSelf: WeakReference<Application>

        fun get(): Application {
            return weakSelf.get()!!
        }

        suspend fun getBackend() = get().futureBackend.await()

        fun getRootShell() = get().rootShell

        fun getPreferencesDataStore() = get().preferencesDataStore

        fun getToolsInstaller() = get().toolsInstaller

        fun getTunnelManager() = get().tunnelManager

        fun getCoroutineScope() = get().coroutineScope

        fun getNetworkState() = get().networkState
    }

    init {
        weakSelf = WeakReference(this)
    }
}
