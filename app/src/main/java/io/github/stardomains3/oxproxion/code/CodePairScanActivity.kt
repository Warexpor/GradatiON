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
import io.github.stardomains3.oxproxion.AppToast
import io.github.stardomains3.oxproxion.R

/**
 * Thin QR scanner for Code pairing. No network — returns / queues a parsed
 * [CodePairing.Result] via [CodePairPending]. Camera denied → toast and finish
 * so the caller can fall back to manual entry.
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
            is CodePairing.ParseResult.Err -> {
                AppToast.makeText(
                    this,
                    getString(errorString(parsed.reason)),
                    AppToast.LENGTH_LONG
                ).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner() else {
            AppToast.makeText(this, getString(R.string.code_pair_camera_denied), AppToast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> launchScanner()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
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
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, CodePairScanActivity::class.java)
    }
}
