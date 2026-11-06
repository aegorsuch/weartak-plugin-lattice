// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.weartak.plugin.lattice.core.ConnectionConfig
import java.io.IOException
import java.net.URISyntaxException
import java.security.GeneralSecurityException

/** Native WearTAK DayNight settings/list; no background reception or host transport calls. */
class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var credentials: CredentialStore? = null
    private lateinit var status: TextView
    private lateinit var tracks: TextView
    private lateinit var endpoint: EditText
    private lateinit var token: EditText
    private lateinit var sandbox: EditText
    private lateinit var ca: EditText
    private var saved: SavedConfiguration? = null
    private var notice: Int? = null
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = if (resources.configuration.isScreenRound) 34 else 20
            setPadding(dp(inset), dp(28), dp(inset), dp(36))
        }
        fun text(id: Int) = TextView(this).apply {
            setText(id)
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setPadding(0, dp(8), 0, dp(8))
            content.addView(this)
        }
        text(R.string.app_name).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            isAccessibilityHeading = true
        }
        text(R.string.read_only_notice)
        status = text(R.string.host_inactive).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        fun button(id: Int, action: () -> Unit) {
            content.addView(Button(this).apply {
                setText(id); minHeight = dp(48)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        button(R.string.connect) { connect() }
        button(R.string.disconnect) { notice = null; LatticeSession.stop(); render() }
        tracks = text(R.string.track_empty)
        text(R.string.scope_notice)
        fun field(label: Int, limit: Int, secret: Boolean): EditText {
            val title = text(label)
            val input = createInput(limit, secret)
            title.labelFor = input.id
            content.addView(input)
            return input
        }
        endpoint = field(R.string.endpoint, 2048, false).apply { setHint(R.string.endpoint_hint) }
        token = field(R.string.token, 8192, true)
        sandbox = field(R.string.sandbox_token, 8192, true)
        ca = field(R.string.ca, CredentialStore.MAX_CA_BYTES, false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            maxLines = 4
            setHint(R.string.ca_hint)
        }
        button(R.string.save) {
            LatticeSession.stop()
            protectConfiguration(R.string.save_failed) {
                val value = SavedConfiguration(ConnectionConfig(endpoint.text.toString().trim(), token.text.toString(), sandbox.text.toString()), ca.text.toString())
                CredentialStore.tls(value.caPem)
                requireNotNull(credentials).save(value)
                saved = value
                token.text.clear(); sandbox.text.clear(); ca.text.clear()
                showNotice(R.string.saved)
            }
        }
        button(R.string.forget) {
            LatticeSession.stop()
            protectConfiguration(R.string.save_failed) {
                requireNotNull(credentials).forget()
                saved = null
                endpoint.text.clear(); token.text.clear(); sandbox.text.clear(); ca.text.clear()
                showNotice(R.string.forgotten)
            }
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
            addView(content)
        })
        protectConfiguration(R.string.load_failed) {
            credentials = CredentialStore(this)
            saved = requireNotNull(credentials).load()
            saved?.let { endpoint.setText(it.connection.origin.toASCIIString()) }
        }
    }

    // Deployment bearer tokens, not account passwords; no phone provisioning contract exists.
    @SuppressLint("WearPasswordInput")
    private fun createInput(limit: Int, secret: Boolean) = EditText(this).apply {
        id = View.generateViewId()
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        filters = arrayOf(InputFilter.LengthFilter(limit))
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setAutofillHints(null)
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        minimumHeight = dp(48)
        setSingleLine(true)
    }

    private fun connect() {
        val value = saved
        if (value == null) { showNotice(R.string.missing_configuration); return }
        notice = null
        protectConfiguration(R.string.save_failed) {
            LatticeSession.start(value.connection, CredentialStore.tls(value.caPem))
            render()
        }
    }
    private fun protectConfiguration(failure: Int, action: () -> Unit) {
        try {
            action()
        } catch (_: GeneralSecurityException) { showNotice(failure)
        } catch (_: IOException) { showNotice(failure)
        } catch (_: IllegalArgumentException) { showNotice(failure)
        } catch (_: IllegalStateException) { showNotice(failure)
        } catch (_: URISyntaxException) { showNotice(failure) }
    }
    private fun showNotice(id: Int) { notice = id; status.setText(id) }
    override fun onStart() {
        super.onStart()
        LatticeSession.foreground(true)
        main.post(refresh)
    }
    override fun onStop() {
        main.removeCallbacks(refresh)
        LatticeSession.foreground(false)
        token.text.clear(); sandbox.text.clear(); ca.text.clear()
        super.onStop()
    }
    override fun onDestroy() { saved = null; super.onDestroy() }
    private fun render() {
        val state = LatticeSession.snapshot()
        val statusText = notice?.let(::getString) ?: (when (state.message) {
            R.string.receiving -> getString(state.message, state.tracks.size)
            R.string.http_failed -> getString(state.message, state.httpStatus)
            else -> getString(state.message)
        } + if (state.ignored > 0) "\n${getString(R.string.ignored, state.ignored)}" else "")
        if (status.text.toString() != statusText) status.text = statusText
        val trackText = if (state.tracks.isEmpty()) getString(R.string.track_empty) else state.tracks.joinToString("\n\n") {
            val name = if (it.simulated) getString(R.string.simulated, it.name) else it.name
            getString(R.string.track_detail, name, it.affiliation, it.environment, it.platform,
                it.latitude, it.longitude, it.observed.toString(), it.expires.toString(), it.id)
        }
        if (tracks.text.toString() != trackText) tracks.text = trackText
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
