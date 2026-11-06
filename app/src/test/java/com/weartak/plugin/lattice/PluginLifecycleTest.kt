// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import android.content.Intent
import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.SettingsApplyMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginLifecycleTest {
    private fun control(binder: Binder = Binder()): IWearTakPluginControl {
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IWearTakPluginControl::class.java)) { _, method, _ ->
            if (method.name == "asBinder") binder else throw AssertionError("Unexpected host call: ${method.name}")
        } as IWearTakPluginControl
    }

    private class DeathBinder : Binder() {
        lateinit var recipient: IBinder.DeathRecipient
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { this.recipient = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
    }

    @Test fun delayedDeathOfReplacedHostCannotRevokeCurrentBinding() {
        val controller = Robolectric.buildService(LatticePluginService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            val init = PluginInitParcel(service.packageName, Binder.getCallingUid(), LatticePluginService.PLUGIN_ID)
            val old = DeathBinder()
            val replacement = DeathBinder()
            service.plugin.initializePluginV2(init, control(old), null)
            service.plugin.initializePluginV2(init, control(replacement), null)
            service.plugin.startPlugin()
            old.recipient.binderDied()
            assertEquals(PluginLifecycleState.RUNNING, service.plugin.getStatus().state)
            replacement.recipient.binderDied()
            assertEquals(PluginLifecycleState.UNBOUND, service.plugin.getStatus().state)
            assertThrows(IllegalStateException::class.java) { service.plugin.startPlugin() }
        } finally { controller.destroy() }
    }

    @Test fun failedReplacementDeathRegistrationCannotReuseOldHostAuthorization() {
        val controller = Robolectric.buildService(LatticePluginService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            val init = PluginInitParcel(service.packageName, Binder.getCallingUid(), LatticePluginService.PLUGIN_ID)
            service.plugin.initializePluginV2(init, control(), null)
            service.plugin.startPlugin()
            val dead = object : Binder() {
                override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
                    throw RemoteException("Synthetic dead host")
                }
            }
            assertThrows(RemoteException::class.java) {
                service.plugin.initializePluginV2(init, control(dead), null)
            }
            assertThrows(IllegalStateException::class.java) { service.plugin.startPlugin() }
            assertFalse(LatticeSession.snapshot().receiving)
            service.plugin.initializePluginV2(init, control(), null)
            service.plugin.startPlugin()
            assertEquals(PluginLifecycleState.RUNNING, service.plugin.getStatus().state)
        } finally { controller.destroy() }
    }

    @Test fun bindingAndCapabilitiesExposeNoOutboundOrMapPaths() {
        val controller = Robolectric.buildService(LatticePluginService::class.java).create()
        try {
            val service = controller.get()
            assertNull(service.onBind(Intent("wrong")))
            assertNotNull(service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE)))
            val capabilities = service.plugin.getCapabilities()
            assertTrue(capabilities.externalMainActivity)
            assertFalse(capabilities.hostedMainUi)
            assertFalse(capabilities.hostedSettingsUi)
            assertFalse(capabilities.cotReadyEvents)
            assertFalse(capabilities.mapLayers)
            assertFalse(capabilities.deviceIntegration)
            assertTrue(service.plugin.getSettingsValues().isEmpty)
            assertTrue(service.plugin.getMainUiState().isEmpty)
        } finally { controller.destroy() }
    }

    @Test fun listingSubtitleIsExactAndPluginDescriptionKeepsOperationalScope() {
        val controller = Robolectric.buildService(LatticePluginService::class.java).create()
        try {
            val service = controller.get()
            val component = ComponentName(service, LatticePluginService::class.java)
            val info = service.packageManager.getServiceInfo(
                component,
                android.content.pm.PackageManager.ComponentInfoFlags.of(
                    android.content.pm.PackageManager.GET_META_DATA.toLong(),
                ),
            )
            assertEquals(
                "Unfunded Request",
                info.metaData?.getString("com.weartak.plugin.MENU_SUBTITLE"),
            )
            assertEquals(
                "Read-only Lattice point tracks. Foreground only; no map or outbound reporting.",
                service.plugin.getPluginInfo().description,
            )
        } finally { controller.destroy() }
    }

    @Test fun initializedHostCanStartStopAndShutdownButCannotRestartOldBinder() {
        val controller = Robolectric.buildService(LatticePluginService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            val plugin = service.plugin
            assertThrows(IllegalStateException::class.java) { plugin.startPlugin() }
            plugin.initializePluginV2(PluginInitParcel(service.packageName, Binder.getCallingUid(), LatticePluginService.PLUGIN_ID), control(), null)
            assertEquals(PluginLifecycleState.READY, plugin.getStatus().state)
            plugin.startPlugin()
            assertEquals(PluginLifecycleState.RUNNING, plugin.getStatus().state)
            assertFalse(LatticeSession.snapshot().receiving)
            plugin.stopPlugin()
            assertEquals(PluginLifecycleState.STOPPED, plugin.getStatus().state)
            plugin.shutdownPlugin()
            assertEquals(PluginLifecycleState.SHUTDOWN, plugin.getStatus().state)
            assertThrows(IllegalStateException::class.java) { plugin.startPlugin() }
        } finally { controller.destroy() }
    }

    @Test fun mismatchedCallerPluginAndPackageAreRejectedBeforeNetworking() {
        val controller = Robolectric.buildService(LatticePluginService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            for (init in listOf(
                PluginInitParcel(service.packageName, Binder.getCallingUid() + 1, LatticePluginService.PLUGIN_ID),
                PluginInitParcel(service.packageName, Binder.getCallingUid(), "wrong"),
                PluginInitParcel("unknown.host", Binder.getCallingUid(), LatticePluginService.PLUGIN_ID),
            )) {
                assertThrows(IllegalArgumentException::class.java) { service.plugin.initializePluginV2(init, control(), null) }
            }
            assertEquals(PluginLifecycleState.BOUND, service.plugin.getStatus().state)
            assertFalse(LatticeSession.snapshot().receiving)
        } finally { controller.destroy() }
    }

    @Test fun hostSettingsAndActionsCannotSupplySecretsOrCommands() {
        val controller = Robolectric.buildService(LatticePluginService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            service.plugin.initializePluginV2(PluginInitParcel(service.packageName, Binder.getCallingUid(), LatticePluginService.PLUGIN_ID), control(), null)
            val result = service.plugin.applySettingsWithResult(Bundle().apply { putString("token", "synthetic") }, SettingsApplyMode.PARTIAL_PATCH)
            assertFalse(result.success)
            assertTrue(result.values.isEmpty)
            assertThrows(IllegalArgumentException::class.java) { service.plugin.invokeMainUiAction("publish", Bundle()) }
        } finally { controller.destroy() }
    }
}
