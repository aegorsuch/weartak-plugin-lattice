// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.*
import com.weartak.plugin.sdk.lifecycle.*
import com.weartak.plugin.sdk.model.*
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.PluginMetadataValidator
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3

class LatticePluginService : Service() {
    private val lifecycle = PluginLifecycleMachine()
    private var hostUid: Int? = null
    private var callbackUid: Int? = null
    private var callback: IWearTakHostCallback? = null
    private var hostBinder: IBinder? = null
    private var hostDeath: IBinder.DeathRecipient? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != WearTakPluginContract.ACTION_PLUGIN_SERVICE) return null
        transition(PluginLifecycleEvent.BIND_ATTACHED)
        return plugin
    }
    override fun onUnbind(intent: Intent?): Boolean { synchronized(this) { detach() }; return false }
    override fun onDestroy() { synchronized(this) { detach() }; super.onDestroy() }

    internal val plugin = object : IWearTakPlugin.Stub() {
        override fun getPluginInfo() = PluginInfoParcel(
            PLUGIN_ID, getString(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID,
            getString(R.string.plugin_description),
        )
        override fun getCapabilities() = PluginCapabilitiesParcel(externalMainActivity = true)
        override fun getApiVersion() = WearTakPluginContract.CONTRACT_API_VERSION
        override fun getStatus(): PluginStatusParcel {
            val state = LatticeSession.snapshot()
            val message = when (state.message) {
                R.string.receiving -> getString(state.message, state.tracks.size)
                R.string.http_failed -> getString(state.message, state.httpStatus)
                else -> getString(state.message)
            }
            return PluginStatusParcel(lifecycle.snapshot(), message)
        }
        override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) =
            initializePluginV2(init, control, null)
        override fun initializePluginV3(
            init: PluginInitParcel?, control: IWearTakPluginControl?,
            controlV2: IWearTakPluginHostControlV2?, controlV3: IWearTakPluginHostControlV3?,
        ) = initializePluginV2(init, control, controlV2)

        override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?, controlV2: IWearTakPluginHostControlV2?) {
            synchronized(this@LatticePluginService) {
                requireNotNull(init) { "Missing host identity." }
                requireNotNull(control) { "Missing host control." }
                require(PluginMetadataValidator.validateInitAgainstPlugin(init, PLUGIN_ID) is ContractValidationResult.Ok)
                val caller = Binder.getCallingUid()
                require(caller == init.hostUid &&
                    packageManager.getPackagesForUid(caller)?.contains(init.hostPackageName) == true) { "Invalid host identity." }
                check(!lifecycle.isShutdown() && (hostUid == null || hostUid == caller)) { "Invalid binding." }
                require(callbackUid == null || callbackUid == caller) { "Callback identity mismatch." }
                LatticeSession.hostStopped()
                unlinkHost()
                hostUid = null
                val binder = control.asBinder()
                val death = object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        synchronized(this@LatticePluginService) {
                            if (hostDeath === this) detach()
                        }
                    }
                }
                binder.linkToDeath(death, 0)
                hostBinder = binder
                hostDeath = death
                hostUid = caller
                transition(PluginLifecycleEvent.INITIALIZED)
            }
        }
        override fun startPlugin() {
            synchronized(this@LatticePluginService) {
                verifyHost()
                transition(PluginLifecycleEvent.HOST_STARTED)
                if (hostUid != null) LatticeSession.hostStarted()
            }
        }
        override fun stopPlugin() {
            synchronized(this@LatticePluginService) {
                verifyHost()
                LatticeSession.hostStopped()
                transition(PluginLifecycleEvent.HOST_STOPPED)
            }
        }
        override fun shutdownPlugin() {
            synchronized(this@LatticePluginService) {
                verifyHost()
                LatticeSession.hostStopped()
                transition(PluginLifecycleEvent.HOST_SHUTDOWN)
                detach(resetLifecycle = false)
            }
        }
        override fun registerHostCallback(value: IWearTakHostCallback?) {
            synchronized(this@LatticePluginService) {
                check(!lifecycle.isShutdown())
                val caller = Binder.getCallingUid()
                check(hostUid == null || hostUid == caller)
                check(callbackUid == null || callbackUid == caller)
                callback = requireNotNull(value)
                callbackUid = caller
                transition(PluginLifecycleEvent.HOST_CALLBACK_REGISTERED)
            }
        }
        override fun unregisterHostCallback(value: IWearTakHostCallback?) {
            synchronized(this@LatticePluginService) {
                verifyHost()
                if (callback?.asBinder() == value?.asBinder()) {
                    callback = null
                    callbackUid = null
                    LatticeSession.hostStopped()
                }
            }
        }
        override fun getSettingsSchema() = SettingsSchemaParcel(title = getString(R.string.app_name))
        override fun getSettingsValues() = Bundle()
        override fun applySettings(values: Bundle?) { require(applySettingsWithResult(values, SettingsApplyMode.PARTIAL_PATCH).success) }
        override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
            synchronized(this@LatticePluginService) { verifyHost() }
            val valid = values?.isEmpty != false && applyMode in setOf(
                SettingsApplyMode.PARTIAL_PATCH, SettingsApplyMode.FULL_REPLACEMENT, SettingsApplyMode.HOST_DRIVEN_RECOMPUTE,
            )
            return SettingsApplyResultParcel(
                success = valid, applyMode = applyMode,
                errors = if (valid) emptyList() else listOf(SettingsValidationErrorParcel("", "UNSUPPORTED", "Configure credentials only in the native plugin screen.")),
            )
        }
        override fun onHostSettingsChanged(settings: HostSettingsParcel?) { synchronized(this@LatticePluginService) { verifyHost() } }
        override fun getMainUiSchema() = PluginMainUiSchemaParcel()
        override fun getMainUiState() = Bundle()
        override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
            synchronized(this@LatticePluginService) { verifyHost() }
            throw IllegalArgumentException("No hosted actions supported.")
        }
    }

    private fun verifyHost() {
        check(hostUid == Binder.getCallingUid() && !lifecycle.isShutdown()) { "Host not initialized." }
    }
    private fun transition(event: PluginLifecycleEvent) {
        check(lifecycle.dispatch(event) !is PluginLifecycleTransition.Rejected) { "Invalid lifecycle transition." }
        try {
            callback?.onPluginStatusChanged(plugin.getStatus())
        } catch (_: RemoteException) {
            Log.w("LatticePlugin", "Host callback unavailable; reception stopped.")
            detach()
        }
    }
    private fun unlinkHost() {
        val binder = hostBinder
        val death = hostDeath
        hostBinder = null
        hostDeath = null
        if (binder != null && death != null) binder.unlinkToDeath(death, 0)
    }
    private fun detach(resetLifecycle: Boolean = true) {
        LatticeSession.hostStopped()
        unlinkHost()
        callback = null
        callbackUid = null
        hostUid = null
        if (resetLifecycle) lifecycle.dispatch(PluginLifecycleEvent.RESET)
    }
    companion object { const val PLUGIN_ID = "com.weartak.plugin.lattice" }
}
