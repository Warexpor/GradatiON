package io.github.stardomains3.oxproxion

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AutosendActivity : AppCompatActivity() {

    private var pendingSharedText: String? = null
    private var biometricUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_autosend)

        if (savedInstanceState == null) {
            BiometricGateHelper.gateIfNeeded(this) {
                biometricUnlocked = true
                handleIntent(intent)
            }
        } else {
            biometricUnlocked = true
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                pendingSharedText = sharedText
                showConfirmationDialog(sharedText)
                return
            }
        }
        finish()
    }

    private fun showConfirmationDialog(sharedText: String) {
        val preview = sharedText.take(200).let { text ->
            if (sharedText.length > 200) "$text…" else text
        }
        GlassAlertDialogBuilder(this)
            .setTitle("Auto Send")
            .setMessage("Send this text to GradatiON now?\n\n$preview")
            .setPositiveButton("Send") { _, _ -> forwardToMain(sharedText) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun forwardToMain(sharedText: String) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("autosend", true)
            putExtra("shared_text", sharedText)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (biometricUnlocked) {
            handleIntent(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && pendingSharedText == null) {
            finish()
        }
    }
}
