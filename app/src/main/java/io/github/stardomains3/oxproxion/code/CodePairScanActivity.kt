package io.github.stardomains3.oxproxion.code

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.stardomains3.oxproxion.R

/**
 * Thin QR scanner for Code pairing. No network — returns / queues a parsed
 * [CodePairing.Result] via [CodePairPending]. A bad QR or a denied camera finishes with
 * RESULT_CANCELED and the reason in [EXTRA_ERROR]; the same text is queued on
 * [CodePairPending.error] because the dialog starts this screen without a result callback and
 * [CodeModeHost] shows it once the chat screen is back. The caller can fall back to manual entry.
 */
class CodePairScanActivity : AppCompatActivity() {

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            finish()
            return@registerForActivityResult
        }
        when (val parsed = CodePairing.parse(contents)) {
            is CodePairing.ParseResult.Ok -> {
                CodePairPending.offer(parsed.pairing)
                setResult(RESULT_OK)
                finish()
            }
            is CodePairing.ParseResult.Err -> fail(getString(errorString(parsed.reason)))
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner() else fail(getString(R.string.code_pair_camera_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> launchScanner()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun fail(message: String) {
        CodePairPending.offerError(message)
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.code_pair_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    private fun errorString(reason: CodePairing.Reason): Int = when (reason) {
        CodePairing.Reason.NOT_PAIR_URI -> R.string.code_pair_bad_qr
        CodePairing.Reason.MISSING_URL -> R.string.code_pair_missing_url
        CodePairing.Reason.MISSING_TOKEN -> R.string.code_pair_missing_token
        CodePairing.Reason.BAD_URL -> R.string.code_host_bad_url
        CodePairing.Reason.BAD_FINGERPRINT -> R.string.code_pair_bad_fingerprint
        CodePairing.Reason.PIN_REQUIRES_WSS -> R.string.code_pair_pin_requires_wss
    }

    companion object {
        const val EXTRA_ERROR = "code_pair_error"

        fun intent(context: Context): Intent = Intent(context, CodePairScanActivity::class.java)
    }
}
