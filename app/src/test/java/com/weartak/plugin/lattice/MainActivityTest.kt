// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {
    @Test fun nativeScreenUsesSecureWindowAndDoesNotSaveCredentialFieldState() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val scroll = root.getChildAt(0) as ScrollView
            val content = scroll.getChildAt(0) as LinearLayout
            val fields = (0 until content.childCount).map(content::getChildAt).filterIsInstance<EditText>()
            assertEquals(4, fields.size)
            assertTrue(fields.all { !it.isSaveEnabled })
            assertTrue(fields.all { it.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt() })
            assertFalse(LatticeSession.snapshot().receiving)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun invalidPrivateCaNeverBecomesTrustAll() {
        assertNull(CredentialStore.tls(""))
        assertThrows(java.security.cert.CertificateException::class.java) { CredentialStore.tls("not a certificate") }
        assertThrows(IllegalArgumentException::class.java) { CredentialStore.tls("x".repeat(CredentialStore.MAX_CA_BYTES + 1)) }
    }
}
