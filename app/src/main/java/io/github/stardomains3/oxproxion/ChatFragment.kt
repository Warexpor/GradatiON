package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokPushOver
import io.github.stardomains3.oxproxion.Motion.setShownAnimated
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.util.Linkify
import android.util.Base64
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import coil.load
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.SpanFactory
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import io.noties.markwon.simple.ext.SimpleExtPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.ranges.contains
import kotlin.text.get
import kotlin.text.set

interface OnKeyboardShortcutListener {
    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean
}
class ChatFragment : Fragment(R.layout.fragment_chat), OnKeyboardShortcutListener, HistoryPanelHost {
   // private var isFontUpdate = false
    private var menuClosedByTouch = false
    private lateinit var speechLauncher: ActivityResultLauncher<Intent>
    private lateinit var textToSpeech: TextToSpeech

    /** Character whose wallpaper the photo picker is choosing (set just before launching it). */
    private var rpWallpaperFor: Long? = null
    private val pickRpWallpaper = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = rpWallpaperFor ?: return@registerForActivityResult
        rpWallpaperFor = null
        if (uri == null) return@registerForActivityResult
        BackgroundPhoto.import(requireContext(), uri, BackgroundPhoto.slotForCharacter(id)) { ok ->
            if (!ok) context?.let { GlassNotice.show(it, getString(R.string.toast_could_not_open_image)) }
        }
    }
    private var isSpeaking = false
    private var isShare = false
    private lateinit var chatFrameView: FrameLayout
    private var dimOverlay: View? = null
    private var historyDrawerContainer: FrameLayout? = null
    private var historyDrawerScrim: View? = null
    private var currentSpeakingPosition = -1
    private lateinit var settingsButton: MaterialButton
    private lateinit var homeButton: MaterialButton
    private lateinit var presetsButton: MaterialButton
    private lateinit var presetsButton2: MaterialButton
    private lateinit var attachmentButton: MaterialButton
    private lateinit var topSettingsButton: MaterialButton
    private lateinit var topPresetsButton: MaterialButton
    private lateinit var extendedTopBarContainer: LinearLayout
    private lateinit var topStreamButton: MaterialButton
    private lateinit var topReasoningButton: MaterialButton
    private lateinit var topWebSearchButton: MaterialButton
   // private lateinit var topConvoButton: MaterialButton
    private lateinit var topToolsButton: MaterialButton

    private var selectedImageBytes: ByteArray? = null
    private var selectedImageMime: String? = null
    private lateinit var audioPicker: ActivityResultLauncher<Array<String>>
    private var selectedAudioBytes: ByteArray? = null
    private var selectedAudioFormat: String? = null
    private var doVolScroll: Boolean = false
    private var fromWater: Boolean = false
    private lateinit var plusButton: MaterialButton
    private lateinit var btnDecreaseFont: MaterialButton
    private lateinit var btnIncreaseFont: MaterialButton
    private lateinit var btnDoneFont: MaterialButton
    private var ambientBackground: AmbientBackgroundView? = null
    private lateinit var genButton: MaterialButton
    private lateinit var saveMarkdownFileButton: MaterialButton
    private lateinit var saveEpubButton: MaterialButton
    private lateinit var saveHtmlButton: MaterialButton
    private lateinit var printButton: MaterialButton
    private var originalSendIcon: Drawable? = null
    private lateinit var webSearchButton: MaterialButton
    private lateinit var toolsButton: MaterialButton
    private val viewModel: ChatViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }
    private val askMode = AskModeController()
    private val rpMode = RpModeController()
    private lateinit var modelNameTextView: TextView
    private lateinit var modelNameShell: FrameLayout
    private lateinit var tabChat: TextView
    private lateinit var tabRoleplay: TextView
    private lateinit var modeTabIndicator: View
    private lateinit var codeMode: io.github.stardomains3.oxproxion.code.CodeModeHost
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatEditText: EditText
    private lateinit var extBG: LinearLayout
    private lateinit var fontSizeControlsContainer: LinearLayout
    private lateinit var sendChatButton: MaterialButton
    private lateinit var resetChatButton: MaterialButton
    private lateinit var utilityButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var speechButton: MaterialButton
    private var dictation: VoiceDictation? = null
    private lateinit var scrollToTopButton: MaterialButton
    private lateinit var scrollToBottomButton: MaterialButton
    private lateinit var convoButton: MaterialButton
    private lateinit var newChatButton: MaterialButton
    private lateinit var openSavedChatsButton: MaterialButton
    private lateinit var copyChatButton: MaterialButton
    private lateinit var buttonsRow2: LinearLayout
    private lateinit var chatInputContainer: LinearLayout
    private lateinit var rpComposerExtras: LinearLayout
    private lateinit var rpReminderButton: MaterialButton
    private lateinit var rpStreamButton: MaterialButton
    private lateinit var rpSwipeBar: LinearLayout
    private lateinit var rpSwipePrevButton: MaterialButton
    private lateinit var rpSwipeNextButton: MaterialButton
    private lateinit var rpSwipeCounter: TextView
    private lateinit var expandedButtonContainer: LinearLayout
    private lateinit var leftButtonContainer: LinearLayout
    private lateinit var rightButtonContainer: LinearLayout
    private lateinit var menuButton: MaterialButton
    private lateinit var controlsButton: MaterialButton
    private var modelChipColorAnimator: ValueAnimator? = null
    private lateinit var backcopyButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var progressBar: View
    private lateinit var pdfChatButton: MaterialButton
    private lateinit var systemMessageButton: MaterialButton
    private lateinit var topBarLayout: ConstraintLayout
    private lateinit var streamButton: MaterialButton
    private lateinit var reasoningButton: MaterialButton
    private var ttsAvailable = true
    private lateinit var dateFmt: SimpleDateFormat
    private lateinit var timeFmt: SimpleDateFormat
    private lateinit var datetimeFmt: SimpleDateFormat
    private lateinit var humanFmt: SimpleDateFormat
    private lateinit var fontsButton: MaterialButton
    private lateinit var fontSizeButton: MaterialButton
    private lateinit var buttonsContainer: LinearLayout
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var markwon: Markwon
    private lateinit var pdfGenerator: PdfGenerator
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private lateinit var attachmentPreviewContainer: View
    private lateinit var previewImageView: ImageView
    private lateinit var centerWatermarkIcon: ImageView
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var removeAttachmentButton: ImageButton
    private lateinit var headerContainer: LinearLayout
    private var overlayView: View? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private var currentCameraUri: Uri? = null
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var localNetworkPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPolicyLauncher: ActivityResultLauncher<Intent>
    private lateinit var galleryPicker: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var legacyGalleryPicker: ActivityResultLauncher<Array<String>>
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var pdfPicker: ActivityResultLauncher<Array<String>>
    private var currentTempImageFile: File? = null  // Tracks PDF PNG temp file
    data class AttachedFile(
        val fileName: String,
        val content: String,
        val size: Long
    )
    private var isScrollersEnabled = false     // 🔥 Cache → NO prefs/VM in onScroll
    private var isScrollProgressEnabled = false
    private var lastContentLength = 0
    private var mediaRecorder: MediaRecorder? = null
    private var voiceRecordFile: File? = null
    private var isRecording = false
    private lateinit var textFilePicker: ActivityResultLauncher<String>
    private val pendingFiles = mutableListOf<AttachedFile>()
    private val MAX_FILE_SIZE = 3 * 1024 * 1024 // 3MB total
    private val MAX_SINGLE_FILE_SIZE = 1024 * 1024 // 1MB per file
    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())
        speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // STT disabled
            /*
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!results.isNullOrEmpty()) {
                    val recognizedText = results[0]
                    chatEditText.setText(recognizedText)
                    chatEditText.setSelection(chatEditText.text.length)
                    if (sharedPreferencesHelper.getConversationModeEnabled()) {
                        sendChatButton.performClick()
                    }
                }
            }
            */
        }
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // STT disabled — mic permission was only used for voice input
            /*
            if (isGranted) {
                startSpeechRecognition()
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_mic_permission), AppToast.LENGTH_SHORT).show()
            }
            */
        }
        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCamera()
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_camera_permission), AppToast.LENGTH_SHORT).show()
            }
        }
        localNetworkPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                AppToast.makeText(requireContext(), getString(R.string.toast_lan_granted), AppToast.LENGTH_SHORT).show()
                // Optional: Auto-trigger send or model connection if you interrupted it
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_lan_permission), AppToast.LENGTH_LONG).show()
                // Optional: Revert model selection to a cloud model
            }
        }
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted) {
                AppToast.makeText(requireContext(), getString(R.string.toast_location_permission), AppToast.LENGTH_SHORT).show()
            }
        }

        notificationPolicyLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // You could re-check the permission here and update the UI if necessary,
            // but usually, the user just grants it in Settings and comes back.
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val imageUri = currentCameraUri ?:
            run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.data?.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
                }
            } ?: result.data?.data  // Fallbacks

            if (result.resultCode == Activity.RESULT_OK && imageUri != null) {
                if (discardAttachmentIfRp()) return@registerForActivityResult
                try {
                    // Read raw bytes first (fresh stream, one-time read)
                    val rawBytes = requireContext().contentResolver.openInputStream(imageUri)?.use { stream ->
                        stream.readBytes()
                    } ?: run {
                        GlassNotice.show(requireContext(), getString(R.string.toast_failed_read_image))
                        return@registerForActivityResult
                    }

                    if (rawBytes.size > 12_000_000) {
                        GlassNotice.show(requireContext(), getString(R.string.toast_image_too_large))
                        requireContext().contentResolver.delete(imageUri, null, null)
                        return@registerForActivityResult
                    }

                    selectedImageBytes = rawBytes  // Raw for send (EXIF intact)
                    selectedImageMime = "image/jpeg"
                    previewImageView.setImageURI(imageUri)  // Use Uri for preview (EXIF auto)
                    attachmentPreviewContainer.visibility = View.VISIBLE
                    AppToast.makeText(requireContext(), getString(R.string.toast_photo_saved), AppToast.LENGTH_SHORT).show()

                    // NEW: Set pending as string for FlexibleMessage (MediaStore Uri already persistent)
                    viewModel.setPendingUserImageUri(imageUri.toString())

                    // Notify for gallery refresh
                    requireContext().contentResolver.notifyChange(imageUri, null)
                } catch (e: Exception) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_failed_process_photo))
                    requireContext().contentResolver.delete(imageUri, null, null)
                }
            } else {
                // Cancel or error
                    AppToast.makeText(
                        requireContext(),
                        getString(
                            if (result.resultCode == Activity.RESULT_CANCELED) {
                                R.string.toast_capture_canceled
                            } else {
                                R.string.toast_capture_failed
                            }
                        ),
                        AppToast.LENGTH_SHORT
                    ).show()
                imageUri?.let { uri ->
                    requireContext().contentResolver.delete(uri, null, null)  // Clean up placeholder
                }
            }
            currentCameraUri = null  // Always reset after callback
        }

        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                // Take persistable permissions so it survives app restarts
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)

                // Save via your helper
                sharedPreferencesHelper.saveSafFolderUri(uri.toString())

                AppToast.makeText(requireContext(), getString(R.string.toast_folder_granted), AppToast.LENGTH_SHORT).show()
            }
        }
        audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { processAudioUri(it) }
        }
        galleryPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { processPickedImageUri(it) }
        }
        legacyGalleryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { processPickedImageUri(it) }
        }
        pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { processPdfUri(it) }  // Null-safe: Call if non-null
        }
        textFilePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                // Process each URI (with size/MIME checks via processTextFile)
                var successCount = 0
                var errorCount = 0
                uris.forEach { uri ->
                    processTextFile(uri)  // Your updated function—handles one at a time
                    // Note: Since processTextFile is async, we don't await here; toasts/UI update inside it
                    // For batch feedback, you could collect results, but simple loop + toasts work fine
                }
                // Optional: Single toast after all (but since async, use a counter or LiveData)

                //  AppToast.makeText(requireContext(), "${uris.size} files processed", AppToast.LENGTH_SHORT).show()
            }
        }
        // --- Initialize Views from fragment_chat.xml ---
        pdfChatButton = view.findViewById(R.id.pdfChatButton)
        systemMessageButton = view.findViewById(R.id.systemMessageButton)
        streamButton = view.findViewById(R.id.streamButton)
        reasoningButton = view.findViewById(R.id.reasoningButton)
        webSearchButton = view.findViewById(R.id.webSearchButton)
        toolsButton = view.findViewById(R.id.toolsButton)
        fontsButton = view.findViewById(R.id.fontsButton)
        fontSizeButton = view.findViewById(R.id.fontSizeButton)
        scrollToBottomButton = view.findViewById(R.id.scrollToBottomButton)
        scrollToTopButton = view.findViewById(R.id.scrollToTopButton)
        convoButton  = view.findViewById(R.id.convoButton)
        clearButton = view.findViewById(R.id.clearButton)
        utilityButton = view.findViewById(R.id.utilityButton)
        speechButton = view.findViewById(R.id.speechButton)
        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)
        chatEditText = view.findViewById(R.id.chatEditText)
        sendChatButton = view.findViewById(R.id.sendChatButton)
        extBG = view.findViewById(R.id.extBG)
        topBarLayout = view.findViewById(R.id.topBarLayout)
        fontSizeControlsContainer = view.findViewById(R.id.fontSizeControlsContainer)
        originalSendIcon = sendChatButton.icon
        resetChatButton = view.findViewById(R.id.resetChatButton)
        newChatButton = view.findViewById(R.id.newChatButton)
        openSavedChatsButton = view.findViewById(R.id.openSavedChatsButton)
        copyChatButton = view.findViewById(R.id.copyChatButton)
        saveMarkdownFileButton  = view.findViewById(R.id.saveMarkdownFileButton)
        saveEpubButton  = view.findViewById(R.id.saveEpubButton)
        saveHtmlButton  = view.findViewById(R.id.saveHtmlButton)
        printButton =   view.findViewById(R.id.printButton)
        buttonsRow2 = view.findViewById(R.id.buttonsRow2)
        chatInputContainer = view.findViewById(R.id.chatInputContainer)
        rpComposerExtras = view.findViewById(R.id.rpComposerExtras)
        rpReminderButton = view.findViewById(R.id.rpReminderButton)
        rpStreamButton = view.findViewById(R.id.rpStreamButton)
        rpSwipeBar = view.findViewById(R.id.rpSwipeBar)
        rpSwipePrevButton = view.findViewById(R.id.rpSwipePrevButton)
        rpSwipeNextButton = view.findViewById(R.id.rpSwipeNextButton)
        rpSwipeCounter = view.findViewById(R.id.rpSwipeCounter)
        rpSwipePrevButton.setOnClickListener { viewModel.swipeRpPrev() }
        rpSwipeNextButton.setOnClickListener { viewModel.swipeRpNext() }
        rpReminderButton.setOnClickListener { insertRpReminderTemplate() }
        rpStreamButton.setOnClickListener { streamButton.performClick() }
        viewModel.hasChatFork.observe(viewLifecycleOwner) {
            if (::chatAdapter.isInitialized) {
                chatAdapter.notifyDataSetChanged()
            }
        }
        expandedButtonContainer = view.findViewById(R.id.expandedButtonContainer)
        leftButtonContainer = view.findViewById(R.id.leftButtonContainer)
        rightButtonContainer = view.findViewById(R.id.rightButtonContainer)
        extendedTopBarContainer =  view.findViewById(R.id.extendedTopBarContainer)
        topReasoningButton = view.findViewById(R.id.topReasoningButton)
        topWebSearchButton = view.findViewById(R.id.topWebSearchButton)
        topStreamButton = view.findViewById(R.id.topStreamButton)
        //topConvoButton = view.findViewById(R.id.topConvoButton)
        topToolsButton = view.findViewById(R.id.topToolsButton)

        topPresetsButton = view.findViewById(R.id.topPresetsButton)
        topSettingsButton = view.findViewById(R.id.topSettingsButton)
        menuButton = view.findViewById(R.id.menuButton)
        controlsButton = view.findViewById(R.id.controlsButton)
        backcopyButton = view.findViewById(R.id.backcopyButton)
        homeButton = view.findViewById(R.id.homeButton)
        backButton = view.findViewById(R.id.backButton)
        progressBar = view.findViewById(R.id.progressBar)
        progressBar.pivotX = 0f
        chatFrameView = view.findViewById(R.id.chatFrameView)
        attachmentButton = view.findViewById(R.id.attachmentButton)
        buttonsContainer = view.findViewById(R.id.buttonsContainer)
        modelNameTextView = view.findViewById(R.id.modelNameTextView)
        modelNameShell = view.findViewById(R.id.modelNameShell)
        tabChat = view.findViewById(R.id.tabChat)
        tabRoleplay = view.findViewById(R.id.tabRoleplay)
        modeTabIndicator = view.findViewById(R.id.modeTabIndicator)
        attachmentPreviewContainer = view.findViewById(R.id.attachmentPreviewContainer)
        previewImageView = view.findViewById(R.id.previewImageView)
        centerWatermarkIcon = view.findViewById(R.id.centerWatermarkIcon)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        removeAttachmentButton = view.findViewById(R.id.removeAttachmentButton)
        headerContainer = view.findViewById(R.id.headerContainer)
        (headerContainer as? GlassLinearLayout)?.dragDismiss = DragDismiss(
            headerContainer,
            onProgress = { p -> dimOverlay?.alpha = 0.6f * (1f - p) },
            onDismiss = { hideMenu() }
        )
        setupQuickControls(view)
        settingsButton = view.findViewById(R.id.settingsButton)
        presetsButton = view.findViewById(R.id.presetsButton)
        presetsButton2 = view.findViewById(R.id.presetsButton2)
        arguments?.getString("shared_text")?.let { sharedText ->
            setSharedText(sharedText)
            arguments?.remove("shared_text") // To prevent re-processing
        }
        textToSpeech = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith("TTS_SAVE_") == true) {
                            val parts = utteranceId.split("_")
                            if (parts.size == 4 && parts[0] == "TTS" && parts[1] == "SAVE") {
                                val timestamp = parts[2].toLongOrNull() ?: return
                                val position = parts[3].toIntOrNull() ?: return
                                val context = requireContext()
                                val tempFile = File(context.cacheDir, "temp_tts_${timestamp}.wav")
                                val fileName = "TTS_${timestamp}_msg${position}.wav"

                                // 🎯 COROUTINES: IO → Main (Structured, Cancellable, No Thread Leaks!)
                                lifecycleScope.launch(Dispatchers.IO) {  // 🔧 BACKGROUND I/O
                                    var success = false
                                    try {
                                        // 🔍 File ready (onDone guarantees!)
                                        if (!tempFile.exists() || tempFile.length() == 0L) {
                                            throw Exception("TTS file empty (0 bytes)")
                                        }

                                        // 🎯 MediaStore Downloads (NO PERMISSIONS)
                                        val contentValues = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                                          //  put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, WorkspacePaths.mediaStoreRelativePath())
                                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                                        }

                                        val resolver = context.contentResolver
                                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                            ?: throw Exception("Failed to create MediaStore URI")

                                        resolver.openOutputStream(uri)?.use { outputStream ->
                                            tempFile.inputStream().use { inputStream ->
                                                inputStream.copyTo(outputStream)
                                            }
                                        } ?: throw Exception("Failed to open OutputStream")

                                        // ✅ Complete
                                        contentValues.clear()
                                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                        resolver.update(uri, contentValues, null, null)

                                        success = true

                                    } catch (e: Exception) {
                                    } finally {
                                        // 🧹 Cleanup
                                        tempFile.delete()
                                    }

                                    // 🎯 MAIN THREAD TOAST (Auto-switched!)
                                    withContext(Dispatchers.Main) {
                                        val message = if (success) {
                                            "✅ Saved to Downloads: $fileName"
                                        } else {
                                            "❌ Save failed"
                                        }
                                        AppToast.makeText(context, message, AppToast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        else {
                            requireActivity().runOnUiThread { onSpeechFinished() }  // Run on main thread
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId?.startsWith("TTS_SAVE_") == true) {
                            AppToast.makeText(requireContext(), getString(R.string.toast_tts_synthesis_error), AppToast.LENGTH_SHORT).show()
                        }
                        else {
                            requireActivity().runOnUiThread {
                                AppToast.makeText(
                                    requireContext(),
                                    getString(R.string.toast_tts_engine_error),
                                    AppToast.LENGTH_SHORT
                                ).show()
                                onSpeechFinished()
                            }
                        }
                    }
                })
                ttsAvailable = true
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_tts_failed), AppToast.LENGTH_SHORT).show()
                ttsAvailable = false
            }
        }
        val prism4j = Prism4j(ExampleGrammarLocator())
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val theme = if (isNightMode) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
        val syntaxHighlightPlugin = SyntaxHighlightPlugin.create(prism4j, theme)

        val tableBorder = ContextCompat.getColor(requireContext(), R.color.markwon_table_border)
        val tableHeaderBg = ContextCompat.getColor(requireContext(), R.color.markwon_table_header_bg)
        val customTableTheme = TableTheme.buildWithDefaults(requireContext())
            .tableBorderColor(tableBorder)
            .tableBorderWidth(1)
            .tableCellPadding((10 * resources.displayMetrics.density).toInt())
            .tableHeaderRowBackgroundColor(tableHeaderBg)
            .build()

        val codeTextColor = ContextCompat.getColor(requireContext(), R.color.markwon_code_text)
        val codeBgColor = ContextCompat.getColor(requireContext(), R.color.markwon_code_bg)
        val blockQuoteColor = ContextCompat.getColor(requireContext(), R.color.markwon_blockquote)
        val taskAccent = ContextCompat.getColor(requireContext(), R.color.xai_accent_sunset)
        val taskBg = ContextCompat.getColor(requireContext(), R.color.xai_canvas_card)

        markwon = Markwon.builder(requireContext())
            // ✅ TablePlugin EARLY with custom theme
            .usePlugin(TablePlugin.create(customTableTheme))

            // Core plugins next (HtmlPlugin omitted — raw HTML in model output is a XSS risk in TextViews)
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            // ✅ TaskList before syntax/images
            .usePlugin(TaskListPlugin.create(
                taskAccent,
                taskAccent,
                taskBg
            ))

            .usePlugin(syntaxHighlightPlugin)

            // ✅ Images AFTER table/syntax
            .usePlugin(CoilImagesPlugin.create(requireContext()))

            // Movement method LAST (after table/images)
           // .usePlugin(MovementMethodPlugin.create())
            .usePlugin(MovementMethodPlugin.create(LinkMovementMethod.getInstance()))


            // Custom plugins
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver(LinkResolverDef())
                }
            })
            .usePlugin(SimpleExtPlugin.create { plugin ->
                plugin.addExtension(2, '=', SpanFactory { _, _ ->
                    val typedValue = TypedValue()
                    requireContext().theme.resolveAttribute(
                        android.R.attr.textColorHighlight, typedValue, true
                    )
                    BackgroundColorSpan(typedValue.data)
                })
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(codeTextColor)
                        .codeBackgroundColor(codeBgColor)
                        .codeBlockBackgroundColor(codeBgColor)
                        .blockQuoteColor(blockQuoteColor)
                        .isLinkUnderlined(true)
                }
            })
            // Chat look: code cards, inline code pills, calmer headings (painted by ChatTextView).
            .usePlugin(ChatMarkdown.plugin(requireContext()))
            .build()
        // 🔥 Cache formatters (init once)
        dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        datetimeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        humanFmt = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        setupRecyclerView()
        setupEdgeToEdge(view)
        setupGlassChrome(view)
        // Compact glass controls keep their size; their hit areas grow to 44dp.
        TouchTargets.expand(
            view.findViewById(R.id.composerDock),
            menuButton, controlsButton, modelNameTextView, speechButton, sendChatButton,
            rpReminderButton, rpStreamButton, rpSwipePrevButton, rpSwipeNextButton
        )
        TouchTargets.expand(view.findViewById(R.id.extBG), scrollToTopButton, scrollToBottomButton, presetsButton2)
        TouchTargets.expand(removeAttachmentButton.parent as ViewGroup, removeAttachmentButton)

        // In onViewCreated(), after initializing chatEditText and before setupClickListeners()
        chatEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (headerContainer.isVisible && count > 0) { // Hide on first character input (touch on key)
                    hideMenu()
                    // Optional: chatEditText.removeTextChangedListener(this) // Remove after first hide
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {
                updateButtonVisibility()
                updateSendButtonChrome()
            }
        })
        updateSendButtonChrome()
        pdfGenerator = PdfGenerator(requireContext())
        plusButton = view.findViewById(R.id.plusButton)
        btnDecreaseFont = view.findViewById(R.id.btnDecreaseFont)
        btnIncreaseFont = view.findViewById(R.id.btnIncreaseFont)
        btnDoneFont = view.findViewById(R.id.btnDoneFont)
        ambientBackground = view.findViewById(R.id.ambientBackground)
        genButton = view.findViewById(R.id.genButton)
        setupClickListeners()
        setupPlusButtonListener()
        setupTextFilePicker()
        updateSystemMessageButtonState()
        updateInitialUI()
        val rootView = view as FrameLayout // The root FrameLayout (fragment_container)
        overlayView = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(requireContext().getColor(android.R.color.transparent))
            // This listener logic is now correct
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (headerContainer.isVisible && isTouchOutsideHeader(event.rawX, event.rawY) && !isTouchOnMenuButton(event.rawX, event.rawY)) {
                        hideMenu()
                        menuClosedByTouch = true
                        // Return false to pass the touch to the button underneath
                        return@setOnTouchListener false
                    }
                }
                // If touch is inside the header, let it pass through to the header
                false
            }
        }
        rootView.addView(overlayView)
        dimOverlay = view.findViewById<View>(R.id.dimOverlay)
        historyDrawerContainer = view.findViewById(R.id.historyDrawerContainer)
        historyDrawerScrim = view.findViewById(R.id.historyDrawerScrim)
        historyDrawerScrim?.setOnClickListener { closeHistoryPanel() }
        setupHistorySwipeGestures(view)
        setupWideSwipes(view)
        viewModel.activeChatModel.observe(viewLifecycleOwner) { model ->
            if (model != null) {
                if (!viewModel.isRpMode()) {
                    modelNameTextView.text = viewModel.getModelDisplayName(model)
                }
                updateQuickControls()
                if (model.contains("google/lyria", ignoreCase = true)) {
                    // Only toggle if it's currently OFF to avoid redundant toasts
                    if (viewModel.isStreamingEnabled.value == false) {
                        viewModel.toggleStreaming()
                        AppToast.makeText(requireContext(), getString(R.string.toast_streaming_music), AppToast.LENGTH_SHORT).show()
                    }
                }


                // Handle attachment button (plusButton) based on model capabilities
                applyModelCapabilityChrome(model)

                // Auto-disable web search if switching to LAN model
                if (viewModel.activeModelIsLan() && viewModel.isWebSearchEnabled.value == true) {
                    viewModel.toggleWebSearch()
                }

                // Clear staged image if model doesn't support vision
                if (selectedImageBytes != null && !viewModel.isVisionModel(model)) {
                    selectedImageBytes = null
                    selectedImageMime = null
                    attachmentPreviewContainer.visibility = View.GONE
                    AppToast.makeText(requireContext(), getString(R.string.toast_image_removed_no_vision), AppToast.LENGTH_SHORT).show()
                }
                // Clear staged audio if model doesn't support transcription
                if (selectedAudioBytes != null && !viewModel.isTranscriptionModel(model)) {
                    selectedAudioBytes = null
                    selectedAudioFormat = null
                    attachmentPreviewContainer.visibility = View.GONE
                    AppToast.makeText(requireContext(), getString(R.string.toast_audio_removed_no_transcription), AppToast.LENGTH_SHORT).show()
                }
            }

            // Update button visibility based on current model and preference
            updateExtendedTopBarVisibility(sharedPreferencesHelper.getExtendedTopBarEnabled())
            updateModelSourceIndicator()
        }

        var appliedComposerMode: ChatMode? = null
        viewModel.chatMode.observe(viewLifecycleOwner) { mode ->
            updateRpChrome()
            val next = mode ?: ChatMode.ASK
            ambientBackground?.mode = next
            // Only swap composer text on an actual Ask↔RP change. Re-emitting the same mode
            // (draft restore) must not wipe autosend / in-progress typing.
            if (appliedComposerMode != next) {
                appliedComposerMode = next
                val draft = sharedPreferencesHelper.getComposerDraft(next)
                chatEditText.setText(draft)
                if (draft.isNotEmpty()) chatEditText.setSelection(draft.length)
            }
        }
        viewModel.activeRpCharacter.observe(viewLifecycleOwner) { updateRpChrome() }
        viewModel.rpChromeRefreshEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { updateRpChrome() }
        }
        viewModel.rpSwipeNav.observe(viewLifecycleOwner) { nav ->
            applyRpSwipeChrome(nav)
        }
        // Code mode (third tab, on by default, Settings > Modes turns it off) lives in its own package; see CodeModeHost.
        codeMode = io.github.stardomains3.oxproxion.code.CodeModeHost(this, view)
        codeMode.onTabsChanged = {
            val rp = viewModel.isRpMode()
            tabChat.isSelected = !codeMode.isActive && !rp
            tabRoleplay.isSelected = !codeMode.isActive && rp
            placeModeTabIndicator(animate = true)
        }
        // Tabs slide like the swipe: both pages side by side, never an empty frame between.
        listOf(tabChat, tabRoleplay, codeMode.tab).forEach { tab ->
            tab.setOnClickListener { pageToTab(tab) }
        }
        tabRoleplay.setOnLongClickListener {
            openRpHub()
            true
        }
        tabRoleplay.contentDescription = getString(R.string.mode_tab_roleplay_a11y)
        refreshModeTabs()
        val retab = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            modeTabIndicator.post { placeModeTabIndicator(animate = false) }
        }
        listOf(tabChat, tabRoleplay, codeMode.tab, modeTabIndicator, view.findViewById<View>(R.id.modeTabs)).forEach { it.addOnLayoutChangeListener(retab) }


        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sharedText.filterNotNull().collect { text ->
                    setSharedText(text)
                    viewModel.textConsumed()
                }
            }
        }
        viewModel.isExtendedDockEnabled.observe(viewLifecycleOwner) { isEnabled ->
            utilityButton.visibility = if (isEnabled) View.VISIBLE else View.GONE
            updateComposerAccessoryVisibility()
        }

        viewModel.isPresetsExtendedEnabled.observe(viewLifecycleOwner) { isPresetsOnChatScreen ->
            if (viewModel.isRpMode()) {
                presetsButton2.isVisible = false
                presetsButton.isVisible = false
                return@observe
            }
            val isTopBarEnabled = sharedPreferencesHelper.getExtendedTopBarEnabled()

            // 1. The button near the Send Button (presetsButton2)
            presetsButton2.isVisible = isPresetsOnChatScreen

            // 2. The button in the Menu Area (presetsButton)
            // ONLY show in menu if it's NOT on the chat screen AND NOT in the top bar
            presetsButton.isVisible = !isPresetsOnChatScreen && !isTopBarEnabled
        }
        viewModel.chatMessages.observe(viewLifecycleOwner) { messages ->
            chatAdapter.setMessages(messages)
            updateSendButtonChrome()
            val hasMessages = messages.isNotEmpty()
            // STT disabled — watermark never used for hold-to-talk
            centerWatermarkIcon.isClickable = false
            /*
            val isFeatureEnabled = sharedPreferencesHelper.getWatermarkSttEnabled()
            if (hasMessages || !isFeatureEnabled) {
                centerWatermarkIcon.isClickable = false
            } else {
                centerWatermarkIcon.isClickable = true
            }
            */
            if(hasMessages){
                resetChatButton.icon.alpha = 255
                if (emptyStateContainer.isVisible && emptyStateContainer.alpha > 0f && Motion.areAnimationsEnabled(requireContext())) {
                    // The mark dissolves into the background as the conversation starts.
                    emptyStateContainer.animate().cancel()
                    emptyStateContainer.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f)
                        .setDuration(420).setInterpolator(Motion.easeOut).withEndAction {
                            emptyStateContainer.visibility = View.GONE
                            emptyStateContainer.scaleX = 1f
                            emptyStateContainer.scaleY = 1f
                        }.start()
                } else {
                    emptyStateContainer.visibility = View.GONE
                }
                resetChatButton.isVisible = true
                val lastMessage = messages.last()
                if (lastMessage.role == "assistant" && lastMessage.content is JsonPrimitive) {
                    val contentStr = lastMessage.content.content
                    val currentLen = contentStr.length
                    if (ThinkingPlaceholder.matches(contentStr)) {
                        lastContentLength = 0
                        // Grok-style: pin the user message near the top; leave room below for the reply.
                        pinUserMessageForReply()
                    } else if (currentLen > lastContentLength) {
                        lastContentLength = currentLen
                        if (isShare) {
                            homeButton.visibility = View.GONE
                            backcopyButton.visibility = View.VISIBLE
                            isShare = false
                        }
                        // Stream reveal is painted by ChatAdapter; followStreamingEdge keeps it in view.
                    } else {
                        lastContentLength = currentLen
                    }
                }
            }
            else
            {
                resetChatButton.icon.alpha = 102
                emptyStateContainer.animate().cancel()
                emptyStateContainer.scaleX = 1f
                emptyStateContainer.scaleY = 1f
                emptyStateContainer.visibility = View.VISIBLE
                bindEmptyState(viewModel.isRpMode())
                emptyStateContainer.alpha = 1f
            }
            if(sharedPreferencesHelper.getScrollersPreference()){
                chatRecyclerView.post {
                    val canScrollUp = chatRecyclerView.canScrollVertically(-1)
                    val canScrollDown = chatRecyclerView.canScrollVertically(1)
                    scrollToTopButton.setShownAnimated(canScrollUp)
                    scrollToBottomButton.setShownAnimated(canScrollDown)
                }
            }
            resetChatButton.isEnabled = hasMessages
           // pdfChatButton.isVisible = hasMessages
            //copyChatButton.isVisible = hasMessages
            buttonsRow2.isVisible = hasMessages
            view?.findViewById<View>(R.id.exportLabel)?.isVisible = hasMessages
        }

        fun areAnimationsEnabled(context: Context): Boolean {
            val resolver = context.contentResolver
            return try {
                val durationScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
                durationScale != 0.0f
            } catch (e: Exception) {
                true // Default to true if we can't read settings
            }
        }

        viewModel.isAwaitingResponse.observe(viewLifecycleOwner) { isAwaiting ->
            if (!isAwaiting && sharedPreferencesHelper.getConversationModeEnabled()) {
                val messages = viewModel.chatMessages.value ?: return@observe
                if (messages.isNotEmpty()) {
                    val lastMessage = messages.last()
                    if (lastMessage.role == "assistant" &&
                        !ThinkingPlaceholder.matches(viewModel.getMessageText(lastMessage.content))) {
                        chatRecyclerView.post {
                            val position = messages.size - 1
                            val holder = chatRecyclerView.findViewHolderForAdapterPosition(position) as? ChatAdapter.AssistantViewHolder
                            holder?.ttsButton?.performClick()
                        }
                    }
                }
            }
            if (!isAwaiting) {// && sharedPreferencesHelper.getStreamingPreference()) {
                chatAdapter.finalizeStreaming()
                // Autosave always on: persist with LLM title when streaming completes
                viewModel.autoSaveChat()
            }
            applyRpSwipeChrome(viewModel.rpSwipeNav.value)
            sendChatButton.isEnabled = true
            val materialButton = sendChatButton
            val morphMs = resources.getInteger(R.integer.motion_send_morph).toLong()
            val morphAnim = Motion.areAnimationsEnabled(requireContext())
            fun applyAwaitingChrome() {
                materialButton.setIconResource(R.drawable.ic_stop_grok)
                materialButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.xai_ink)
                )
                materialButton.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_send_disabled)
            }
            fun applyIdleChrome() {
                materialButton.icon = originalSendIcon
                updateSendButtonChrome()
            }
            if (isAwaiting) {
                if (sharedPreferencesHelper.getHapticResponding()) {
                    sendChatButton.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
                sendChatButton.contentDescription = getString(R.string.cd_stop)
                if (!morphAnim) {
                    applyAwaitingChrome()
                } else {
                    materialButton.animate().cancel()
                    // A cancelled send "pop" would otherwise leave the button shrunk.
                    materialButton.scaleX = 1f
                    materialButton.scaleY = 1f
                    materialButton.animate().alpha(0f).setDuration(morphMs / 2).setInterpolator(Motion.easeOut).withEndAction {
                        applyAwaitingChrome()
                        materialButton.animate().alpha(1f).setDuration(morphMs / 2).setInterpolator(Motion.easeOut).start()
                    }.start()
                }
            } else {
                sendChatButton.contentDescription = getString(R.string.cd_send)
                if (!morphAnim) {
                    applyIdleChrome()
                } else {
                    materialButton.animate().cancel()
                    // A cancelled send "pop" would otherwise leave the button shrunk.
                    materialButton.scaleX = 1f
                    materialButton.scaleY = 1f
                    materialButton.animate().alpha(0f).setDuration(morphMs / 2).setInterpolator(Motion.easeOut).withEndAction {
                        applyIdleChrome()
                        materialButton.animate().alpha(1f).setDuration(morphMs / 2).setInterpolator(Motion.easeOut).start()
                    }.start()
                }
                val messages = viewModel.chatMessages.value
                if (messages?.isNotEmpty() == true) {
                    val lastMessage = messages.last()
                    if (lastMessage.role == "assistant") {
                        val baseColor = ContextCompat.getColor(requireContext(), R.color.xai_ink)
                        val isError = lastMessage.content
                            .let { it as? JsonPrimitive }?.content?.startsWith("**Error:**") == true
                        modelChipColorAnimator?.cancel()
                        modelNameTextView.setTextColor(baseColor)
                        if (isError) {
                            val errorColor = ContextCompat.getColor(requireContext(), R.color.xai_error)
                            modelChipColorAnimator = ValueAnimator.ofArgb(baseColor, errorColor, errorColor, baseColor).apply {
                                duration = 3200
                                addUpdateListener { modelNameTextView.setTextColor(it.animatedValue as Int) }
                                start()
                            }
                        }
                        if ( sharedPreferencesHelper.getAnimateBarOnError()) {
                            val borderOverlayView = view.findViewById<View>(R.id.borderOverlayView)
                            val accentColor = ContextCompat.getColor(requireContext(), R.color.xai_mute)
                            val errorColor = ContextCompat.getColor(requireContext(), R.color.xai_error)
                            borderOverlayView.animateOutlineFlash(
                                targetColor = if (isError) errorColor else accentColor,
                                glowDuration = 300,
                                stayDuration = 900,
                                fadeDuration = 500,
                                maxStrokeWidth = (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(2)
                            )
                        }
                    }
                }
            }

           /* if (!isAwaiting && viewModel.shouldAutoOffWebSearch()) {
                viewModel.resetWebSearchAutoOff()
                if (viewModel.isWebSearchEnabled.value == true) {
                    viewModel.toggleWebSearch()  // Turn it off
                    AppToast.makeText(requireContext(), getString(R.string.toast_web_search_auto_off), AppToast.LENGTH_SHORT).show()
                }
            }*/
            if (!isAwaiting //&& viewModel.shouldAutoOffWebSearch()
                &&
                sharedPreferencesHelper.getDisableWebSearchAfterSend() &&
                viewModel.isWebSearchEnabled.value == true) {
                viewModel._isWebSearchEnabled.value = false
                sharedPreferencesHelper.saveWebSearchEnabled(false)
                //   viewModel.resetWebSearchAutoOff()
                // viewModel.toggleWebSearch() // Turn it off
                AppToast.makeText(requireContext(), getString(R.string.toast_web_search_auto_off), AppToast.LENGTH_SHORT).show()
            }
           /* if (isAwaiting) {
                if (areAnimationsEnabled(requireContext())) {
                    // Stop any existing animation first
                    // (materialButton.icon as? Animatable)?.stop()

                    // Set and start new animated drawable
                    val avd = AnimatedVectorDrawableCompat.create(requireContext(), R.drawable.avd_rotating_arc)
                    materialButton.icon = avd
                    avd?.start()
                }
                else {
                    // Fallback: show static arc or different icon when animations are off
                    materialButton.setIconResource(R.drawable.ic_stop) // or another static indicator
                }
            } else {
                // Stop animation and reset to original icon
                (materialButton.icon as? Animatable)?.stop()
                materialButton.icon = originalSendIcon
            }*/
        }

        viewModel.modelPreferenceToSave.observe(viewLifecycleOwner) { model ->
            model?.let {
                sharedPreferencesHelper.savePreferenceModelnewchat(it)
                viewModel.onModelPreferenceSaved()
            }
        }
        viewModel.autosendEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { text ->
                chatEditText.setText(text)
                chatEditText.setSelection(text.length)
                // setText may leave Send disabled until the next TextWatcher pass — force enable.
                sendChatButton.isEnabled = text.isNotBlank()
                sendChatButton.performClick()
                homeButton.visibility = View.GONE
                backButton.visibility = View.VISIBLE
                isShare = true
                if(sharedPreferencesHelper.getAutoBack()){
                    activity?.moveTaskToBack(true)
                }
            }
        }

        // --- Credits Observer ---
        viewModel.creditsResult.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { resultMessage ->
                AppToast.makeText(requireContext(), resultMessage, AppToast.LENGTH_LONG).show()
            }
        }

        viewModel.isStreamingEnabled.observe(viewLifecycleOwner) { isEnabled ->
            streamButton.isSelected = isEnabled
            topStreamButton.isSelected = isEnabled
            if (::rpStreamButton.isInitialized) rpStreamButton.isSelected = isEnabled
            updateStreamToggleAppearance(isEnabled)
        }

        viewModel.isWebSearchEnabled.observe(viewLifecycleOwner) { reflectToolButtons() }
        viewModel.isToolsEnabled.observe(viewLifecycleOwner) { reflectToolButtons() }
        viewModel.isExpandableInputEnabled.observe(viewLifecycleOwner) { isEnabled ->
            if (isEnabled) {
                attachExpandableInputListeners()
            } else {
                detachExpandableInputListeners()
            }
        }
        viewModel.isScrollersEnabled.observe(viewLifecycleOwner) { isEnabled ->
            isScrollersEnabled = isEnabled  // Cache for perf

            if (isScrollersEnabled) {
                // Initial check (safe post)
                chatRecyclerView.post {
                    val canScrollUp = chatRecyclerView.canScrollVertically(-1)
                    val canScrollDown = chatRecyclerView.canScrollVertically(1)
                    scrollToTopButton.setShownAnimated(canScrollUp)
                    scrollToBottomButton.setShownAnimated(canScrollDown)
                }
            } else {
                scrollToTopButton.visibility = View.INVISIBLE
                scrollToBottomButton.visibility = View.INVISIBLE
            }
        }
        viewModel.isScrollersEnabled.observe(viewLifecycleOwner) { isEnabled ->
            if(isEnabled) {
                chatRecyclerView.post {
                    val canScrollUp = chatRecyclerView.canScrollVertically(-1)
                    val canScrollDown = chatRecyclerView.canScrollVertically(1)
                    scrollToTopButton.setShownAnimated(canScrollUp)
                    scrollToBottomButton.setShownAnimated(canScrollDown)
                }
            }
            else{
                scrollToTopButton.visibility = View.INVISIBLE
                scrollToBottomButton.visibility = View.INVISIBLE
            }
        }
        viewModel.isReasoningEnabled.observe(viewLifecycleOwner) { isEnabled ->
            reasoningButton.isSelected = isEnabled
            topReasoningButton.isSelected = isEnabled
            updateReasoningButtonAppearance()
            updateQuickControls()
        }

        viewModel.isAdvancedReasoningOn.observe(viewLifecycleOwner) { isAdvanced ->
            updateReasoningButtonAppearance() // Call helper
            updateQuickControls()
        }
        viewModel.isExtendedTopBarEnabled.observe(viewLifecycleOwner) { isTopBarEnabled ->
            updateExtendedTopBarVisibility(isTopBarEnabled)
            val isPresetsOnChatScreen = viewModel.isPresetsExtendedEnabled.value ?: false
            // Re-verify the menu button visibility whenever top bar changes
            presetsButton.isVisible = !isPresetsOnChatScreen && !isTopBarEnabled
        }
        viewModel.isVolumeScrollEnabled.observe(viewLifecycleOwner) { isEnabled ->
            doVolScroll = isEnabled
        }
        viewModel.isScrollProgressEnabled.observe(viewLifecycleOwner) { enabled ->
            isScrollProgressEnabled = enabled  // Cache for perf
            progressBar.visibility = if (enabled) View.VISIBLE else View.GONE
            progressBar.alpha = 0f
        }
        viewModel.isChatLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                chatAdapter.clearCache()
            }

        }
        viewModel.toastUiEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->
                AppToast.makeText(requireContext(), message, AppToast.LENGTH_LONG).show()
            }
        }
        viewModel.composerRestoreEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { draft ->
                // Empty draft forces clear (Start chat / new character). Non-empty restores
                // only when the field is still blank so we don't clobber newer typing.
                if (draft.isEmpty() || chatEditText.text.isNullOrBlank()) {
                    chatEditText.setText(draft)
                    if (draft.isEmpty()) {
                        val mode = if (viewModel.isRpMode()) ChatMode.RP else ChatMode.ASK
                        sharedPreferencesHelper.saveComposerDraft(mode, "")
                    } else {
                        chatEditText.setSelection(draft.length)
                    }
                }
            }
        }
        viewModel.toolUiEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->

                Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_open_folder) {
                        WorkspacePaths.ensureWorkspaceExists()
                        val path = WorkspacePaths.workspaceDirForRead()
                        val intent = Intent(Intent.ACTION_VIEW)

                        // Disable StrictMode check for file:// URI
                        try {
                            val m = StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
                            m.invoke(null)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        intent.setDataAndType("file://${path.absolutePath}".toUri(), "resource/folder")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        // Always show system chooser
                        val chooserIntent = Intent.createChooser(intent, getString(R.string.chooser_open_file_manager))
                        startActivity(chooserIntent)
                    }
                    .show()
            }
        }
       /* viewModel.toolUiEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->

                val fossifyPackage = "org.fossify.filemanager"
                val packageManager = requireContext().packageManager

                // 1. Check if app is installed
                val isInstalled = try {
                    packageManager.getPackageInfo(fossifyPackage, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }

                if (isInstalled) {
                    // 2. App found: Show Snackbar with Action
                    Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_open_folder) {
                            val path = WorkspacePaths.workspaceDirForRead()
                            //val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val intent = Intent(Intent.ACTION_VIEW)
                            intent.setPackage(fossifyPackage)
                            intent.setDataAndType("file://${path.absolutePath}".toUri(), "resource/folder")
                           // intent.setDataAndType(Uri.fromFile(path), "resource/folder")

                            // --- THIS IS THE FIX ---
                            // This forces the app to open in its own stack/window
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            // Optional: Clears the file manager if it was already open, so it refreshes to this folder
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            // -----------------------

                            // Disable StrictMode check for file:// URI
                            try {
                                val m = StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
                                m.invoke(null)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            startActivity(intent)
                        }
                        .show()
                } else {
                    // 3. App not found: Just show the Toast
                    AppToast.makeText(requireContext(), message, AppToast.LENGTH_SHORT).show()
                }
            }
        }*/
        viewModel.presetAppliedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                updateSystemMessageButtonState()
                convoButton.isSelected = sharedPreferencesHelper.getConversationModeEnabled()
            }
        }
        /*viewModel.scrollToBottomEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                if (chatAdapter.itemCount > 0) {
                    chatRecyclerView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            chatRecyclerView.viewTreeObserver.removeOnPreDrawListener(this)
                            val position = chatAdapter.itemCount - 1
                            layoutManager.scrollToPositionWithOffset(position, -12)
                            return true
                        }
                    })
                }
            }
        }*/
        viewModel.scrollToBottomEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                // Grok-style: do not yank the camera when the stream finishes.
                // Only refresh optional scroll affordances.
                chatRecyclerView.post {
                    if (sharedPreferencesHelper.getScrollersPreference()) {
                        val canScrollUp = chatRecyclerView.canScrollVertically(-1)
                        val canScrollDown = chatRecyclerView.canScrollVertically(1)
                        scrollToTopButton.setShownAnimated(canScrollUp)
                        scrollToBottomButton.setShownAnimated(canScrollDown)
                    }
                }
            }
        }
        // STT disabled
        /*
        val shouldStartStt = arguments?.getBoolean("start_stt_on_launch", false) ?: false
        if (shouldStartStt) {
            arguments?.remove("start_stt_on_launch")
            hideKeyboard()
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startSpeechRecognition()
            }
        }
        */
        val selectedFontName = sharedPreferencesHelper.getSelectedFont()
        val typeface = AppFonts.resolveSelectable(requireContext(), selectedFontName)
        chatEditText.typeface = typeface ?: Typeface.DEFAULT
        modelNameTextView.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, 600, false)
        chatAdapter.updateFont(typeface)
        if (sharedPreferencesHelper.getKeepScreenOnPreference()) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (notificationManager != null) {
            val channels = notificationManager.notificationChannels
            val channelIds = channels.map { it.id }
            if (channelIds.contains("SilentUpdatesChannel")) {
                notificationManager.deleteNotificationChannel("SilentUpdatesChannel")
            }
        }
        parentFragmentManager.setFragmentResultListener("edit_request_key", viewLifecycleOwner) { _, bundle ->
            val position = bundle.getInt("position")
            val newContent = bundle.getString("content")

            if (newContent != null) {
                chatAdapter.flagEditUpdate(position)
                viewModel.updateMessageAt(position, newContent)
            }
        }
        parentFragmentManager.setFragmentResultListener("prompt_request", this) { _, bundle ->
            val prompt = bundle.getString("prompt")
            prompt?.let {
                chatEditText.text.clear()
                chatEditText.setText(it)
                /*  chatEditText.postDelayed({
                      sendChatButton.performClick()
                  }, 100)*/
            }
        }
        if (isScrollProgressEnabled) chatRecyclerView.post { updateScrollProgress() }

        updateExtendedTopBarVisibility(sharedPreferencesHelper.getExtendedTopBarEnabled())
        updateModelSourceIndicator()
        applyChatTextScale()
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // 1. IMPORTANT: Remove the listener immediately so it doesn't fire
                // every time the layout changes (like when a message arrives)
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val mode = when (sharedPreferencesHelper.getThemeMode()) {
                    SharedPreferencesHelper.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    SharedPreferencesHelper.THEME_DARK  -> AppCompatDelegate.MODE_NIGHT_YES
                    else                               -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)

            }
        })
        if (viewModel.activeModelIsLan()) {
            checkLocalNetworkPermission()
        }
        // end onviewcreated
    }

    private fun updateSystemMessageButtonState() {
        val selectedSystemMessage = sharedPreferencesHelper.getSelectedSystemMessage()
        systemMessageButton.isSelected = !selectedSystemMessage.isDefault
        updateChatEditTextHint()
    }

    private fun updateChatEditTextHint() {
        if (viewModel.isRpMode()) {
            applyRpComposerHint()
            return
        }
        val selectedMessage = sharedPreferencesHelper.getSelectedSystemMessage()
        val isDefault = selectedMessage.isDefault
        val title = selectedMessage.title.trim()
        chatEditText.hint = if (isDefault) {
            getString(R.string.grok_composer_hint)
        } else {
            getString(R.string.grok_composer_hint_with_system, title)
        }
    }

    private fun applyRpComposerHint() {
        val llm = sharedPreferencesHelper.isRpLlmMode()
        val activeChar = viewModel.activeRpCharacter.value
        chatEditText.hint = when {
            llm -> getString(R.string.rp_composer_hint_llm)
            activeChar != null -> getString(R.string.rp_composer_hint, activeChar.name)
            else -> getString(R.string.rp_composer_hint_empty)
        }
    }

    private fun setSharedText(sharedText: String) {
        if (sharedText.isBlank()) {
            chatEditText.setText("")  // Handle empty case
            return
        }

        // Just set the raw text (trimmed for leading/trailing spaces)
        chatEditText.setText(sharedText.trim())
        chatEditText.setSelection(chatEditText.length())
    }
    private fun moveView(view: View, newParent: ViewGroup) {
        val oldParent = view.parent as? ViewGroup
        oldParent?.removeView(view)
        newParent.addView(view)
    }
    private fun setInputExpandedState(expanded: Boolean) {
        val containerParams = chatInputContainer.layoutParams
        val editParams = chatEditText.layoutParams as LinearLayout.LayoutParams

        // 1. Define the order for EXPANDED (Left-to-Right). Only composer-native buttons move;
        //    panel tiles (new chat, system, paste, clear) stay in the Controls panel so they
        //    are never stranded in the hidden expanded row after collapsing.
        // Send button is last to make it rightmost
        val expandedOrder = listOf(menuButton, controlsButton, speechButton, sendChatButton)

        // 2. Define the order for COLLAPSED
        val leftCollapsed = listOf(menuButton, controlsButton)
        val rightCollapsed = listOf(speechButton, sendChatButton)

        view?.findViewById<View>(R.id.composerDock)?.let { dock ->
            val topPad = if (expanded) view?.findViewById<View>(R.id.topBarGlass)?.height ?: 0 else 0
            dock.setPadding(0, topPad, 0, 0)
        }
        if (expanded) {
            containerParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            editParams.height = 0
            editParams.weight = 1f
            chatEditText.maxLines = Integer.MAX_VALUE
            chatFrameView.visibility = View.GONE

            // Use the horizontal order
            expandedOrder.forEach { btn ->
                moveView(btn, expandedButtonContainer)
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                params.gravity = Gravity.CENTER
                btn.layoutParams = params
            }

            leftButtonContainer.visibility = View.GONE
            rightButtonContainer.visibility = View.GONE
            expandedButtonContainer.visibility = View.VISIBLE

        } else {
            containerParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            editParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            editParams.weight = 0f
            chatEditText.maxLines = 6
            chatFrameView.visibility = View.VISIBLE

            // Restore Left side in order
            leftCollapsed.forEach { btn ->
                moveView(btn, leftButtonContainer)
                applyCollapsedParams(btn)
            }
            // Keep the model pill inside its weighted shell (ellipsize contract from
            // 70ea7f8). Collapse must not orphan the weight=1 FrameLayout.
            moveView(modelNameShell, leftButtonContainer)
            moveView(modelNameTextView, modelNameShell)

            // Restore Right side in order (Send will be added first, so it sits at the top)
            rightCollapsed.forEach { btn ->
                moveView(btn, rightButtonContainer)
                applyCollapsedParams(btn)
            }

            expandedButtonContainer.visibility = View.GONE
            leftButtonContainer.visibility = View.VISIBLE
            rightButtonContainer.visibility = View.VISIBLE
        }
        chatInputContainer.layoutParams = containerParams
        chatEditText.layoutParams = editParams
    }

    // Helper to keep the code clean
    private fun applyCollapsedParams(btn: View) {
        val density = resources.displayMetrics.density
        val size = (38 * density).toInt()
        val params = LinearLayout.LayoutParams(size, size)
        val gap = when {
            btn === controlsButton -> (8 * density).toInt()
            btn === sendChatButton -> (4 * density).toInt()
            else -> 0
        }
        params.setMargins(gap, 0, 0, 0)
        btn.layoutParams = params
    }

    private fun attachExpandableInputListeners() {
        // 1. Focus Listener: Expands when touched
        chatEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setInputExpandedState(true)
            }
        }
        val rootView = view as FrameLayout
        // 2. Insets Listener: Collapses when Keyboard closes (Back button or hideKeyboard())
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // If keyboard is gone AND we have focus, it means we need to close up shop
            if (!isKeyboardVisible && chatEditText.hasFocus()) {
                setInputExpandedState(false)
                chatEditText.clearFocus()
            }
            insets
        }
    }

    private fun detachExpandableInputListeners() {
        // 1. Force collapse immediately (in case user disabled it while it was open)
        setInputExpandedState(false)
        val rootView = view as FrameLayout
        // 2. Remove Focus Listener
        chatEditText.onFocusChangeListener = null

        // 3. Remove Insets Listener (Stop listening to keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(rootView, null)
    }
    // ── Glass chrome ────────────────────────────────────────────────────────────────
    // The transcript fills the screen and scrolls beneath a glass top bar and a floating
    // glass composer. Insets are measured from the live chrome so the first and last
    // messages always clear it, whatever the top bar row or composer height is.
    private var chromeTop = -1
    private var chromeBottom = -1
    private val chromeBaseMargins = HashMap<View, Int>()

    /**
     * Edge to edge: the backdrop (and the chosen background) runs under the status and
     * navigation bars instead of stopping at a flat canvas strip. Only the chrome is inset: the
     * glass top bar grows by the status bar, the composer and Code mode sit above the nav bar
     * and the keyboard.
     */
    private fun setupEdgeToEdge(root: View) {
        val content = root.findViewById<ViewGroup>(R.id.rootLayout)
        val frame = root.findViewById<ViewGroup>(R.id.chatFrameView)
        val backdrop = root.findViewById<View>(R.id.chatBackdrop)
        val topBar = root.findViewById<View>(R.id.topBarGlass)
        val dock = root.findViewById<View>(R.id.composerDock)
        val code = root.findViewById<View>(R.id.codeModeContainer)
        val barTop = topBar.paddingTop
        val dockBottom = dock.paddingBottom
        frame.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            v.setPadding(bars.left, 0, bars.right, 0)
            topBar.setPadding(topBar.paddingLeft, barTop + bars.top, topBar.paddingRight, topBar.paddingBottom)
            // Everything in the chat frame keeps its old place; only the backdrop bleeds out.
            frame.setPadding(0, bars.top, 0, bottom)
            (backdrop.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                if (lp.topMargin != -bars.top || lp.bottomMargin != -bottom) {
                    lp.topMargin = -bars.top
                    lp.bottomMargin = -bottom
                    backdrop.layoutParams = lp
                }
            }
            dock.setPadding(dock.paddingLeft, dock.paddingTop, dock.paddingRight, dockBottom + bottom)
            code.setPadding(0, 0, 0, bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun setupGlassChrome(root: View) {
        val backdrop = root.findViewById<GlassBackdropLayout>(R.id.chatBackdrop)
        val topGlass = root.findViewById<View>(R.id.topBarGlass)
        topGlass.background?.mutate()?.alpha = 0
        (chatInputContainer as? GlassLinearLayout)?.glass?.source = backdrop
        (headerContainer as? GlassLinearLayout)?.glass?.source = backdrop
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyChromeInsets() }
        topGlass.addOnLayoutChangeListener(relayout)
        root.findViewById<View>(R.id.composerDock).addOnLayoutChangeListener(relayout)
        chatInputContainer.addOnLayoutChangeListener(relayout)
        rpComposerExtras.addOnLayoutChangeListener(relayout)
        chatRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateTopBarEdge()
        })
    }

    /** iOS scroll-edge effect: the fade under the floating controls appears once content is beneath them. */
    private fun updateTopBarEdge() {
        val fade = view?.findViewById<View>(R.id.topBarGlass)?.background ?: return
        val under = chatRecyclerView.computeVerticalScrollOffset().toFloat()
        val a = (255 * (under / (24f * resources.displayMetrics.density)).coerceIn(0f, 1f)).toInt()
        if (fade.alpha != a) fade.alpha = a
    }

    private fun applyChromeInsets() {
        val root = view ?: return
        val topGlass = root.findViewById<View>(R.id.topBarGlass) ?: return
        val dock = root.findViewById<ViewGroup>(R.id.composerDock) ?: return
        if (dock.height == 0) return
        var contentTop = dock.height
        for (i in 0 until dock.childCount) {
            val c = dock.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            val lp = c.layoutParams as ViewGroup.MarginLayoutParams
            contentTop = minOf(contentTop, c.top - lp.topMargin)
        }
        val top = if (topGlass.isVisible) topGlass.height else 0
        val bottom = (dock.height - contentTop).coerceAtLeast(0)
        if (top == chromeTop && bottom == chromeBottom) return
        val grew = if (chromeBottom >= 0) bottom - chromeBottom else 0
        chromeTop = top
        chromeBottom = bottom
        // Post: we are inside a layout pass; padding/margin changes request another one.
        root.post {
            if (view == null) return@post
            val d = resources.displayMetrics.density
            val atBottom = !chatRecyclerView.canScrollVertically(1)
            chatRecyclerView.setPadding(
                chatRecyclerView.paddingLeft,
                top + (8 * d).toInt(),
                chatRecyclerView.paddingRight,
                bottom + (14 * d).toInt()
            )
            if (atBottom && grew > 0) chatRecyclerView.post { chatRecyclerView.scrollBy(0, grew) }
            listOf(headerContainer, attachmentPreviewContainer, extBG, fontSizeControlsContainer).forEach { v ->
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
                val base = chromeBaseMargins.getOrPut(v) { lp.bottomMargin }
                if (lp.bottomMargin != base + bottom) {
                    lp.bottomMargin = base + bottom
                    v.layoutParams = lp
                }
            }
            (progressBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    progressBar.layoutParams = lp
                }
            }
            emptyStateContainer.setPadding(0, top, 0, bottom)
            root.findViewById<View>(R.id.composerFade)?.let { fade ->
                val h = bottom + (28 * d).toInt()
                if (fade.layoutParams.height != h) {
                    fade.layoutParams.height = h
                    fade.requestLayout()
                }
            }
            updateTopBarEdge()
        }
    }

    /**
     * While a reply streams, keep its growing edge above the composer, unless the reader has
     * dragged away to look at something else. Scrolling back to the bottom re-engages it.
     */
    private var followStream = true
    private var listDragging = false
    private var followPending = false

    private fun followStreamingEdge() {
        if (!followStream || listDragging || followPending) return
        followPending = true
        chatRecyclerView.doOnPreDraw {
            followPending = false
            if (!followStream || listDragging) return@doOnPreDraw
            val last = chatAdapter.itemCount - 1
            val child = layoutManager.findViewByPosition(last) ?: return@doOnPreDraw
            val limit = chatRecyclerView.height - chatRecyclerView.paddingBottom
            val overflow = child.bottom - limit
            if (overflow > 0) chatRecyclerView.scrollBy(0, overflow)
        }
    }

    /** Pin the just-sent user row near the top so the reply has empty space below (Grok-style). */
    private fun pinUserMessageForReply() {
        followStream = true
        chatRecyclerView.post {
            chatRecyclerView.post {
                val userPos = chatAdapter.itemCount - 2
                if (userPos >= 0) {
                    layoutManager.scrollToPositionWithOffset(userPos, 16)
                }
            }
        }
    }

    /** Keep the last bubble's action row (copy / instruct / regen) above the composer. */
    private fun scrollChatToLatestEnd() {
        chatRecyclerView.post {
            val last = chatAdapter.itemCount - 1
            if (last < 0) return@post
            layoutManager.scrollToPosition(last)
            chatRecyclerView.post {
                val child = layoutManager.findViewByPosition(last) ?: return@post
                val target = chatRecyclerView.height - chatRecyclerView.paddingBottom
                val extra = child.bottom - target
                if (extra > 0) chatRecyclerView.scrollBy(0, extra)
            }
        }
    }

    private fun setupRecyclerView() {
        layoutManager = NonScrollingOnFocusLayoutManager(requireContext()).apply {
            stackFromEnd = false
        }
        chatAdapter = ChatAdapter(
            viewLifecycleOwner.lifecycleScope,
            markwon,
           // viewModel,
            { text, position -> speakText(text, position) },
            { text, position -> synthesizeToWavFile(text, position) },
            ttsAvailable,
            onEditMessage = { position, text ->
                selectedImageBytes = null
                selectedImageMime = null
                attachmentPreviewContainer.visibility = View.GONE
                viewModel.setPendingUserImageUri(null)
                if (viewModel.isRpMode()) {
                    viewModel.truncateForRpEdit(position)
                } else {
                    viewModel.stashAndTruncateFrom(position, anchorAssistantIndex = -1)
                }
                chatEditText.setText(text)
                chatEditText.setSelection(text.length)
                hideMenu()
                chatEditText.showKeyboard()
                viewModel.autoSaveChat()
            },
            onRedoMessage = { position, _ ->
                if (viewModel.isRpMode()) {
                    viewModel.regenerateLastRpReply()
                } else {
                    val systemMessage = sharedPreferencesHelper.getSelectedSystemMessage().prompt
                    viewModel.resendExistingPrompt(position, systemMessage)
                }
                hideMenu()
                scrollChatToLatestEnd()
            },
            onInstructMessage = { _ ->
                val input = com.google.android.material.textfield.TextInputEditText(requireContext())
                input.hint = getString(R.string.rp_instruct_hint)
                input.minLines = 2
                val wrapper = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
                    hint = getString(R.string.rp_instruct)
                    addView(input)
                    setPadding(48, 24, 48, 8)
                }
                val dialog = GlassAlertDialogBuilder(
                    requireContext(),
                    R.style.CustomMaterialAlertDialogTheme
                )
                    .setTitle(R.string.rp_instruct)
                    .setView(wrapper)
                    .setPositiveButton(R.string.action_ok, null)
                    .setNegativeButton(R.string.action_cancel, null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        hideKeyboardFrom(input)
                        val text = input.text?.toString()?.trim().orEmpty()
                        if (text.isBlank()) {
                            dialog.dismiss()
                            return@setOnClickListener
                        }
                        // Keep dialog open on soft-fail so the typed instruct isn't lost.
                        if (!viewModel.instructLastRpReply(text)) return@setOnClickListener
                        dialog.dismiss()
                        scrollChatToLatestEnd()
                    }
                }
                dialog.show()
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                input.requestFocus()
            },
            onDeleteMessage = { position ->
                hideMenu()
                GrokConfirmDialog.show(
                    fragment = this@ChatFragment,
                    title = getString(R.string.delete_message_title),
                    message = getString(R.string.delete_message_body),
                    confirmText = getString(R.string.delete_message_confirm),
                    onConfirm = {
                        viewModel.deleteMessageAt(position)
                        chatRecyclerView.post {
                            if (chatAdapter.itemCount > 0) {
                                layoutManager.scrollToPosition(chatAdapter.itemCount - 1)
                            }
                        }
                        viewModel.autoSaveChat()
                    }
                )
            },
            onEditAssistantMessage = { position, currentRawText ->
                val editFragment = EditMessageFragment.newInstance(position, currentRawText)
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    .hide(this)
                    .add(R.id.fragment_container, editFragment)
                    .addToBackStack(null)
                    .commit()
            },
            onSaveMarkdown = { position, rawMarkdown ->
                viewModel.saveMarkdownToDownloads(rawMarkdown)  // Your ViewModel method
            },
            onCaptureItemToBitmap = ::captureItemToBitmap,
            onShowMarkdown = { markdown ->
                val modelString = viewModel.activeChatModel.value ?: "AI"
                val modeltoPass = viewModel.getModelDisplayName(modelString)
                val selectedFontName = sharedPreferencesHelper.getSelectedFont()
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    //.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                    .hide(this)  // Hides chat fragment
                    .add(R.id.fragment_container, MarkdownViewerFragment.newInstance(markdown,selectedFontName,modeltoPass))
                    .addToBackStack(null)
                    .commit()
            },
            onSaveHtml = { markdown ->
                // Generate clean HTML (light theme, no custom font)
                val htmlContent = MarkdownRenderer.toHtmlExp(markdown)
                viewModel.saveHtmlSingleToDownloads(htmlContent)
            },
            onSaveText = {position, text ->
                viewModel.saveTextToDownloads(text)
            }, onCollapse = {
                if (isScrollProgressEnabled) {
                    updateScrollProgress()
                }

                if(isScrollersEnabled)
                    chatRecyclerView.post {  // Keep post for layout safety
                        val canScrollUp = chatRecyclerView.canScrollVertically(-1)
                        val canScrollDown = chatRecyclerView.canScrollVertically(1)
                        scrollToTopButton.setShownAnimated(canScrollUp)
                        scrollToBottomButton.setShownAnimated(canScrollDown)
                    }
            },
            onSaveAsFile = { content ->
                showSaveFileDialog(content)
            },
            forkNavStateForPosition = { position ->
                viewModel.getForkNavForMessage(position)
            },
            onForkNavigate = { direction ->
                viewModel.navigateFork(direction)
                chatRecyclerView.post {
                    if (chatAdapter.itemCount > 0) {
                        layoutManager.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }

        )
        chatAdapter.onTogglePin = { index ->
            val pinned = viewModel.toggleMessagePin(index)
            if (pinned != null) {
                AppToast.makeText(
                    requireContext(),
                    getString(if (pinned) R.string.rp_pin_on else R.string.rp_pin_off),
                    AppToast.LENGTH_SHORT
                ).show()
            }
        }
        chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = this@ChatFragment.layoutManager
        }
        chatAdapter.onStreamVisualUpdate = { followStreamingEdge() }
        chatRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // Freeze the ambient field while the list moves: each frame re-blurs the glass.
                ambientBackground?.setScrolling(newState != RecyclerView.SCROLL_STATE_IDLE)
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        listDragging = true
                        followStream = false
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        listDragging = false
                        if (!recyclerView.canScrollVertically(1)) followStream = true
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (isScrollProgressEnabled) {
                    updateScrollProgress()
                }
                if(isScrollersEnabled)
                    chatRecyclerView.post {  // Keep post for layout safety
                        val canScrollUp = chatRecyclerView.canScrollVertically(-1)
                        val canScrollDown = chatRecyclerView.canScrollVertically(1)
                        scrollToTopButton.setShownAnimated(canScrollUp)
                        scrollToBottomButton.setShownAnimated(canScrollDown)
                    }
            }
        })

        // Sent bubbles rise from the composer on a spring; replies fade up.
        chatRecyclerView.itemAnimator = ChatItemAnimator(ChatAdapter.VIEW_TYPE_USER)
    }

    fun View.showKeyboard() {
        doOnPreDraw {
            if (!isFocusable || !isFocusableInTouchMode) return@doOnPreDraw
            requestFocus()
            windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    fun View.hideKeyboard() {
        doOnPreDraw {
            windowInsetsController?.hide(WindowInsets.Type.ime())
            requestFocus()
        }
    }
    override fun onDestroyView() {
        ambientBackground = null
        pickerPopover?.dismiss(animated = false)
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        mediaRecorder?.release()
        mediaRecorder = null
        voiceRecordFile?.delete()
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        chatAdapter.clearCache()
        val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(2)

    }
    private fun performNewChat() {
        if (viewModel.chatMessages.value.isNullOrEmpty()) return
        viewModel.startFreshChatForCurrentMode()
        chatEditText.setText("")
        chatEditText.text.clear()
        currentTempImageFile?.delete()
        currentTempImageFile = null
        selectedAudioBytes = null
        selectedAudioFormat = null
        previewImageView.setImageBitmap(null)
        pendingFiles.clear()
        updateAttachmentButton()
        chatAdapter.clearCache()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClickListeners() {
        removeAttachmentButton.setOnClickListener {
            selectedImageBytes = null
            selectedImageMime = null
            selectedAudioBytes = null
            selectedAudioFormat = null
            attachmentPreviewContainer.visibility = View.GONE
            viewModel.setPendingUserImageUri(null)
            previewImageView.setImageBitmap(null)
            currentTempImageFile?.delete()
            currentTempImageFile = null
            AppToast.makeText(requireContext(), getString(R.string.toast_attachment_removed), AppToast.LENGTH_SHORT).show()
        }
        webSearchButton.setOnClickListener {
            //  hideMenu()
            viewModel.toggleWebSearch()
            // NEW: Set flag to auto-disable after next response
           // viewModel.setWebSearchAutoOff(true)
        }
        webSearchButton.setOnLongClickListener {
            showWebSearchEngineDialog()
            true
        }
       /* toolsButton.setOnLongClickListener {
            if (!hasFolderPermission()) {
                val folderPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "oxproxion")
                if (!folderPath.exists()) folderPath.mkdirs()
                AppToast.makeText(requireContext(), getString(R.string.toast_gradation_folder), AppToast.LENGTH_LONG).show()
                folderPickerLauncher.launch(null)
            } else {
                val folderPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "oxproxion")
                if (!folderPath.exists()) folderPath.mkdirs()
                hideMenu()
                showToolsSelectionDialog()
            }
            true   // consume the long‑click
        }*/

        toolsButton.setOnLongClickListener {
            // Simply open the ToolsFragment
            // The ToolsFragment will handle creating the folder and checking permissions
            hideMenu()

            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, ToolsFragment())
                .addToBackStack(null)
                .commit()

            true // consume the long-click
        }

        toolsButton.setOnClickListener {
            if (!hasFolderPermission()) {
                WorkspacePaths.ensureWorkspaceExists()
                AppToast.makeText(requireContext(), getString(R.string.toast_gradation_folder), AppToast.LENGTH_LONG).show()
                folderPickerLauncher.launch(null)
            } else {
                WorkspacePaths.ensureWorkspaceExists()
                // hideMenu()
                viewModel.toggleToolsEnabled()
            }
        }
        sendChatButton.setOnClickListener {
            dictation?.finishNow()
            if (sharedPreferencesHelper.getHapticButtons()) {
                sendChatButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (viewModel.isAwaitingResponse.value == true) {
                viewModel.cancelCurrentRequest()
                //   viewModel.playCancelTone()
            } else {
                // --- API Key Check ---
                if (viewModel.activeModelIsLan()) {
                    // LAN model: Check endpoint instead of API key
                    val lanEndpoint = viewModel.getLanEndpoint()
                    if (lanEndpoint.isNullOrBlank()) {
                        GlassNotice.show(requireContext(), getString(R.string.notice_need_lan))
                        return@setOnClickListener
                    }
                } else {
                    // Non-LAN model: Check API key
                    if (viewModel.activeChatApiKey.isBlank() && !viewModel.activeModelIsDemo()) {
                        GlassNotice.show(requireContext(), getString(R.string.notice_need_key))
                        return@setOnClickListener
                    }
                }
                if (viewModel.isTranscriptionModel(viewModel.activeChatModel.value) && selectedAudioBytes != null) {
                    if (viewModel.isRpMode()) {
                        GlassNotice.show(requireContext(), getString(R.string.rp_attachments_disabled))
                        return@setOnClickListener
                    }
                    hideKeyboard()

                    val audioBytes = selectedAudioBytes!!
                    val audioFormat = selectedAudioFormat ?: "wav"

                    if (viewModel.activeModelIsLan()) {
                        viewModel.sendTranscriptionLan(audioBytes, audioFormat, "audio.$audioFormat")
                    } else {
                        viewModel.sendTranscriptionOpenRouter(audioBytes, audioFormat)
                    }

                    // Clear audio attachment
                    selectedAudioBytes = null
                    selectedAudioFormat = null
                    attachmentPreviewContainer.visibility = View.GONE

                    return@setOnClickListener
                }
                hideKeyboard()
                var prompt = chatEditText.text.toString().trim()
                // Empty RP composer: the send button is "Continue", so the character takes the next beat.
                if (prompt.isEmpty() && pendingFiles.isEmpty() && selectedImageBytes == null &&
                    selectedAudioBytes == null && viewModel.canContinueRpStory()
                ) {
                    viewModel.continueRpStory()
                    return@setOnClickListener
                }
                // RP forbids attachments; reject before file prepend so drafts stay clean.
                if (viewModel.isRpMode() &&
                    (selectedImageBytes != null || selectedAudioBytes != null || pendingFiles.isNotEmpty())
                ) {
                    GlassNotice.show(requireContext(), getString(R.string.rp_attachments_disabled))
                    return@setOnClickListener
                }
                if (pendingFiles.isNotEmpty()) {
                    val fileSections = pendingFiles.mapIndexed { index, file ->  // Explicit -> String
                        val cleanContent = file.content.trim()
                        if (cleanContent.isNotBlank()) {
                            // Raw string for if branch: No escaping needed, newline after filename
                            """File ${index + 1} (${file.fileName}):

```text
$cleanContent
```"""
                        } else {
                            // Raw string for else branch: Matches type, no escaping
                            """File ${index + 1} (${file.fileName}): (empty file)"""
                        }
                    }.joinToString("\n\n")  // Now safely String

                    // Reassign prompt: Type-safe since fileSections is String
                    prompt = if (prompt.isNotBlank()) {
                        "$fileSections\n\n**User message:**\n$prompt"
                    } else {
                        "$fileSections\n\nPlease analyze these attached files."
                    }
                }
                val substitutedPrompt = substituteVariables(prompt)  //#subpromptcode
                val systemMessage = sharedPreferencesHelper.getSelectedSystemMessage()//#subpromptcode
                val substitutedSystemPrompt = substituteVariables(systemMessage.prompt)//#subpromptcode
                //if (prompt.isNotBlank() || selectedImageBytes != null) { //#subpromptcode replaced
                if (substitutedPrompt.isNotBlank() || selectedImageBytes != null) { //#subpromptcode
                    /* if (!ForegroundService.isRunningForeground) {
                         ChatServiceGate.shouldRunService = true
                         startForegroundService()
                     }*/

                  /*  if (ForegroundService.isRunningForeground && sharedPreferencesHelper.getNotiPreference()) {
                        val apiIdentifier = viewModel.activeChatModel.value ?: "Unknown Model"
                        val displayName = viewModel.getModelDisplayName(apiIdentifier)
                        ForegroundService.updateNotificationStatusSilently(displayName, "Prompt sent. Awaiting Response.")
                    }*/

                    // RP: validate before clearing the composer so character-gate failures keep the draft.
                    if (viewModel.isRpMode()) {
                        if (!viewModel.canSendRpMessage()) {
                            AppToast.makeText(requireContext(), getString(R.string.rp_select_character), AppToast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (viewModel.isAwaitingResponse.value == true) {
                            AppToast.makeText(requireContext(), getString(R.string.rp_wait_for_reply), AppToast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!viewModel.sendRpUserMessage(substitutedPrompt)) {
                            return@setOnClickListener
                        }
                        chatEditText.setText("")
                        chatEditText.text.clear()
                        return@setOnClickListener
                    }

                    chatEditText.setText("")
                    chatEditText.text.clear()

                    val userContent = if (selectedImageBytes != null) {
                        val base64 = Base64.encodeToString(selectedImageBytes, Base64.NO_WRAP)
                        val imageUrl = "data:$selectedImageMime;base64,$base64"
                        buildJsonArray {
                            // if (prompt.isNotBlank()) { //#subpromptcode replaced
                            if (substitutedPrompt.isNotBlank()) { //#subpromptcode
                                add(
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("text"),
                                            // "text" to JsonPrimitive(prompt) //#subpromptcode replaced
                                            "text" to JsonPrimitive(substitutedPrompt) //#subpromptcode
                                        )
                                    )
                                )
                            }
                            add(
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("image_url"),
                                        "image_url" to JsonObject(
                                            mapOf(
                                                "url" to JsonPrimitive(imageUrl)
                                            )
                                        )
                                    )
                                )
                            )
                        }
                    } else {
                        //  JsonPrimitive(prompt) //#subpromptcode replaced
                        JsonPrimitive(substitutedPrompt)  //#subpromptcode
                    }
                    //   val systemMessage = sharedPreferencesHelper.getSelectedSystemMessage() //#subpromptcode commentedout
                    //   viewModel.sendUserMessage(userContent, systemMessage.prompt) //#subpromptcode replaced
                    viewModel.sendUserMessage(userContent, substitutedSystemPrompt) //#subpromptcode
                  //  chatEditText.clearFocus()
                    hideMenu()
                    selectedImageBytes = null
                    selectedImageMime = null
                    attachmentPreviewContainer.visibility = View.GONE
                    pendingFiles.clear()
                    updateAttachmentButton()
                }
            }
        }
        genButton.setOnClickListener {
            val model = viewModel.activeChatModel.value ?: return@setOnClickListener

            val isGoogleImageModel = model.startsWith("google/", ignoreCase = true) &&
                    model.contains("image", ignoreCase = true)

            if (!isGoogleImageModel) {
                AppToast.makeText(
                    requireContext(),
                    "Image generation parameters only supported for Google image models",
                    AppToast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Dialog options (match docs: 1:1, 16:9, etc.)
            val aspectRatios = arrayOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")
            val currentRatio = sharedPreferencesHelper.getGeminiAspectRatio() ?: "1:1"  // Default 1:1
            val selectedIndex = aspectRatios.indexOf(currentRatio)

            GlassAlertDialogBuilder(requireContext())
                .setTitle(R.string.image_gen_aspect_title)
                .setSingleChoiceItems(aspectRatios, selectedIndex) { _, which ->
                    val selectedRatio = aspectRatios[which]
                    sharedPreferencesHelper.saveGeminiAspectRatio(selectedRatio)
                }
                .setPositiveButton(android.R.string.ok) { _, _ -> /* Dialog dismisses */ }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        modelNameTextView.setOnClickListener {
            hideKeyboard()
            if (viewModel.isRpMode()) showRpCharacterPanel() else showModelPopover()
        }

        systemMessageButton.setOnClickListener {
            hideKeyboard()
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, SystemMessageLibraryFragment())
                .addToBackStack(null)
                .commit()
        }
        resetChatButton.setOnLongClickListener {
            performNewChat()
            true
        }
        resetChatButton.setOnClickListener {
            performNewChat()
        }
        // STT disabled — watermark hold-to-talk
        centerWatermarkIcon.setOnTouchListener { _, _ -> false }
        /*
        centerWatermarkIcon.setOnTouchListener { v, event ->
            val isFeatureEnabled = sharedPreferencesHelper.getWatermarkSttEnabled()
            val isChatEmpty = viewModel.chatMessages.value.isNullOrEmpty()

            if (!isFeatureEnabled || !isChatEmpty) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        startVoiceRecording()
                        fromWater = true
                        v.animate()
                            .scaleX(2.6f)
                            .scaleY(2.6f)
                            .setDuration(300)
                            .start()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        stopVoiceRecording()
                    }
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300)
                        .start()
                    true
                }
                else -> false
            }
        }
        */

        // IMPORTANT: Remove the OnLongClickListener to prevent conflict with OnTouchListener
        centerWatermarkIcon.setOnLongClickListener { true }
        topReasoningButton.setOnClickListener { reasoningButton.performClick() }
        topWebSearchButton.setOnClickListener { webSearchButton.performClick() }
        topStreamButton.setOnClickListener { streamButton.performClick() }
       // topConvoButton.setOnClickListener { convoButton.performClick() }
        topPresetsButton.setOnClickListener { presetsButton.performClick() }
        topSettingsButton.setOnClickListener { settingsButton.performClick() }
        topToolsButton.setOnClickListener { toolsButton.performClick() }
        topToolsButton.setOnLongClickListener {
            toolsButton.performLongClick()
            true
        }
        btnIncreaseFont.setOnClickListener {
            val newScale = (sharedPreferencesHelper.getFontSizeCh() + 5).coerceAtMost(300)
            sharedPreferencesHelper.saveFontSizeCh(newScale)
            chatAdapter.updateFontSize(newScale)
        }

        btnDecreaseFont.setOnClickListener {
            val newScale = (sharedPreferencesHelper.getFontSizeCh() - 5).coerceAtLeast(50)
            sharedPreferencesHelper.saveFontSizeCh(newScale)
            chatAdapter.updateFontSize(newScale)
        }

// Hide controls
        btnDoneFont.setOnClickListener {
            fontSizeControlsContainer.visibility = View.GONE
        }
        newChatButton.setOnClickListener {
            if (codeMode.isActive) return@setOnClickListener codeMode.onNewPressed()
            resetChatButton.performClick()
        }
        newChatButton.setOnLongClickListener {
            resetChatButton.performLongClick()
        }
        topWebSearchButton.setOnLongClickListener {
            showWebSearchEngineDialog()
            true
        }
        topReasoningButton.setOnLongClickListener {
            if(reasoningButton.isSelected)
            {
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    .hide(this)
                    .add(R.id.fragment_container, AdvancedReasoningFragment())
                    .addToBackStack(null)
                    .commit()
            }
            return@setOnLongClickListener true
        }

        openSavedChatsButton.setOnClickListener {
            hideKeyboard()
            if (codeMode.isActive) return@setOnClickListener codeMode.onMenuPressed()
            openHistoryPanel()
        }

        pdfChatButton.setOnClickListener {
            val messages = viewModel.chatMessages.value ?: emptyList()

            if (messages.isEmpty()) {
                AppToast.makeText(requireContext(), getString(R.string.toast_no_chat_export), AppToast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pdfChatButton.setIconResource(R.drawable.ic_check)
            Handler(Looper.getMainLooper()).postDelayed({
                hideMenu()
                pdfChatButton.setIconResource(R.drawable.ic_pdfnew)
            }, 500)

            lifecycleScope.launch {
                val modelIdentifier = viewModel.activeChatModel.value ?: "Unknown Model"
                val modelName = viewModel.getModelDisplayName(modelIdentifier)

                val filePath = withContext(Dispatchers.IO) {
                    try {
                        val messages = viewModel.chatMessages.value ?: emptyList()
                        val generatedImagesMap = mutableMapOf<Int, String>()
                        messages.forEachIndexed { index, message ->
                            if (message.role == "assistant" && !message.imageUri.isNullOrEmpty()) {
                                generatedImagesMap[index] = message.imageUri
                            }
                        }
                        when {
                            generatedImagesMap.isNotEmpty() -> {
                                pdfGenerator.generateStyledChatPdfWithGeneratedImages(requireContext(), messages, modelName, generatedImagesMap)
                            }
                            viewModel.hasImagesInChat() -> {
                                pdfGenerator.generateStyledChatPdfWithImages(requireContext(), messages, modelName)
                            }
                            else -> {
                                val markdownText = viewModel.getFormattedChatHistory()
                                pdfGenerator.generateStyledChatPdf(requireContext(), markdownText, modelName)
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                withContext(Dispatchers.Main) {
                    if (filePath != null) {
                        val rootView = requireView()
                        val context = rootView.context

                        // Disable StrictMode check for file:// URI
                        try {
                            val m = StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
                            m.invoke(null)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        // GradatiON workspace folder (read path may fall back to legacy folders)
                        val path = WorkspacePaths.workspaceDirForRead()

                        // Create intent to view the folder
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType("file://${path.absolutePath}".toUri(), "resource/folder")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        // Create the system chooser intent
                        val chooserIntent = Intent.createChooser(intent, getString(R.string.action_open_folder))

                        // Show Snackbar with the action
                        Snackbar.make(rootView, R.string.toast_pdf_saved, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_open_folder) {
                                context.startActivity(chooserIntent)
                            }
                            .show()
                    } else {
                        GlassNotice.show(requireContext(), getString(R.string.toast_pdf_failed))
                    }
                }
            }
        }
        chatEditText.setOnReceiveContentListener(
            arrayOf("text/*")
        ) { view, payload ->
            run {
                val text = payload.clip.getItemAt(0).text?.toString() ?: ""
                val editable = chatEditText.editableText
                val start = chatEditText.selectionStart
                val end = chatEditText.selectionEnd
                editable.replace(start, end, text)
                null
            }
        }

        copyChatButton.setOnLongClickListener {
            val chatText = viewModel.getFormattedChatHistory()  // Raw Markdown
            if (chatText.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Chat History (Markdown)", chatText)
                clipboard.setPrimaryClip(clip)
                AppToast.makeText(requireContext(), getString(R.string.toast_chat_copied_md), AppToast.LENGTH_SHORT).show()
                true  // Consume the long press
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_copy), AppToast.LENGTH_SHORT).show()
                true
            }
        }

        copyChatButton.setOnClickListener {
            val chatText = viewModel.getFormattedChatHistoryPlainText()  // Use the new plain-text function
            if (chatText.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Chat History", chatText)
                clipboard.setPrimaryClip(clip)
                AppToast.makeText(requireContext(), getString(R.string.toast_chat_copied), AppToast.LENGTH_SHORT).show()
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_copy), AppToast.LENGTH_SHORT).show()
            }
        }
        backButton.setOnLongClickListener {
            backButton.visibility = View.GONE
            backcopyButton.visibility = View.GONE
            updateHomeButtonVisibility()
            viewModel.startFreshChatForCurrentMode()
            chatEditText.setText("")
            chatEditText.text.clear()
            currentTempImageFile?.delete()
            currentTempImageFile = null
            previewImageView.setImageBitmap(null)
            // Add to reset logic
            pendingFiles.clear()
            updateAttachmentButton()
            chatAdapter.clearCache()
            activity?.moveTaskToBack(true) ?: false
            true
        }

        backcopyButton.setOnLongClickListener {
            copyLatestMessage()
            backButton.visibility = View.GONE
            backcopyButton.visibility = View.GONE
            updateHomeButtonVisibility()
            viewModel.startFreshChatForCurrentMode()
            chatEditText.setText("")
            chatEditText.text.clear()
            currentTempImageFile?.delete()
            currentTempImageFile = null
            previewImageView.setImageBitmap(null)
            // Add to reset logic
            pendingFiles.clear()
            updateAttachmentButton()
            chatAdapter.clearCache()
            activity?.moveTaskToBack(true) ?: false
            true
        }
        backcopyButton.setOnClickListener {
            copyLatestMessage()
            backButton.visibility = View.GONE
            backcopyButton.visibility = View.GONE
            updateHomeButtonVisibility()
            activity?.moveTaskToBack(true) ?: false
        }

        backButton.setOnClickListener {
            backButton.visibility = View.GONE
            backcopyButton.visibility = View.GONE
            updateHomeButtonVisibility()
            activity?.moveTaskToBack(true) ?: false
        }
        printButton.setOnClickListener {
            hideMenu()
            lifecycleScope.launch {
                val chatHtml = viewModel.getFormattedChatHistoryStyledHtml()
                if (chatHtml.isNotBlank()) {
                    printChatHtml(chatHtml)
                } else {
                    AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_print), AppToast.LENGTH_SHORT).show()
                }
            }
        }
        homeButton.setOnClickListener {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }

        saveMarkdownFileButton.setOnClickListener {
            hideMenu()
            val chatText = viewModel.getFormattedChatHistoryMarkdownandPrint()
            if (chatText.isNotBlank()) {
                viewModel.saveMarkdownToDownloads(chatText)
                // No need for local Toast - ViewModel handles UI event via _toolUiEvent
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_save), AppToast.LENGTH_SHORT).show()
            }
        }
        saveEpubButton.setOnClickListener {
            hideMenu()
            lifecycleScope.launch {
                // Reuse your existing HTML generation logic
                val innerHtml = viewModel.getFormattedChatHistoryEpubHtml()

                if (innerHtml.isNotBlank()) {
                    // Call the new ViewModel function
                    viewModel.saveEpubToDownloads(innerHtml)
                } else {
                    AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_save), AppToast.LENGTH_SHORT).show()
                }
            }
        }
        saveMarkdownFileButton.setOnLongClickListener {
            hideMenu()
            val chatText = viewModel.getFormattedChatHistoryTxt()
            if (chatText.isNotBlank()) {
                viewModel.saveTxtToDownloads(chatText)
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_save), AppToast.LENGTH_SHORT).show()
            }
            true  // Required for onLongClickListener
        }
        saveHtmlButton.setOnClickListener {
            hideMenu()
            lifecycleScope.launch {
                val innerHtml = viewModel.getFormattedChatHistoryStyledHtml()
                if (innerHtml.isNotBlank()) {
                    viewModel.saveHtmlToDownloads(innerHtml)
                    // VM handles success Toast via _toolUiEvent
                } else {
                    AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_save), AppToast.LENGTH_SHORT).show()
                }
            }
        }


        controlsButton.setOnClickListener {
            hideKeyboard()
            dismissAttachPopup()
            if (headerContainer.isVisible) hideMenu() else showMenu()
        }
        menuButton.setOnClickListener {
            hideKeyboard()
            showAttachSheet()
        }
        menuButton.setOnLongClickListener {
            val inputText = chatEditText.text.toString().trim()
            if (inputText.isBlank()) {
                AppToast.makeText(requireContext(), getString(R.string.toast_no_text_to_correct), AppToast.LENGTH_SHORT).show()
            } else if (viewModel.activeChatApiKey.isBlank()) {
                AppToast.makeText(requireContext(), getString(R.string.toast_api_key_missing), AppToast.LENGTH_SHORT).show()
            } else {
                menuButton.isSelected = true
                menuButton.setIconResource(R.drawable.ic_magic)

                lifecycleScope.launch {
                    val corrected = viewModel.correctText(inputText)
                    if (!corrected.isNullOrBlank()) {
                        chatEditText.setText(corrected)
                        chatEditText.setSelection(corrected.length)
                    } else {
                        AppToast.makeText(

                            requireContext(),
                            getString(R.string.toast_correction_failed),
                            AppToast.LENGTH_SHORT
                        ).show()
                    }
                    restoreAttachPlusIcon()
                }
            }
            true
        }

        settingsButton.setOnClickListener {
            hideMenu()
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, SettingsFragment())
                .addToBackStack("settings")
                .commit()
        }
        presetsButton.setOnClickListener {
            hideMenu()
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, PresetsListFragment())
                .addToBackStack(null)
                .commit()
        }
        presetsButton2.setOnClickListener {
            hideKeyboard()
            hideMenu()
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, PresetsListFragment())
                .addToBackStack(null)
                .commit()
        }
        presetsButton2.setOnLongClickListener {
            hideMenu()
            hideKeyboard()
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, PromptLibraryFragment())
                .addToBackStack(null)
                .commit()
            true
        }
        presetsButton.setOnLongClickListener {
            hideMenu()
            hideKeyboard()
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, PromptLibraryFragment())
                .addToBackStack(null)
                .commit()
            true
        }
        reasoningButton.setOnLongClickListener {
            if(reasoningButton.isSelected)
            {
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    .hide(this)
                    .add(R.id.fragment_container, AdvancedReasoningFragment())
                    .addToBackStack(null)
                    .commit()
            }
            return@setOnLongClickListener true
        }

        modelNameTextView.setOnLongClickListener {
            if (viewModel.isRpMode()) {
                hideKeyboard()
                openBotModelPicker()
                return@setOnLongClickListener true
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW, "https://openrouter.ai/models".toUri())
                startActivity(intent)
            } catch (e: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.toast_open_browser_failed))
            }
            true
        }

        streamButton.setOnClickListener {
            val currentModel = viewModel.activeChatModel.value ?: ""
            val isLyria = currentModel.contains("google/lyria", ignoreCase = true)
            val isStreamEnabled = viewModel.isStreamingEnabled.value ?: false

            if (isLyria && isStreamEnabled) {
                // Prevent turning off streaming for Lyria
                AppToast.makeText(requireContext(), getString(R.string.toast_streaming_required_lyria), AppToast.LENGTH_SHORT).show()
            } else {
                // Normal toggle for other models or if turning it ON for Lyria
                viewModel.toggleStreaming()
            }
        }

        reasoningButton.setOnClickListener {
            viewModel.toggleReasoning()
        }
        fontSizeButton.setOnClickListener {
            hideMenu()
            fontSizeControlsContainer.visibility = View.VISIBLE

        }
        fontsButton.setOnClickListener {
            hideMenu()

            val fontOptions = listOf(
                Pair("Plus Jakarta Sans", R.font.jakarta_regular as Int?),
                Pair("Inter", R.font.inter_regular),
                Pair("System Default", null),
            )

            fun fontNameFromRes(fontResId: Int?): String = when (fontResId) {
                null -> AppFonts.SYSTEM_DEFAULT
                R.font.inter_regular -> AppFonts.INTER
                else -> AppFonts.JAKARTA
            }

            val dialog = GlassAlertDialogBuilder(requireContext(), R.style.CustomMaterialAlertDialogTheme)
                .setTitle(R.string.select_font_title)
                .setNegativeButton(R.string.action_cancel, null)
                .create()

            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val textView = TextView(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(32, 16, 32, 16)
                        textSize = 18f
                        gravity = Gravity.CENTER
                        isClickable = true
                    }
                    return object : RecyclerView.ViewHolder(textView) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val (displayName, fontResId) = fontOptions[position]
                    val textView = holder.itemView as TextView
                    textView.text = displayName
                    textView.typeface = if (fontResId != null) {
                        ResourcesCompat.getFont(textView.context, fontResId)
                            ?: AppFonts.resolveSelectable(textView.context, AppFonts.JAKARTA)
                    } else {
                        Typeface.DEFAULT
                    }

                    val fontName = fontNameFromRes(fontResId)
                    val isSelected = fontName == sharedPreferencesHelper.getSelectedFont()
                    if (isSelected) {
                        textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.xai_mute))
                    } else {
                        textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.xai_ink))
                    }

                    textView.setOnClickListener {
                        sharedPreferencesHelper.saveSelectedFont(fontName)
                        val newTypeface = AppFonts.resolveSelectable(requireContext(), fontName)
                        chatEditText.typeface = newTypeface
                        modelNameTextView.typeface = Typeface.create(newTypeface, 600, false)
                        chatAdapter.updateFont(newTypeface)
                        dialog.dismiss()
                    }
                }

                override fun getItemCount() = fontOptions.size
            }

            val recyclerView = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
                this.adapter = adapter
            }
            dialog.window?.let { GlassDialogs.frost(it) }

            dialog.setView(recyclerView)
            dialog.show()

            val titleView = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            titleView?.paintFlags = titleView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }
        convoButton.setOnClickListener {
            val currentState = sharedPreferencesHelper.getConversationModeEnabled()
            val newState = !currentState
            sharedPreferencesHelper.saveConversationModeEnabled(newState)
            convoButton.isSelected = newState
        }
        systemMessageButton.setOnLongClickListener {
            val defaultMessage = SharedPreferencesHelper(requireContext()).getDefaultSystemMessage()
            SharedPreferencesHelper(requireContext()).saveSelectedSystemMessage(defaultMessage)
            systemMessageButton.isSelected = false
            updateChatEditTextHint()
            // AppToast.makeText(requireContext(), "System message reset to default", AppToast.LENGTH_SHORT).show()
            true
        }

        sendChatButton.setOnLongClickListener {
            val lastPos = chatAdapter.itemCount - 1
            if (lastPos >= 0) {
                layoutManager.scrollToPositionWithOffset(lastPos, -12)
            } else if (!viewModel.isRpMode()) {
                showMenu()
            }
            true
        }

        scrollToTopButton.setOnClickListener {
            chatRecyclerView.post {
                chatRecyclerView.scrollToPosition(0)
                updateScrollButtonsVisibility()
            }
        }

        scrollToBottomButton.setOnClickListener {
            chatRecyclerView.post {
                val layoutManager = chatRecyclerView.layoutManager as LinearLayoutManager
                val lastIndex = chatAdapter.itemCount - 1
                if (lastIndex >= 0) {
                    layoutManager.scrollToPositionWithOffset(lastIndex, -1000000)  // Bottom-align
                }
                updateScrollButtonsVisibility()
            }
        }

        scrollToTopButton.setOnLongClickListener {
            scrollToPreviousScreen()
            true  // Consume long press
        }

        scrollToBottomButton.setOnLongClickListener {
            scrollToNextScreen()
            true  // Consume long press
        }


        utilityButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text
                if (text != null) {
                    // Safe to paste as text
                    val start = chatEditText.selectionStart
                    val end = chatEditText.selectionEnd
                    chatEditText.text.replace(start, end, text.toString())
                } else {
                    // Clipboard item is not text (e.g., image, URI, etc.)
                    AppToast.makeText(requireContext(), getString(R.string.toast_clipboard_no_text), AppToast.LENGTH_SHORT).show()
                }
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_paste), AppToast.LENGTH_SHORT).show()
            }
        }

        utilityButton.setOnLongClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text
                if (text != null) {
                    // Safe to paste as text
                    val start = chatEditText.selectionStart
                    val end = chatEditText.selectionEnd
                    chatEditText.text.replace(start, end, text.toString())
                    sendChatButton.performClick()
                } else {
                    // Clipboard item is not text (e.g., image, URI, etc.)
                    AppToast.makeText(requireContext(), getString(R.string.toast_clipboard_no_text), AppToast.LENGTH_SHORT).show()
                }
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_nothing_to_paste), AppToast.LENGTH_SHORT).show()
            }
            true
        }
        dictation = VoiceDictation(
            fragment = this,
            input = chatEditText,
            micButton = speechButton,
            wave = requireView().findViewById(R.id.voiceWave),
            swapOut = listOf(modelNameTextView),
        ) { bytes, format, name -> viewModel.transcribeAudioForInput(bytes, format, name) }

        clearButton.setOnClickListener {
            chatEditText.text.clear()
        }
        clearButton.setOnLongClickListener {
            val textToCopy = chatEditText.text.toString()
            if (textToCopy.isNotEmpty()) {
                val clipboard = chatEditText.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Chat Message", textToCopy)
                clipboard.setPrimaryClip(clip)
                true // Return true to indicate we have consumed the long press event
            } else {
                false // Return false if text is empty so the system handles it normally
            }
        }
    }
    fun Fragment.hideKeyboard() {
        view?.let { activity?.hideKeyboard(it) }
    }

    fun Context.hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun hideKeyboardFrom(target: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun restoreAttachPlusIcon() {
        menuButton.setIconResource(R.drawable.ic_attach_plus)
        menuButton.isSelected = false
        menuButton.icon?.alpha = 255
    }
    private fun isTouchOutsideHeader(x: Float, y: Float): Boolean {
        val location = IntArray(2)
        headerContainer.getLocationOnScreen(location)
        val headerLeft = location[0].toFloat()
        val headerTop = location[1].toFloat()
        val headerRight = headerLeft + headerContainer.width
        val headerBottom = headerTop + headerContainer.height

        return x < headerLeft || x > headerRight || y < headerTop || y > headerBottom
    }
    private fun isTouchOnMenuButton(x: Float, y: Float): Boolean {
        val location = IntArray(2)
        menuButton.getLocationOnScreen(location)
        val buttonLeft = location[0].toFloat()
        val buttonTop = location[1].toFloat()
        val buttonRight = buttonLeft + menuButton.width
        val buttonBottom = buttonTop + menuButton.height

        // Return true if the touch is INSIDE the button's bounds
        return x >= buttonLeft && x <= buttonRight && y >= buttonTop && y <= buttonBottom
    }
    private fun updateInitialUI() {
        val isExtended = sharedPreferencesHelper.getExtPreference()
        if (sharedPreferencesHelper.getExtPreference2() && !viewModel.isRpMode()){
           // extBG.visibility = View.VISIBLE
            presetsButton2.visibility = View.VISIBLE
            presetsButton.visibility = View.GONE
        }
        convoButton.isSelected = sharedPreferencesHelper.getConversationModeEnabled()
      //  topConvoButton.isSelected = sharedPreferencesHelper.getConversationModeEnabled()
        utilityButton.visibility = if (isExtended) View.VISIBLE else View.GONE
        updateComposerAccessoryVisibility()
    }

    /** Mic is core Ask chrome; clear button stays gated by extended dock. */
    private fun updateComposerAccessoryVisibility() {
        if (!::speechButton.isInitialized || !::clearButton.isInitialized || !::chatEditText.isInitialized) return
        val isExtended = sharedPreferencesHelper.getExtPreference()
        val hasText = !chatEditText.text.isNullOrEmpty()
        clearButton.visibility = if (isExtended && hasText) View.VISIBLE else View.GONE
        dictation?.refresh()
    }

    private fun updateButtonVisibility() {
        updateComposerAccessoryVisibility()
    }
    private fun processAudioUri(uri: Uri) {
        if (discardAttachmentIfRp()) return
        lifecycleScope.launch {
            try {
                val mimeType = requireContext().contentResolver.getType(uri)
                val extension = uri.lastPathSegment?.substringAfterLast('.')?.lowercase() ?: "wav"

                // Validate audio format
                val supportedFormats = setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac", "opus")
                val formatFromMime = when {
                    mimeType == "audio/wav" || mimeType == "audio/x-wav" -> "wav"
                    mimeType == "audio/mpeg" || mimeType == "audio/mp3" -> "mp3"
                    mimeType == "audio/flac" -> "flac"
                    mimeType == "audio/mp4" || mimeType == "audio/x-m4a" -> "m4a"
                    mimeType == "audio/ogg" -> "ogg"
                    mimeType == "audio/webm" -> "webm"
                    mimeType == "audio/aac" -> "aac"
                    mimeType == "audio/opus" || mimeType == "application/ogg" -> "opus"
                    else -> extension
                }


                if (formatFromMime !in supportedFormats && extension !in supportedFormats) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_audio_unsupported))
                    return@launch
                }

                val audioFormat = if (formatFromMime in supportedFormats) formatFromMime else extension

                val bytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }

                if (bytes == null || bytes.size > 25_000_000) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_audio_too_large))
                    return@launch
                }
                if (discardAttachmentIfRp()) return@launch

                selectedAudioBytes = bytes
                selectedAudioFormat = audioFormat

                // Show audio attachment indicator
                previewImageView.setImageResource(android.R.drawable.ic_media_play) // or use a custom ic_audio
                attachmentPreviewContainer.visibility = View.VISIBLE
                AppToast.makeText(requireContext(), getString(R.string.toast_audio_attached), AppToast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.toast_audio_read_failed, e.message ?: ""))
            }
        }
    }
    // 🚀 synthesizeToWavFile (MINIMAL CHANGE - still queues with utteranceId)
    private fun synthesizeToWavFile(text: String, position: Int) {
        val safeText = text.take(3900)
        val context = requireContext()

        if (safeText.length < text.length) {
            AppToast.makeText(context, context.getString(R.string.toast_tts_text_truncated), AppToast.LENGTH_SHORT).show()
        }

        try {
            val timestamp = System.currentTimeMillis()
            val utteranceId = "TTS_SAVE_${timestamp}_${position}"
            val tempFile = File(context.cacheDir, "temp_tts_${timestamp}.wav")
            // val fileName = "TTS_${timestamp}_msg${position}.wav"  // For Toast tracking

            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }

            val result = textToSpeech.synthesizeToFile(
                safeText,
                params,
                tempFile,
                utteranceId
            )

            when (result) {
                TextToSpeech.SUCCESS -> {
                    AppToast.makeText(context, context.getString(R.string.toast_tts_audio_generating), AppToast.LENGTH_SHORT).show()
                }
                else -> {
                    AppToast.makeText(context, context.getString(R.string.toast_tts_wav_failed, result), AppToast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            AppToast.makeText(context, context.getString(R.string.toast_tts_queue_error, e.message ?: ""), AppToast.LENGTH_SHORT).show()
        }
    }

    private fun speakText(text: String, position: Int) {
        applyReadAloudVoice()
        if (isSpeaking) {
            if (position == currentSpeakingPosition) {
                // Stop current speech
                textToSpeech.stop()
                onSpeechFinished()
            } else {
                // Stop old and start new
                textToSpeech.stop()
                onSpeechFinished()
                // Start new
                isSpeaking = true
                currentSpeakingPosition = position
                chatAdapter.updateTtsState(isSpeaking, currentSpeakingPosition)
                // flashissue: Update icon directly if holder is attached, else notify
                updateIconDirectlyOrNotify(position, R.drawable.ic_stop_circle)
                val safeText = text.take(3900)
                if (safeText.length < text.length) {
                    AppToast.makeText(requireContext(), getString(R.string.toast_tts_text_truncated), AppToast.LENGTH_SHORT).show()
                }
                textToSpeech.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
            }
        } else {
            isSpeaking = true
            currentSpeakingPosition = position
            chatAdapter.updateTtsState(isSpeaking, currentSpeakingPosition)
            updateIconDirectlyOrNotify(position, R.drawable.ic_stop_circle)
            val safeText = text.take(3900)
            if (safeText.length < text.length) {
                AppToast.makeText(requireContext(), getString(R.string.toast_tts_text_truncated), AppToast.LENGTH_SHORT).show()
            }
            textToSpeech.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
        }
    }
    private fun showSaveFileDialog(content: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_file, null)
        val fileNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.fileNameInput)
        val fileExtensionInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.fileExtensionInput)

        // Smart extension detection from content
        val detectedExt = when {
            // === PRIORITY 1: Markdown code fences (explicit language tags) ===
            content.contains("```html", ignoreCase = true) ||
                    content.contains("```htm", ignoreCase = true) -> "html"

            content.contains("```kotlin", ignoreCase = true) ||
                    content.contains("```kt", ignoreCase = true) -> "kt"

            content.contains("```javascript", ignoreCase = true) ||
                    content.contains("```js", ignoreCase = true) -> "js"

            content.contains("```python", ignoreCase = true) ||
                    content.contains("```py", ignoreCase = true) -> "py"

            content.contains("```css", ignoreCase = true) -> "css"
            content.contains("```json", ignoreCase = true) -> "json"
            content.contains("```java", ignoreCase = true) -> "java"
            content.contains("```xml", ignoreCase = true) -> "xml"
            content.contains("```sql", ignoreCase = true) -> "sql"
            content.contains("```cpp", ignoreCase = true) ||
                    content.contains("```c++", ignoreCase = true) -> "cpp"

            // === PRIORITY 2: Content-based detection (plain text without fences) ===
            // HTML FIRST (before JS) because HTML files often contain <script> tags with const/let
            content.contains("<!DOCTYPE html>", ignoreCase = true) ||
                    content.contains("<html", ignoreCase = true) -> "html"

            // Kotlin
            content.contains(" fun ", ignoreCase = true) ||
                    (content.contains("class ", ignoreCase = true) && content.contains("{")) -> "kt"

            // JavaScript - only if NOT HTML (avoid matching const/let inside <script> tags)
            !content.contains("<html", ignoreCase = true) &&
                    (content.contains("const ", ignoreCase = true) || content.contains("let ", ignoreCase = true)) &&
                    content.contains("{") -> "js"

            // Python
            content.contains("def ", ignoreCase = true) ||
                    content.contains("import ", ignoreCase = true) && content.contains(":") -> "py"

            else -> "txt"
        }
        fileExtensionInput.setText(detectedExt)

        val dialog = GlassAlertDialogBuilder(requireContext(),
            R.style.CustomMaterialAlertDialogTheme
        )
            .setTitle(R.string.save_as_file_title)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val fileName = fileNameInput.text?.toString()?.trim() ?: ""
                val extension = fileExtensionInput.text?.toString()?.trim() ?: ""

                if (fileName.isNotEmpty() && extension.isNotEmpty()) {
                    viewModel.saveFileWithName(fileName, extension, content)
                } else {
                    AppToast.makeText(requireContext(), getString(R.string.toast_filename_extension_required), AppToast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()

        // Apply dim amount like your other dialog
        dialog.window?.let { GlassDialogs.frost(it) }

        // Optional: Make the Save button disabled until text is entered
        val saveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        fileNameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveButton.isEnabled = !s.isNullOrBlank()
            }
        })
        saveButton.isEnabled = false
    }
    private fun updateIconDirectlyOrNotify(position: Int, @DrawableRes iconRes: Int) {
        val lm = chatRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val vh = lm.findViewByHolder(position)          // extension below
        if (vh is ChatAdapter.AssistantViewHolder && vh.itemView.isAttachedToWindow) {
            vh.ttsButton.setImageResource(iconRes)      // fast path – no flash
        } else {
            chatAdapter.notifyItemChanged(position)     // slow path
        }
    }

    /* helper – returns the ViewHolder that is *currently* bound to the given adapter position */
    private fun LinearLayoutManager.findViewByHolder(pos: Int): RecyclerView.ViewHolder? {
        return findViewByPosition(pos)?.let { chatRecyclerView.getChildViewHolder(it) }
    }

    private fun onSpeechFinished() {
        isSpeaking = false
        val pos = currentSpeakingPosition
        currentSpeakingPosition = -1
        chatAdapter.updateTtsState(isSpeaking, currentSpeakingPosition)
        if (pos != -1) {
            // flashissue: Update icon directly if holder is attached, else notify
            updateIconDirectlyOrNotify(pos, R.drawable.ic_volume_up)
        }
    }
    override fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val hasMessages = (viewModel.chatMessages.value?.size ?: 0) > 0
                if (isSpeaking) {
                    return false // Let the system handle Volume Up
                }
                if (doVolScroll && hasMessages) {
                    when {
                        event?.isLongPress == true -> {
                            scrollToTopButton.performClick()
                            scrollToPreviousScreen()
                        }
                        event?.repeatCount == 0 -> {
                            scrollToPreviousScreen()
                            // Find what's at the top now
                            /*  val firstPos = layoutManager.findFirstVisibleItemPosition()
                              if (firstPos > 0) {
                                  // Jump to the message exactly above the current top one
                                  layoutManager.scrollToPositionWithOffset(firstPos - 1, -12)
                              }*/
                        }
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val messages = viewModel.chatMessages.value ?: emptyList()
                if (isSpeaking) {
                    return false // Let the system handle Volume Down
                }
                if (doVolScroll && messages.isNotEmpty()) {
                    when {
                        event?.isLongPress == true -> {
                            scrollToBottomButton.performClick()
                            scrollToNextScreen()
                        }
                        event?.repeatCount == 0 -> {
                            scrollToNextScreen()
                            // Find what's at the top now
                            /*  val firstPos = layoutManager.findFirstVisibleItemPosition()
                              if (firstPos < messages.size - 1) {
                                  // Jump so the next message in the list becomes the new top message
                                  layoutManager.scrollToPositionWithOffset(firstPos + 1, -12)
                              }*/
                        }
                    }
                    return true
                }
            }

            // Add more fragment-specific shortcuts here
        }
        return false // Event not handled by this fragment
    }
    private var effortGroup: GlassSegmentedGroup? = null
    private var quickControlsBinding = false

    /** Controls sheet top: model row, reasoning effort, and a "More controls" fold for the grid. */
    private fun setupQuickControls(view: View) {
        view.findViewById<View>(R.id.controlsModelRow).setOnClickListener {
            hideMenu()
            hideKeyboard()
            showModelPopover()
        }
        val group = view.findViewById<GlassSegmentedGroup>(R.id.controlsEffortGroup)
        effortGroup = group
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || quickControlsBinding) return@addOnButtonCheckedListener
            val on = viewModel.isReasoningEnabled.value == true
            when (checkedId) {
                R.id.effortOff -> if (on) viewModel.toggleReasoning()
                R.id.effortAuto -> {
                    if (!on) viewModel.toggleReasoning()
                    sharedPreferencesHelper.saveAdvancedReasoningEnabled(false)
                }
                else -> {
                    if (!on) viewModel.toggleReasoning()
                    sharedPreferencesHelper.saveAdvancedReasoningEnabled(true)
                    // An explicit effort wins over a token budget from Advanced Reasoning.
                    sharedPreferencesHelper.saveReasoningMaxTokens(null)
                    sharedPreferencesHelper.saveReasoningEffort(
                        when (checkedId) {
                            R.id.effortLow -> "low"
                            R.id.effortHigh -> "high"
                            else -> "medium"
                        }
                    )
                }
            }
            group.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            viewModel.checkAdvancedReasoningStatus()
            updateQuickControls()
        }
        // Long-press the Reasoning header for the full Advanced Reasoning screen.
        view.findViewById<View>(R.id.controlsEffortLabel).setOnLongClickListener {
            hideMenu()
            parentFragmentManager.beginTransaction()
                .withGrokStackAnimations()
                .hide(this)
                .add(R.id.fragment_container, AdvancedReasoningFragment())
                .addToBackStack(null)
                .commit()
            true
        }
        val more = view.findViewById<View>(R.id.controlsMoreRow)
        val chevron = view.findViewById<View>(R.id.controlsMoreChevron)
        val label = (more as ViewGroup).getChildAt(0) as TextView
        more.setOnClickListener {
            val open = !buttonsContainer.isVisible
            more.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            label.setText(if (open) R.string.controls_less else R.string.controls_more)
            val anim = Motion.areAnimationsEnabled(requireContext())
            chevron.animate().rotation(if (open) 180f else 0f).setDuration(if (anim) 260 else 0)
                .setInterpolator(Motion.iosOut).start()
            if (anim) {
                android.transition.TransitionManager.beginDelayedTransition(
                    headerContainer as ViewGroup,
                    android.transition.ChangeBounds().setDuration(320).setInterpolator(Motion.iosOut)
                )
            }
            buttonsContainer.isVisible = open
            if (open) updateMenuRowVisibilities()
        }
    }

    /** Mirror model + reasoning state into the sheet's quick controls. */
    private fun updateQuickControls() {
        val group = effortGroup ?: return
        val root = view ?: return
        val model = viewModel.activeChatModel.value
        root.findViewById<TextView>(R.id.controlsModelName).text =
            model?.let { viewModel.getModelDisplayName(it) } ?: ""
        val supported = viewModel.isReasoningModel(model)
        val on = viewModel.isReasoningEnabled.value == true
        val budget = sharedPreferencesHelper.getReasoningMaxTokens()?.takeIf { it > 0 } != null
        val target = when {
            !on -> R.id.effortOff
            !sharedPreferencesHelper.getAdvancedReasoningEnabled() || budget -> R.id.effortAuto
            else -> when (sharedPreferencesHelper.getReasoningEffort()) {
                "minimal", "low" -> R.id.effortLow
                "high" -> R.id.effortHigh
                else -> R.id.effortMedium
            }
        }
        quickControlsBinding = true
        if (group.checkedButtonId != target) group.check(target)
        quickControlsBinding = false
        group.isEnabled = supported
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = supported
        group.alpha = if (supported) 1f else 0.45f
        root.findViewById<TextView>(R.id.controlsEffortHint).text = when {
            !supported -> getString(R.string.controls_effort_unsupported)
            on && budget && sharedPreferencesHelper.getAdvancedReasoningEnabled() -> getString(R.string.controls_effort_budget)
            else -> ""
        }
    }

    private fun showMenu() {
        updateQuickControls()
        val anim = Motion.areAnimationsEnabled(requireContext())
        val d = resources.displayMetrics.density
        controlsButton.isSelected = true
        controlsButton.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        overlayView?.visibility = View.VISIBLE
        headerContainer.animate().cancel()
        headerContainer.apply {
            visibility = View.VISIBLE
            if (anim) {
                // Control Center: rises from the composer with a spring, rows settle in behind it.
                pivotX = width.takeIf { it > 0 }?.div(2f) ?: (resources.displayMetrics.widthPixels / 2f)
                pivotY = height.takeIf { it > 0 }?.toFloat() ?: (300f * d)
                alpha = 0f
                scaleX = 0.94f
                scaleY = 0.94f
                translationY = 28f * d
                animate().alpha(1f).setDuration(160).setInterpolator(Motion.iosOut).start()
                animate().scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(520).setInterpolator(Motion.spring).start()
                staggerPanelRows(d)
            } else {
                alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f
            }
        }
        (chatFrameView as ViewGroup).bringChildToFront(headerContainer)
        dimOverlay?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(0.6f).setDuration(260).setInterpolator(Motion.iosOut).start()
        }
        animateTopBarDim(0.6f, 260)
        // Fade empty-state (mark + prompt) while menu is open
        if (emptyStateContainer.isVisible) {
            emptyStateContainer.visibility = View.GONE
        }
    }

    /** Rows of the Controls panel follow the panel in with a slight cascade. */
    private fun staggerPanelRows(d: Float) {
        val rows = listOfNotNull(
            headerContainer.findViewById<ViewGroup>(R.id.controlsQuick),
            headerContainer.findViewById<ViewGroup>(R.id.buttonsContainer)?.takeIf { it.isVisible }
        ).flatMap { c -> (0 until c.childCount).map { c.getChildAt(it) } }
        var index = 0
        for (row in rows) {
            if (row.visibility != View.VISIBLE) continue
            row.animate().cancel()
            row.alpha = 0f
            row.translationY = 14f * d
            row.animate().alpha(1f).translationY(0f)
                .setStartDelay(40L + index * 28L)
                .setDuration(420).setInterpolator(Motion.spring).start()
            index++
        }
    }

    private var topBarDimAnimator: android.animation.ValueAnimator? = null

    /** The dim that sits over the transcript also washes over the glass top bar. */
    private fun animateTopBarDim(target: Float, durationMs: Long) {
        val bar = view?.findViewById<View>(R.id.topBarGlass) ?: return
        val scrim = (bar.foreground as? android.graphics.drawable.ColorDrawable)
            ?: android.graphics.drawable.ColorDrawable(ContextCompat.getColor(requireContext(), R.color.xai_scrim))
                .also { it.alpha = 0; bar.foreground = it }
        topBarDimAnimator?.cancel()
        val from = scrim.alpha / 255f
        val to = target.coerceIn(0f, 1f) * Color.alpha(ContextCompat.getColor(requireContext(), R.color.xai_scrim)) / 255f
        topBarDimAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (Motion.areAnimationsEnabled(requireContext())) durationMs else 0L
            interpolator = Motion.iosOut
            addUpdateListener { scrim.alpha = ((it.animatedValue as Float) * 255).toInt() }
            start()
        }
    }

    private var attachPlusOpen = false

    private fun showAttachSheet() {
        if (viewModel.isRpMode()) {
            showRpPlusPopover()
            return
        }
        if (pickerPopover?.isShowing == true) {
            pickerPopover?.dismiss()
            return
        }
        val rows = ArrayList<PickerPopover.Row>()
        rows += PickerPopover.Row(getString(R.string.grok_attach_camera), getString(R.string.attach_camera_sub), R.drawable.ic_camera) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchCamera()
            }
        }
        rows += PickerPopover.Row(getString(R.string.grok_attach_gallery), getString(R.string.attach_gallery_sub), R.drawable.ic_gallery) {
            menuButton.post { launchGalleryPicker() }
        }
        rows += PickerPopover.Row(getString(R.string.grok_attach_files), getString(R.string.attach_files_sub), R.drawable.ic_attachdoc) {
            textFilePicker.launch("*/*")
        }
        if (pendingFiles.isNotEmpty()) {
            rows += PickerPopover.Row(
                resources.getQuantityString(R.plurals.attach_review_files, pendingFiles.size, pendingFiles.size),
                null, R.drawable.ic_check_round
            ) { showAttachedFiles() }
        }
        val footer = listOf(
            PickerPopover.Row(getString(R.string.settings_manage_tools), getString(R.string.attach_tools_sub), R.drawable.ic_tools) {
                parentFragmentManager.beginTransaction()
                    .withGrokStackAnimations()
                    .hide(this)
                    .add(R.id.fragment_container, ToolsFragment())
                    .addToBackStack(null)
                    .commit()
            }
        )
        setAttachPlusOpen(true)
        newPopover(menuButton) { open -> if (!open) setAttachPlusOpen(false) }?.show(null, rows, footer)
    }

    /** Roleplay "+": scene tools instead of attachments (files are off in RP by design). */
    private fun showRpPlusPopover() {
        if (pickerPopover?.isShowing == true) {
            pickerPopover?.dismiss()
            return
        }
        val rows = ArrayList<PickerPopover.Row>()
        val noCharacter = viewModel.activeRpCharacter.value == null && !sharedPreferencesHelper.isRpLlmMode()
        if (noCharacter) {
            rows += PickerPopover.Row(getString(R.string.rp_empty_choose), getString(R.string.rp_plus_choose_sub), R.drawable.ic_nav_characters) {
                menuButton.post { showCharacterPopover() }
            }
        }
        rows += PickerPopover.Row(getString(R.string.rp_plus_reminder), getString(R.string.rp_plus_reminder_sub), R.drawable.ic_nav_prompts) {
            insertRpReminderTemplate()
        }
        val streaming = streamButton.isSelected
        rows += PickerPopover.Row(
            getString(R.string.rp_plus_stream),
            getString(if (streaming) R.string.rp_plus_stream_on else R.string.rp_plus_stream_off),
            R.drawable.ic_stream,
            selected = streaming
        ) { streamButton.performClick() }
        val footer = listOf(
            PickerPopover.Row(getString(R.string.drawer_nav_roleplay), getString(R.string.rp_plus_home_sub), R.drawable.ic_nav_characters) { openRpHub() }
        )
        setAttachPlusOpen(true)
        newPopover(menuButton) { open -> if (!open) setAttachPlusOpen(false) }?.show(
            if (noCharacter) getString(R.string.rp_plus_title_empty) else null, rows, footer
        )
    }

    private fun dismissAttachPopup() {
        pickerPopover?.dismiss()
        setAttachPlusOpen(false)
    }

    private fun launchGalleryPicker() {
        if (!isAdded) return
        try {
            galleryPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: ActivityNotFoundException) {
            legacyGalleryPicker.launch(arrayOf("image/*"))
        }
    }

    /** Late Ask picker results must not stage media after a flip to RP. */
    private fun discardAttachmentIfRp(): Boolean {
        if (!viewModel.isRpMode()) return false
        GlassNotice.show(requireContext(), getString(R.string.rp_attachments_disabled))
        return true
    }

    private fun processPickedImageUri(uri: Uri) {
        if (discardAttachmentIfRp()) return
        val model = viewModel.activeChatModel.value
        if (model != null && !viewModel.isVisionModel(model)) {
            AppToast.makeText(
                requireContext(),
                getString(R.string.toast_image_need_vision),
                AppToast.LENGTH_SHORT
            ).show()
        }
        val resolver = requireContext().applicationContext.contentResolver
        val mime = resolver.getType(uri)
        if (mime !in setOf("image/jpeg", "image/png", "image/webp")) {
            GlassNotice.show(requireContext(), getString(R.string.toast_unsupported_image_format))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Read off the main thread, capped so a huge file can't balloon memory.
            val maxBytes = 12_000_000
            val bytes: ByteArray? = withContext(Dispatchers.IO) {
                try {
                    resolver.openInputStream(uri)?.use { stream ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > maxBytes) return@use ByteArray(maxBytes + 1)
                        }
                        buf.toByteArray()
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (!isAdded) return@launch
            if (bytes == null) {
                GlassNotice.show(requireContext(), getString(R.string.toast_failed_read_image))
                return@launch
            }
            if (bytes.size > maxBytes) {
                GlassNotice.show(requireContext(), getString(R.string.toast_image_too_large))
                return@launch
            }
            if (discardAttachmentIfRp()) return@launch
            selectedImageBytes = bytes
            selectedImageMime = mime
            previewImageView.setImageURI(uri)
            attachmentPreviewContainer.visibility = View.VISIBLE
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                resolver.takePersistableUriPermission(uri, takeFlags)
                viewModel.setPendingUserImageUri(uri.toString())
            } catch (_: SecurityException) {
                viewModel.setPendingUserImageUri(uri.toString())
            }
        }
    }

    private fun setAttachPlusOpen(open: Boolean) {
        if (attachPlusOpen == open) return
        attachPlusOpen = open
        val target = if (open) 45f else 0f
        menuButton.animate()
            .rotation(target)
            .setDuration(resources.getInteger(R.integer.motion_menu).toLong())
            .setInterpolator(Motion.easeOut)
            .start()
    }

    private fun hideMenu() {
        val menuMs = resources.getInteger(R.integer.motion_menu).toLong()
        val d = resources.displayMetrics.density
        controlsButton.isSelected = false
        dimOverlay?.animate()?.cancel()
        headerContainer.animate().cancel()
        dimOverlay?.animate()?.alpha(0f)?.setDuration(menuMs)?.setInterpolator(Motion.iosIn)?.withEndAction {
            dimOverlay?.visibility = View.GONE
        }?.start()
        if (headerContainer.visibility == View.VISIBLE) animateTopBarDim(0f, menuMs)
        headerContainer.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f)
            .translationY(maxOf(headerContainer.translationY, 14f * d))
            .setDuration(menuMs - 20).setInterpolator(Motion.iosIn).withEndAction {
                headerContainer.visibility = View.GONE
                headerContainer.scaleX = 1f
                headerContainer.scaleY = 1f
                headerContainer.translationY = 0f
                overlayView?.visibility = View.GONE
            }.start()

        // Only restore empty state if the chat is empty
        val hasMessages = !(viewModel.chatMessages.value.isNullOrEmpty())
        if (!hasMessages) {
            emptyStateContainer.alpha = 0f
            emptyStateContainer.visibility = View.VISIBLE
            bindEmptyState(viewModel.isRpMode())
            emptyStateContainer.animate().alpha(1f).setDuration(menuMs).setInterpolator(Motion.easeOut).start()
        }
    }

    private fun scrollToPreviousScreen() {
        chatRecyclerView.post {
            val height = chatRecyclerView.height
            chatRecyclerView.smoothScrollBy(0, -height)
            updateScrollButtonsVisibility()
            //AppToast.makeText(requireContext(), "Scrolled up one screen", AppToast.LENGTH_SHORT).show()
        }
    }

    private fun scrollToNextScreen() {
        chatRecyclerView.post {
            val height = chatRecyclerView.height
            chatRecyclerView.smoothScrollBy(0, height)
            updateScrollButtonsVisibility()
        }
    }

    private fun updateScrollButtonsVisibility() {
        if (!sharedPreferencesHelper.getScrollersPreference()) return
        chatRecyclerView.post {
            val canScrollUp = chatRecyclerView.canScrollVertically(-1)
            val canScrollDown = chatRecyclerView.canScrollVertically(1)
            scrollToTopButton.setShownAnimated(canScrollUp)
            scrollToBottomButton.setShownAnimated(canScrollDown)
        }
    }

    private fun formatModelName(modelString: String): String {
        return modelString.substringAfterLast("/")
            .substringBefore("@")
            .substringBefore(":")
    }
    private fun setupTextFilePicker() {
        attachmentButton.setOnClickListener {
            if (viewModel.isRpMode()) {
                GlassNotice.show(requireContext(), getString(R.string.rp_attachments_disabled))
                return@setOnClickListener
            }
            // Launch multi-picker with primary MIME (broadens to text-like; client-side filters the rest)
            val mimeType = "*/*"  // Or "text/plain" for stricter start; fallback handles .kt etc.
            textFilePicker.launch(mimeType)
        }

        attachmentButton.setOnLongClickListener {
            if (viewModel.isRpMode()) {
                GlassNotice.show(requireContext(), getString(R.string.rp_attachments_disabled))
                return@setOnLongClickListener true
            }
            showAttachedFiles()
            true
        }
    }

    private fun setupPlusButtonListener() {
        plusButton.setOnClickListener {
            hideKeyboard()
            val model = viewModel.activeChatModel.value
            if (model != null && viewModel.isTranscriptionModel(model)) {
                audioPicker.launch(arrayOf("audio/*"))
                return@setOnClickListener
            }
            if (model == null || !viewModel.isVisionModel(model)) {
                GlassNotice.show(requireContext(), getString(R.string.toast_image_pdf_not_supported))
                return@setOnClickListener
            }

            // NEW: Check if vision model supports PDF (all do, but future-proof)
            val supportsPdf = true // Or add model check if needed

            val items = mutableListOf("Take a Photo", "Choose from Gallery")
            if (supportsPdf) items.add("Choose PDF") // NEW: Add PDF option

            GlassAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_load_image_or_pdf)
                .setItems(items.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> { // Take Photo
                            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                launchCamera()
                            }
                        }
                        1 -> menuButton.post { launchGalleryPicker() }
                        2 -> { // Choose PDF
                            pdfPicker.launch(arrayOf("application/pdf"))  // NEW: Launches document picker with PDF filter
                        }

                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        // In setupPlusButtonListener(), after the existing setOnClickListener block
        plusButton.setOnLongClickListener {
            val model = viewModel.activeChatModel.value
            if (viewModel.isTranscriptionModel(model)) {
                audioPicker.launch(arrayOf("audio/*"))
                return@setOnLongClickListener true
            }
            if (model == null || !viewModel.isVisionModel(model)) {
                GlassNotice.show(requireContext(), getString(R.string.toast_image_not_supported))
                return@setOnLongClickListener false
            }

            // Direct camera launch on long-click
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchCamera()
            }
            true  // Consume long-click
        }

    }

    private fun startSpeechRecognition() {
        // STT disabled
        /*
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            GlassNotice.show(requireContext(), getString(R.string.toast_speech_not_supported))
        }
        */
    }
    private fun startForegroundService() {
        try {
            val serviceIntent = Intent(requireContext(), ForegroundService::class.java)
            val displayName = viewModel.getModelDisplayName(viewModel.activeChatModel.value ?: "Unknown Model")
            serviceIntent.putExtra("initial_title", displayName)
            requireContext().startService(serviceIntent)
        } catch (e: Exception) {
            // silently ignore — foreground service start is best-effort
        }
    }

    private fun stopForegroundService() {
        try {
            ForegroundService.stopService()
        } catch (e: Exception) {
        }
    }
    private fun updateModelSourceIndicator() {
        if (viewModel.isRpMode()) {
            // RP chrome owns the chip label + a11y (character / LLM / Characters).
            return
        }
        val isLan = viewModel.activeModelIsLan()
        val description = if (isLan) getString(R.string.a11y_lan_model) else getString(R.string.a11y_cloud_model)
        val chevronColor = ContextCompat.getColor(requireContext(), R.color.xai_body)
        modelNameTextView.compoundDrawableTintList = ColorStateList.valueOf(chevronColor)
        // No leading source glyph — Grok chip is text + chevron only
        modelNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            R.drawable.ic_expand_more,
            0
        )
        modelNameTextView.contentDescription = description
    }
    private fun checkLocalNetworkPermission() {
        // Android 17 (API 37) requires explicit local network permission
        if (Build.VERSION.SDK_INT >= 37) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    "android.permission.ACCESS_LOCAL_NETWORK"
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Request the permission
                localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            }
        }
        // If permission is already granted, or Android < 17, do nothing (let it proceed)
    }
    private fun updateStreamToggleAppearance(isEnabled: Boolean) {
        streamButton.isSelected = isEnabled
        topStreamButton.isSelected = isEnabled
        if (::rpStreamButton.isInitialized) {
            rpStreamButton.isSelected = isEnabled
        }
    }

    private fun updateReasoningButtonAppearance() {
        // Use .value to get the current state from LiveData
        val isReasoningOn = viewModel.isReasoningEnabled.value ?: false
        val isAdvancedOn = viewModel.isAdvancedReasoningOn.value ?: false


        if (isReasoningOn && isAdvancedOn) {
            // STATE: Advanced Reasoning is ON. Add the outline.
            val strokeColor = ContextCompat.getColor(requireContext(), R.color.ora)
            val strokeWidth = resources.getDimensionPixelSize(R.dimen.advanced_reasoning_outline_width)

            reasoningButton.strokeColor = ColorStateList.valueOf(strokeColor)
            reasoningButton.strokeWidth = strokeWidth
            topReasoningButton.strokeColor = ColorStateList.valueOf(strokeColor)
            topReasoningButton.strokeWidth = strokeWidth
        } else {
            // STATE: Normal or OFF. Remove the outline by setting its width to 0.
            reasoningButton.strokeWidth = 0
            topReasoningButton.strokeWidth = 0
        }
    }
    /** Roleplay and Code tabs follow Settings > Modes; leaving a disabled Roleplay lands on Chat. */
    private fun refreshModeTabs() {
        if (!::tabRoleplay.isInitialized) return
        val rpOn = sharedPreferencesHelper.isRoleplayEnabled()
        tabRoleplay.isVisible = rpOn
        if (!rpOn && viewModel.isRpMode() && viewModel.isAwaitingResponse.value != true) {
            sharedPreferencesHelper.saveComposerDraft(ChatMode.RP, chatEditText.text?.toString().orEmpty())
            viewModel.toggleChatMode()
        }
        if (::codeMode.isInitialized) codeMode.refresh()
        modeTabIndicator.post { placeModeTabIndicator(animate = false) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) pickerPopover?.dismiss(animated = false)
        if (!hidden) {  // Fragment is now visible
            refreshModeTabs()
            settleSendButton()
            applyChatTextScale()
            updateSystemMessageButtonState()
           // chatEditText.requestFocus()
            viewModel.checkAdvancedReasoningStatus()
            convoButton.isSelected = sharedPreferencesHelper.getConversationModeEnabled()
          //  topConvoButton.isSelected = sharedPreferencesHelper.getConversationModeEnabled()
        }
    }
    override fun onStop() {
        super.onStop()
        backButton.visibility = View.GONE
        backcopyButton.visibility = View.GONE
        updateHomeButtonVisibility()
    }
    override fun onResume() {
        super.onResume()
        applyChatTextScale()
        refreshModeTabs()
        dictation?.refresh()
        settleSendButton()
        updateSystemMessageButtonState()
        viewModel.isStreamingEnabled.value?.let { updateStreamToggleAppearance(it) }
       // chatEditText.requestFocus()
        viewModel.checkAdvancedReasoningStatus()
       // val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        //notificationManager.cancel(2)
        ForegroundService.dismissNotificationIfNotSpeaking(requireContext())

        /*if (viewModel.isChatLoading.value == false) {
            if (viewModel.chatMessages.value.isNullOrEmpty()) {
                chatEditText.post {
                    chatEditText.showKeyboard()
                }
            } else {
                chatEditText.post {
                    chatEditText.hideKeyboard()
                }
            }
        }*/
    }


    private fun cancelDrawerAnimation() {
        historyDrawerContainer?.animate()?.cancel()
        historyDrawerScrim?.animate()?.cancel()
    }

    /**
     * Wide, deliberate swipes across the whole chat, read as pages
     * History | Chat | Roleplay | Models: finger right goes one page left, finger left one page
     * right. Inside the open history, a leftward swipe drags it closed.
     */
    // ── Mode pager ────────────────────────────────────────────────────────────────────────
    // Chat, Roleplay and Code share one set of views, so the neighbour page can't be laid out
    // beside the current one. Instead: snapshot the current page, switch to the next mode
    // behind the snapshot, and slide the two side by side. Taps and swipes both use it.

    private class Pager(val target: TextView, val origin: TextView, val direction: Int, val shot: ImageView, val w: Float)

    private var pager: Pager? = null
    private var pagerOrigin: TextView? = null
    /** True from the first drag frame until the pages settle; the pager owns the underline. */
    private var pagerBusy = false

    private fun modeTabsInOrder(): List<TextView> = listOfNotNull(
        tabChat,
        tabRoleplay.takeIf { it.isVisible },
        codeMode.tab.takeIf { it.isVisible }
    )

    private fun currentTab(): TextView = when {
        codeMode.isActive -> codeMode.tab
        viewModel.isRpMode() && tabRoleplay.isVisible -> tabRoleplay
        else -> tabChat
    }

    /** The tab a drag in [direction] leads to (+1 = finger moving right = the tab to the left). */
    private fun targetFrom(origin: TextView, direction: Int): TextView? {
        val order = modeTabsInOrder()
        val i = order.indexOf(origin).coerceAtLeast(0)
        return order.getOrNull(if (direction > 0) i - 1 else i + 1)
    }

    /** The page layers that slide; the top bar and its tabs stay put. */
    private fun modePages(): List<View> {
        val root = view ?: return emptyList()
        return listOfNotNull(
            root.findViewById(R.id.chatFrameView),
            root.findViewById(R.id.composerDock),
            root.findViewById(R.id.composerFade),
            root.findViewById(R.id.codeModeContainer)
        ).filter { it.isVisible }
    }

    /** Switch now, no animation. False when blocked (a reply is still streaming). */
    private fun switchToTab(tab: TextView): Boolean {
        if (tab == codeMode.tab) {
            if (!codeMode.isActive) { hideKeyboard(); codeMode.activate(animate = false) }
            return true
        }
        val target = if (tab == tabRoleplay) ChatMode.RP else ChatMode.ASK
        val current = if (viewModel.isRpMode()) ChatMode.RP else ChatMode.ASK
        if (target != current && viewModel.isAwaitingResponse.value == true) {
            GlassNotice.show(requireContext(), getString(R.string.rp_wait_for_reply))
            return false
        }
        codeMode.deactivate()
        if (target != current) {
            sharedPreferencesHelper.saveComposerDraft(current, chatEditText.text?.toString().orEmpty())
            viewModel.toggleChatMode()
        }
        return true
    }

    private fun openPager(target: TextView, direction: Int): Boolean {
        val root = view ?: return false
        val content = root.findViewById<FrameLayout>(R.id.rootLayout)
        val topBar = root.findViewById<View>(R.id.topBarGlass)
        if (content.width == 0) return false
        val origin = currentTab()
        pagerBusy = true
        modePages().forEach { it.animate().cancel(); it.translationX = 0f; it.alpha = 1f }
        // The top bar stays on screen above both pages, so leave it out of the picture.
        val bar = topBar.visibility
        topBar.visibility = View.INVISIBLE
        val bmp = runCatching { content.drawToBitmap(Bitmap.Config.ARGB_8888) }.getOrNull()
        topBar.visibility = bar
        if (bmp == null) { pagerBusy = false; return false }
        val shot = ImageView(requireContext()).apply {
            setImageBitmap(bmp)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(shot, content.indexOfChild(topBar),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Lay it out now: it has to cover the page on this very frame, before the switch shows.
        shot.measure(
            View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(content.height, View.MeasureSpec.EXACTLY)
        )
        shot.layout(0, 0, content.width, content.height)
        if (!switchToTab(target)) {
            content.removeView(shot)
            bmp.recycle()
            pagerBusy = false
            return false
        }
        target.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        pager = Pager(target, origin, direction, shot, content.width.toFloat())
        movePager(0f)
        return true
    }

    /** Old page (the snapshot) under the finger, the new one right beside it. */
    private fun movePager(dx: Float) {
        val p = pager ?: return
        val w = p.w
        p.shot.translationX = dx
        modePages().forEach { it.animate().cancel(); it.translationX = dx - p.direction * w; it.alpha = 1f }
        val from = indicatorXFor(p.origin)
        val to = indicatorXFor(p.target)
        if (from != null && to != null) {
            val t = (abs(dx) / w).coerceIn(0f, 1f)
            modeTabIndicator.animate().cancel()
            modeTabIndicator.translationX = from + (to - from) * t
        }
    }

    /** Finish the slide either way; on cancel the origin mode comes back behind the snapshot. */
    private fun settlePager(commit: Boolean) {
        val p = pager ?: return
        pager = null
        val w = p.w
        val anim = Motion.areAnimationsEnabled(requireContext())
        val shotTo = if (commit) p.direction * w else 0f
        val pageTo = if (commit) 0f else -p.direction * w
        val lineTo = indicatorXFor(if (commit) p.target else p.origin)
        val done = {
            if (!commit) switchToTab(p.origin)
            modePages().forEach { it.translationX = 0f; it.alpha = 1f }
            // Give an async mode reload a beat to draw before the snapshot goes.
            p.shot.postDelayed({
                (p.shot.parent as? ViewGroup)?.removeView(p.shot)
                (p.shot.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
                p.shot.setImageDrawable(null)
            }, if (commit) 0L else 120L)
            pagerBusy = false
            pagerOrigin = null
            placeModeTabIndicator(animate = false)
        }
        if (!anim) { done(); return }
        val duration = 300L
        p.shot.animate().translationX(shotTo).setDuration(duration).setInterpolator(Motion.iosOut)
            .withEndAction { done() }.start()
        modePages().forEach { it.animate().translationX(pageTo).setDuration(duration).setInterpolator(Motion.iosOut).start() }
        if (lineTo != null) {
            modeTabIndicator.animate().translationX(lineTo).setDuration(duration).setInterpolator(Motion.iosOut).start()
        }
    }

    /** Drop an open pager without animating (direction flipped, or the drag went elsewhere). */
    private fun dropPager() {
        val p = pager ?: return
        pager = null
        switchToTab(p.origin)
        modePages().forEach { it.translationX = 0f; it.alpha = 1f }
        p.shot.postDelayed({
            (p.shot.parent as? ViewGroup)?.removeView(p.shot)
            p.shot.setImageDrawable(null)
        }, 120L)
        pagerBusy = false
    }

    /** Tab tap: the same side-by-side slide as a swipe, in the tabs' order. */
    private fun pageToTab(tab: TextView) {
        if (pager != null || pagerBusy) return
        val from = currentTab()
        if (tab == from) return
        val order = modeTabsInOrder()
        val direction = if (order.indexOf(tab) < order.indexOf(from)) 1 else -1
        if (!Motion.areAnimationsEnabled(requireContext())) {
            if (switchToTab(tab)) tab.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            placeModeTabIndicator(animate = false)
            return
        }
        if (openPager(tab, direction)) settlePager(commit = true)
    }

    private fun setupWideSwipes(root: View) {
        val d = resources.displayMetrics.density
        val content = root.findViewById<View>(R.id.rootLayout)
        val topBar = root.findViewById<View>(R.id.topBarGlass)
        val dock = chatInputContainer
        val rootLoc = IntArray(2)
        val vLoc = IntArray(2)
        fun inside(v: View?, x: Float, y: Float): Boolean {
            if (v == null || !v.isShown) return false
            root.getLocationInWindow(rootLoc)
            v.getLocationInWindow(vLoc)
            val left = vLoc[0] - rootLoc[0]
            val top = vLoc[1] - rootLoc[1]
            return x >= left && x <= left + v.width && y >= top && y <= top + v.height
        }
        (root as? SwipeNavLayout)?.listener = object : SwipeNavLayout.Listener {
            override fun canStart(x: Float, y: Float): Boolean =
                historyDrawerContainer?.visibility != View.VISIBLE &&
                    pickerPopover?.isShowing != true &&
                    !headerContainer.isVisible &&
                    !inside(topBar, x, y) && !inside(dock, x, y) &&
                    parentFragmentManager.backStackEntryCount == 0

            var historyDrag = false

            override fun onDrag(dx: Float) {
                val direction = if (dx > 0) 1 else -1
                if (pagerOrigin == null) pagerOrigin = currentTab()
                val target = targetFrom(pagerOrigin ?: currentTab(), direction)
                val toHistory = direction > 0 && target == null
                if (toHistory != historyDrag) {
                    if (historyDrag) cancelHistoryDrag(animate = false)
                    historyDrag = toHistory
                    if (toHistory) { hideKeyboard(); dropPager(); beginHistoryDrag() }
                }
                if (historyDrag) {
                    dragHistory(dx)
                    return
                }
                if (target == null) {
                    // Nothing that way: lean a little and resist.
                    dropPager()
                    val max = 36f * d
                    val lean = max * (dx / (abs(dx) + 5 * max)) * 3f
                    modePages().forEach { it.translationX = lean; it.alpha = 1f }
                    return
                }
                if (pager?.target != target || pager?.direction != direction) {
                    dropPager()
                    if (!openPager(target, direction)) {
                        // Switching is blocked (a reply is streaming): just lean.
                        modePages().forEach { it.translationX = dx * 0.15f }
                        return
                    }
                }
                movePager(dx)
            }

            override fun onCommit(direction: Int) {
                content.performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_END)
                if (historyDrag) {
                    historyDrag = false
                    pagerOrigin = null
                    settleHistoryDrag()
                    return
                }
                if (pager == null) { onCancel(); return }
                settlePager(commit = true)
            }

            override fun onCancel() {
                if (historyDrag) {
                    historyDrag = false
                    pagerOrigin = null
                    cancelHistoryDrag(animate = true)
                    return
                }
                if (pager != null) { settlePager(commit = false); return }
                pagerOrigin = null
                modePages().forEach { p ->
                    p.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(Motion.spring).start()
                }
                placeModeTabIndicator(animate = true)
            }
        }
        (root as? SwipeNavLayout)?.apply {
            // Easier than before: shorter pull commits, and a light flick is enough.
            commitFraction = 0.22f
        }

        val drawer = historyDrawerContainer as? SwipeNavLayout ?: return
        drawer.commitFraction = 0.28f
        drawer.listener = object : SwipeNavLayout.Listener {
            override fun onDrag(dx: Float) {
                cancelDrawerAnimation()
                val w = drawer.width.coerceAtLeast(1).toFloat()
                val t = dx.coerceAtMost(0f)
                drawer.translationX = t
                content.translationX = w * 0.25f * (1f + t / w)
                historyDrawerScrim?.alpha = 0.35f * (1f + t / w)
            }

            override fun onCommit(direction: Int) {
                if (direction < 0) closeHistoryPanel() else onCancel()
            }

            override fun onCancel() {
                val w = drawer.width.coerceAtLeast(1).toFloat()
                drawer.animate().translationX(0f).setDuration(380).setInterpolator(Motion.spring).start()
                content.animate().translationX(w * 0.25f).setDuration(380).setInterpolator(Motion.spring).start()
                historyDrawerScrim?.animate()?.alpha(0.35f)?.setDuration(200)?.start()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHistorySwipeGestures(root: View) {
        val minDx = 64f * resources.displayMetrics.density
        // Open: left-edge strip only, finger must move LEFT → RIGHT
        val openDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                if (historyDrawerContainer?.visibility == View.VISIBLE) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < abs(dy) * 1.5f) return false
                if (dx > minDx && velocityX > 200f) {
                    hideKeyboard()
                    openHistoryPanel()
                    return true
                }
                return false
            }
        })
        // Close: history surface, finger must move RIGHT → LEFT
        val closeDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < abs(dy) * 1.5f) return false
                if (dx < -minDx && velocityX < -200f) {
                    closeHistoryPanel()
                    return true
                }
                return false
            }
        })

        root.findViewById<View>(R.id.historyEdgeSwipeStrip)?.let { strip ->
            // Align under top bar so History control is never covered
            view?.findViewById<View>(R.id.topBarLayout)?.let { top ->
                strip.post {
                    val lp = strip.layoutParams as? FrameLayout.LayoutParams ?: return@post
                    lp.topMargin = top.bottom
                    strip.layoutParams = lp
                }
            }
            strip.setOnTouchListener { _, event ->
                openDetector.onTouchEvent(event)
                // Consume move/up after a horizontal drag; never steal simple taps
                event.actionMasked != MotionEvent.ACTION_DOWN
            }
        }
        historyCloseSwipeDetector = closeDetector
    }

    /** Used by embedded SavedChatsFragment for R→L dismiss. */
    var historyCloseSwipeDetector: GestureDetector? = null
        private set

    override fun closeHistoryPanel(animated: Boolean) {
        val panel = historyDrawerContainer ?: return
        val scrim = historyDrawerScrim ?: return
        if (panel.visibility != View.VISIBLE) return
        cancelDrawerAnimation()
        val finishClose = {
            panel.visibility = View.GONE
            panel.translationX = -panel.width.toFloat().coerceAtLeast(0f)
            view?.findViewById<View>(R.id.rootLayout)?.let { it.animate().cancel(); it.translationX = 0f }
            scrim.visibility = View.GONE
            scrim.alpha = 0f
            panel.setLayerType(View.LAYER_TYPE_NONE, null)
            // Keep SavedChatsFragment attached — remounting every open caused stutter
        }
        if (!animated || !Motion.areAnimationsEnabled(requireContext())) {
            finishClose()
            return
        }
        val drawerMs = resources.getInteger(R.integer.motion_drawer).toLong()
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        panel.animate()
            .translationX(-panel.width.toFloat().coerceAtLeast(1f))
            .setDuration(drawerMs * 4 / 5)
            .setInterpolator(Motion.iosPush)
            .withEndAction { finishClose() }
            .start()
        // Chat slides back from its parallax offset as the panel leaves.
        view?.findViewById<View>(R.id.rootLayout)?.animate()
            ?.translationX(0f)?.setDuration(drawerMs * 4 / 5)?.setInterpolator(Motion.iosPush)?.start()
        scrim.animate()
            .alpha(0f)
            .setDuration(resources.getInteger(R.integer.motion_scrim).toLong())
            .setInterpolator(Motion.easeOut)
            .withEndAction { scrim.visibility = View.GONE }
            .start()
    }

    override fun startNewChatFromHistory() {
        closeHistoryPanel(animated = true)
        val hasMessages = !viewModel.chatMessages.value.isNullOrEmpty()
        if (hasMessages) {
            resetChatButton.performClick()
        } else if (viewModel.isRpMode()) {
            viewModel.startFreshChatForCurrentMode()
        } else {
            viewModel.startNewChat()
        }
    }

    override fun openSettingsFromHistory() {
        // Push settings over the history panel — do not hide chat or close history first, or the
        // chat layer flashes through. A slide, not a fade: a fading page let the list show through.
        parentFragmentManager.beginTransaction()
            .withGrokPushOver()
            .add(R.id.fragment_container, SettingsFragment())
            .addToBackStack("settings")
            .commit()
    }

    override fun openFromHistory(destination: HistoryPanelHost.Destination) {
        when (destination) {
            HistoryPanelHost.Destination.CODE -> {
                closeHistoryPanel()
                if (::codeMode.isInitialized) codeMode.activate()
            }
            HistoryPanelHost.Destination.ROLEPLAY -> openRpHub()
            HistoryPanelHost.Destination.MODELS -> openBotModelPicker()
            HistoryPanelHost.Destination.PROMPTS -> parentFragmentManager.beginTransaction()
                .withGrokPushOver()
                .add(R.id.fragment_container, PromptLibraryFragment())
                .addToBackStack(null)
                .commit()
            HistoryPanelHost.Destination.PRESETS -> parentFragmentManager.beginTransaction()
                .withGrokPushOver()
                .add(R.id.fragment_container, PresetsListFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    // ── History drawer following a wide swipe (see setupWideSwipes) ──

    private fun beginHistoryDrag() {
        val panel = historyDrawerContainer ?: return
        val scrim = historyDrawerScrim ?: return
        if (panel.visibility == View.VISIBLE) return
        cancelDrawerAnimation()
        if (childFragmentManager.findFragmentById(R.id.historyDrawerContainer) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.historyDrawerContainer, SavedChatsFragment.newEmbedded())
                .commitNow()
        }
        (childFragmentManager.findFragmentById(R.id.historyDrawerContainer) as? SavedChatsFragment)?.refreshModeRows()
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.START
        panel.layoutParams = lp
        val w = (view?.width ?: panel.width).coerceAtLeast(1)
        panel.translationX = -w.toFloat()
        panel.visibility = View.VISIBLE
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
    }

    private fun dragHistory(dx: Float) {
        val panel = historyDrawerContainer ?: return
        val w = (view?.width ?: panel.width).coerceAtLeast(1).toFloat()
        val x = dx.coerceIn(0f, w)
        panel.translationX = x - w
        historyDrawerScrim?.alpha = 0.35f * (x / w)
        view?.findViewById<View>(R.id.rootLayout)?.translationX = x * 0.25f
    }

    private fun settleHistoryDrag() {
        val panel = historyDrawerContainer ?: return
        val w = (view?.width ?: panel.width).coerceAtLeast(1).toFloat()
        val ms = resources.getInteger(R.integer.motion_drawer).toLong()
        historyDrawerScrim?.animate()?.alpha(0.35f)?.setDuration(ms)?.setInterpolator(Motion.iosOut)?.start()
        panel.animate().translationX(0f).setDuration(ms).setInterpolator(Motion.iosOut)
            .withEndAction { panel.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
        view?.findViewById<View>(R.id.rootLayout)?.animate()
            ?.translationX(w * 0.25f)?.setDuration(ms)?.setInterpolator(Motion.iosOut)?.start()
    }

    private fun cancelHistoryDrag(animate: Boolean) {
        val panel = historyDrawerContainer ?: return
        val scrim = historyDrawerScrim
        val w = (view?.width ?: panel.width).coerceAtLeast(1).toFloat()
        val root = view?.findViewById<View>(R.id.rootLayout)
        val done = {
            panel.visibility = View.GONE
            panel.translationX = 0f
            panel.setLayerType(View.LAYER_TYPE_NONE, null)
            scrim?.visibility = View.GONE
        }
        if (!animate) {
            root?.translationX = 0f
            scrim?.alpha = 0f
            done()
            return
        }
        scrim?.animate()?.alpha(0f)?.setDuration(260)?.setInterpolator(Motion.iosOut)?.start()
        root?.animate()?.translationX(0f)?.setDuration(420)?.setInterpolator(Motion.spring)?.start()
        panel.animate().translationX(-w).setDuration(260).setInterpolator(Motion.iosOut)
            .withEndAction { done() }.start()
    }

    private fun openHistoryPanel() {
        // Full-screen history overlay (Grok ref: covers Ask entirely; >> / back / swipe dismiss)
        val panel = historyDrawerContainer ?: return
        val scrim = historyDrawerScrim ?: return
        if (panel.visibility == View.VISIBLE) return
        cancelDrawerAnimation()

        if (childFragmentManager.findFragmentById(R.id.historyDrawerContainer) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.historyDrawerContainer, SavedChatsFragment.newEmbedded())
                .commitNow()
        }
        (childFragmentManager.findFragmentById(R.id.historyDrawerContainer) as? SavedChatsFragment)?.refreshModeRows()

        val drawerMs = resources.getInteger(R.integer.motion_drawer).toLong()
        val anim = Motion.areAnimationsEnabled(requireContext())

        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.START
        panel.layoutParams = lp

        // Position off-screen before first draw — avoids flash/stutter of content at x=0
        panel.visibility = View.INVISIBLE
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f

        panel.post {
            val w = panel.width.coerceAtLeast(1)
            panel.translationX = -w.toFloat()
            panel.visibility = View.VISIBLE
            if (!anim) {
                panel.translationX = 0f
                return@post
            }
            panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // Full-bleed panel — keep scrim subtle/brief (mostly covered)
            scrim.animate().alpha(0.35f).setDuration(resources.getInteger(R.integer.motion_scrim).toLong())
                .setInterpolator(Motion.easeOut).start()
            panel.animate()
                .translationX(0f)
                .setDuration(drawerMs)
                .setInterpolator(Motion.iosPush)
                .withEndAction { panel.setLayerType(View.LAYER_TYPE_NONE, null) }
                .start()
            // Parallax: the chat drifts a little the same way, like an iOS push.
            view?.findViewById<View>(R.id.rootLayout)?.animate()
                ?.translationX(w * 0.25f)?.setDuration(drawerMs)?.setInterpolator(Motion.iosPush)?.start()
        }
    }

    fun onBackPressed(): Boolean {
        if (::codeMode.isInitialized && codeMode.onBackPressed()) return true
        if (historyDrawerContainer?.visibility == View.VISIBLE) {
            closeHistoryPanel()
            return true
        }
        if (viewModel.isExpandableInputEnabled.value == true && chatEditText.hasFocus()) {
            setInputExpandedState(false)
        }
        chatEditText.clearFocus()
        if (headerContainer.isVisible) {
            hideMenu()
            return true
        } else if (menuClosedByTouch) {
            menuClosedByTouch = false  // Reset immediately
            return true  // Consume to prevent app hide (menu already closed by touch)
        }
        return false  // Allow normal back (e.g., exit app)
    }
    private fun launchCamera() {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "gradation_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }

        val uri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { imageUri ->
            currentCameraUri = imageUri  // NEW: Store for reliable retrieval
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)  // Allow camera to write
            }
            if (cameraIntent.resolveActivity(requireContext().packageManager) != null) {
                cameraLauncher.launch(cameraIntent)
            } else {
                AppToast.makeText(requireContext(), getString(R.string.toast_no_camera_app), AppToast.LENGTH_SHORT).show()
                requireContext().contentResolver.delete(imageUri, null, null)
                currentCameraUri = null  // NEW: Clean up
            }
        } ?: run {
            GlassNotice.show(requireContext(), getString(R.string.toast_could_not_create_image))
        }
    }
    fun startSpeechRecognitionSafely() {
        // STT disabled
        /*
        hideKeyboard()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startSpeechRecognition()
        }
        */
    }
    private fun processPdfUri(pdfUri: Uri) {
        if (discardAttachmentIfRp()) return
        lifecycleScope.launch {
            var parcelFd: ParcelFileDescriptor? = null
            var tempPdfFile: File? = null
            try {
                // Try direct ParcelFileDescriptor (OpenDocument makes this reliable)
                parcelFd = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openFileDescriptor(pdfUri, "r")
                } ?: run {
                    // Fallback copy (rare now)
                    // Direct access fallback (rare)
                    val inputStream = requireContext().contentResolver.openInputStream(pdfUri)
                        ?: run {
                            AppToast.makeText(requireContext(), getString(R.string.toast_pdf_no_read_access), AppToast.LENGTH_SHORT).show()
                            return@launch
                        }

                    val cacheDir = requireContext().cacheDir
                    tempPdfFile = File(cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
                    withContext(Dispatchers.IO) {
                        inputStream.use { input ->
                            FileOutputStream(tempPdfFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    inputStream.close()

                    ParcelFileDescriptor.open(tempPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }

                if (parcelFd == null) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_pdf_access_failed))
                    return@launch
                }

                val pdfRenderer = PdfRenderer(parcelFd)
                when (val pageCount = pdfRenderer.pageCount) {
                    0 -> {
                        AppToast.makeText(requireContext(), getString(R.string.toast_pdf_no_pages), AppToast.LENGTH_SHORT).show()
                        pdfRenderer.close()
                    }
                    1 -> {
                        val bitmap = renderPdfPageToBitmap(pdfRenderer, 0)
                        processPdfBitmap(bitmap, "Page 1 of 1")
                        pdfRenderer.close()
                    }
                    else -> {
                        // Ownership transfers to the dialog: it closes renderer + fd + temp file.
                        showPageSelectionDialog(pdfRenderer, parcelFd, pageCount, tempPdfFile)
                        parcelFd = null
                        tempPdfFile = null
                    }
                }
            } catch (e: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.notice_pdf_process_failed, e.message ?: ""))
            } finally {
                try {
                    parcelFd?.close()
                    tempPdfFile?.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun renderPdfPageToBitmap(renderer: PdfRenderer, pageIndex: Int): Bitmap {
        return withContext(Dispatchers.IO) {
            val page = renderer.openPage(pageIndex)
            val scale = 2f
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()
            val bounds = Rect(0, 0, width, height)

            // Probe render to pick a background: PdfRenderer draws on transparent, so pages with
            // light ink (dark-theme exports) would vanish on white and dark ink would vanish on black.
            val probeW = 64
            val probeH = (64f * height / width).toInt().coerceAtLeast(1)
            val probe = createBitmap(probeW, probeH)
            page.render(probe, Rect(0, 0, probeW, probeH), null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            var darkPixels = 0
            var totalOpaque = 0
            val pixels = IntArray(probeW * probeH)
            probe.getPixels(pixels, 0, probeW, 0, 0, probeW, probeH)
            for (px in pixels) {
                val alpha = (px ushr 24) and 0xFF
                if (alpha > 32) {
                    totalOpaque++
                    val lum = 0.299f * ((px shr 16) and 0xFF) + 0.587f * ((px shr 8) and 0xFF) + 0.114f * (px and 0xFF)
                    if (lum < 128) darkPixels++
                }
            }
            probe.recycle()
            val bgColor = if (totalOpaque == 0 || darkPixels > totalOpaque / 2) Color.WHITE else Color.BLACK

            val bitmap = createBitmap(width, height)
            bitmap.eraseColor(bgColor)
            page.render(bitmap, bounds, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()
            bitmap
        }
    }


    private suspend fun processPdfBitmap(bitmap: Bitmap, description: String) {
        if (discardAttachmentIfRp()) {
            bitmap.recycle()
            return
        }
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val bytes = byteArrayOutputStream.toByteArray()

        if (bytes.size > 12_000_000) {
            GlassNotice.show(requireContext(), getString(R.string.toast_pdf_page_too_large))
            bitmap.recycle()
            return
        }

        selectedImageBytes = bytes
        selectedImageMime = "image/png"

        // Save PNG to temp file for chat preview
        val cacheDir = requireContext().cacheDir
        val tempPngFile = File(cacheDir, "pdf_page_${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) {  // Off UI: Write bytes to file
            tempPngFile.outputStream().use { out ->
                out.write(bytes)
            }
        }
        currentTempImageFile = tempPngFile  // Track for cleanup

        val pngUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            tempPngFile
        )

        // Set for ViewModel (enables bubble preview)
        viewModel.setPendingUserImageUri(pngUri.toString())

        val previewBmp = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        previewImageView.setImageBitmap(previewBmp)
        attachmentPreviewContainer.visibility = View.VISIBLE

        AppToast.makeText(requireContext(), getString(R.string.toast_converted_to_image, description), AppToast.LENGTH_SHORT).show()

        // Recycle originals
        bitmap.recycle()
        // After copy
    }




    private fun showPageSelectionDialog(
        pdfRenderer: PdfRenderer,
        parcelFd: ParcelFileDescriptor,
        pageCount: Int,
        tempPdfFile: File?
    ) {
        val pageTitles = (1..pageCount).map { "Page $it" }.toTypedArray()
        var selectedPage = 0
        var converting = false
        val release = {
            try { pdfRenderer.close() } catch (_: Exception) {}
            try { parcelFd.close() } catch (_: Exception) {}
            tempPdfFile?.delete()
        }

        GlassAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_select_pdf_page)
            .setSingleChoiceItems(pageTitles, 0) { _, which -> selectedPage = which }
            .setPositiveButton("Convert") { _, _ ->
                converting = true
                lifecycleScope.launch {
                    try {
                        val bitmap = renderPdfPageToBitmap(pdfRenderer, selectedPage)
                        processPdfBitmap(bitmap, "Page ${selectedPage + 1} of $pageCount")
                    } catch (e: Exception) {
                        GlassNotice.show(requireContext(), getString(R.string.notice_pdf_render_failed, e.message ?: ""))
                    } finally {
                        release()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            // Cancel button, back and outside-tap all dismiss; only Convert keeps the renderer open.
            .setOnDismissListener { if (!converting) release() }
            .show()
    }
    private fun processTextFile(uri: Uri) {
        if (discardAttachmentIfRp()) return
        lifecycleScope.launch {
            try {
                // Get file info (your existing query for filename)
                val fileName = try {
                    val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) it.getString(nameIndex) else "unknown.txt"
                        } else "unknown.txt"
                    } ?: "unknown.txt"
                } catch (e: Exception) {
                    "unknown.txt"
                }

                // Get MIME type and extension for validation (before reading content)
                val mimeType = requireContext().contentResolver.getType(uri)
                val extension = fileName.substringAfterLast('.', "").lowercase()

                // Allowed MIME types (your list from earlier)
                val allowedTypes = setOf(
                    "text/plain", "text/html", "text/css", "text/javascript", "application/javascript",
                    "application/json", "application/xml", "text/yaml", "application/toml",
                    "text/csv", "application/sql", "text/markdown", "image/svg+xml"
                )

                // FIXED: Fallback if MIME is known-good OR (unknown/non-text + code extension)
                val isAllowed = if (mimeType != null && allowedTypes.contains(mimeType)) {
                    true  // Known text MIME: Accept
                } else {
                    // MIME is null, unknown (e.g., octet-stream), or non-text: Fallback to extension
                    val codeExtensions = setOf(
                        "kt", "java", "py", "js", "ts", "cpp", "c", "h", "cs", "php", "rb", "go", "rs", "swift",
                        "html", "css", "json", "xml", "yaml", "yml", "md", "txt", "sh", "sql", "csv", "log"
                    )
                    codeExtensions.contains(extension)
                }

                if (!isAllowed) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_unsupported_file, fileName, mimeType))
                    return@launch
                }

                // Check current total size first (your existing logic)
                val currentTotalSize = pendingFiles.sumOf { it.size }

                // Read content and check individual file size (your buffered reading)
                val content = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val contentBuilder = StringBuilder()
                        val buffer = CharArray(8192)
                        var charsRead: Int
                        while (reader.read(buffer).also { charsRead = it } > 0) {
                            contentBuilder.append(buffer, 0, charsRead)
                        }
                        contentBuilder.toString()
                    }
                } ?: throw Exception("Could not read file")

                val fileSize = content.toByteArray().size.toLong()

                // Size validation (your existing checks)
                if (fileSize > MAX_SINGLE_FILE_SIZE) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_file_too_large, fileName, MAX_SINGLE_FILE_SIZE / 1024 / 1024))
                    return@launch
                }

                if (currentTotalSize + fileSize > MAX_FILE_SIZE) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_attachments_total_limit, fileName, (currentTotalSize + fileSize) / 1024 / 1024, MAX_FILE_SIZE / 1024 / 1024))
                    return@launch
                }
                if (discardAttachmentIfRp()) return@launch

                // Add to pending files (your existing AttachedFile)
                pendingFiles.add(AttachedFile(fileName, content, fileSize))

                // Update UI (your existing)
                updateAttachmentButton()
                AppToast.makeText(requireContext(), getString(R.string.toast_file_attached, fileName), AppToast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.toast_failed_read_file, e.message ?: ""))
            }
        }
    }



    private var sendButtonActive = false
    private var appliedTextScale = -1

    /** Settings > Appearance > Chat text may have changed while we were hidden. */
    private fun applyChatTextScale() {
        val scale = sharedPreferencesHelper.getFontSizeCh()
        if (scale == appliedTextScale || !::chatAdapter.isInitialized) return
        appliedTextScale = scale
        chatAdapter.updateFontSize(scale)
    }

    /** Undo any interrupted pop/morph (fragment hidden or paused mid-animation). */
    private fun settleSendButton() {
        if (!::sendChatButton.isInitialized) return
        sendChatButton.animate().cancel()
        sendChatButton.scaleX = 1f
        sendChatButton.scaleY = 1f
        sendChatButton.alpha = 1f
    }

    private fun updateSendButtonChrome() {
        if (!::sendChatButton.isInitialized) return
        val awaiting = viewModel.isAwaitingResponse.value == true
        if (awaiting) {
            sendChatButton.isEnabled = true
            return
        }
        val hasContent = !chatEditText.text.isNullOrBlank() ||
            pendingFiles.isNotEmpty() ||
            currentTempImageFile != null ||
            selectedAudioBytes != null
        // RP with nothing typed: the button turns into Continue (fast-forward the story).
        val canContinue = !hasContent && viewModel.canContinueRpStory()
        if (canContinue) {
            sendButtonActive = false
            sendChatButton.isEnabled = true
            sendChatButton.setBackgroundResource(R.drawable.bg_send_disabled)
            sendChatButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.xai_ink))
            sendChatButton.setIconResource(R.drawable.ic_fast_forward)
            sendChatButton.contentDescription = getString(R.string.rp_continue)
            return
        }
        sendChatButton.contentDescription = getString(R.string.cd_send)
        val becameActive = hasContent && !sendButtonActive
        sendButtonActive = hasContent
        sendChatButton.isEnabled = hasContent
        if (becameActive && Motion.areAnimationsEnabled(requireContext())) {
            // Send wakes up with a small springy pop as soon as there is something to send.
            sendChatButton.animate().cancel()
            sendChatButton.scaleX = 0.78f
            sendChatButton.scaleY = 0.78f
            sendChatButton.animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(Motion.springBouncy).start()
        }
        sendChatButton.setBackgroundResource(
            if (hasContent) R.drawable.bg_send_enabled else R.drawable.bg_send_disabled
        )
        sendChatButton.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (hasContent) R.color.xai_send_icon_enabled else R.color.xai_mute
            )
        )
        sendChatButton.icon = originalSendIcon
    }

    private fun updateAttachmentButton() {
        attachmentButton.isSelected = pendingFiles.isNotEmpty()
        updateSendButtonChrome()
    }

    private fun showAttachedFiles() {
        if (pendingFiles.isEmpty()) return

        val filesList = pendingFiles.joinToString("\n") { file ->
            "${file.fileName} (${formatFileSize(file.size)})"
        }

        val builder = GlassAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_attached_files, pendingFiles.size))
            .setMessage(filesList)
            .setPositiveButton("Remove All") { _, _ ->
                pendingFiles.clear()
                updateAttachmentButton()
            }
            .setNegativeButton("Close", null)

        val dialog = builder.show()

        // Apply dim amount of 0.8f
        dialog.window?.let { GlassDialogs.frost(it) }
    }
    private fun showToolsSelectionDialog() {
        // 1️⃣ Load current state from SharedPreferences
        val enabledTools = sharedPreferencesHelper.getEnabledTools()
        val hasStoredPrefs = sharedPreferencesHelper.hasEnabledToolsStored()

        // 2️⃣ Compute effective enabled set for display
        val effectiveEnabledSet = if (!hasStoredPrefs) {
            emptySet()
        } else {
            enabledTools
        }

        // 3️⃣ Get ALL items
        val allItems = ToolItem.getAllToolItems(effectiveEnabledSet)

        // --- NEW LOGIC: FILTERING ---
        // 4️⃣ Check if Brave API key exists
        val braveApiKey = sharedPreferencesHelper.getApiKeyFromPrefs("brave_search_api_key")
        val hasBraveKey = braveApiKey.isNotEmpty()

        // 5️⃣ Filter the list: Keep everything UNLESS it's brave_search and we don't have a key
        val filteredItems = allItems.filter { item ->
            if (item.name == "brave_search" || item.name == "brave_news" || item.name == "find_nearby_places") {
                hasBraveKey // Only keep Brave tools if key exists
            } else {
                true // Keep all other tools
            }
        }
        // ----------------------------

        // 6️⃣ Create a mutable copy of the FILTERED list
        val mutableItems = filteredItems.toMutableList()

        // 7️⃣ Create the dialog
        val dialog = GlassAlertDialogBuilder(requireContext(),
            R.style.CustomMaterialAlertDialogTheme
        )
            .setTitle(R.string.dialog_enable_disable_tools)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                // Note: We map from the mutableItems which is already filtered
                val newEnabledSet = mutableItems
                    .filter { it.isEnabled }
                    .map { it.name }
                    .toSet()
                sharedPreferencesHelper.saveEnabledTools(newEnabledSet)
            }
            .create()

        val scrollView = ScrollView(requireContext()).apply {
            setPadding(1.dpToPx(), 1.dpToPx(), 1.dpToPx(), 1.dpToPx())
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 8️⃣ Loop through the FILTERED list
        for ((index, item) in mutableItems.withIndex()) {
            val row = LayoutInflater.from(requireContext()).inflate(
                R.layout.item_tool_toggle,
                container,
                false
            )

            val checkBox = row.findViewById<CheckBox>(R.id.checkbox_tool)
            val titleTv = row.findViewById<TextView>(R.id.text_tool_title)
            val descTv = row.findViewById<TextView>(R.id.text_tool_desc)

            titleTv.text = item.displayName
            descTv.text = item.description
            checkBox.isChecked = item.isEnabled

            val stableIndex = index

            // Row click
            row.setOnClickListener {
                val currentItem = mutableItems[stableIndex]
                val newState = !currentItem.isEnabled

                // --- INTERCEPT: set_sound_mode ---
                if (currentItem.name == "set_sound_mode" && newState) {
                    val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    // Check if we already have permission
                    if (!notificationManager.isNotificationPolicyAccessGranted) {
                        // Permission not granted, open settings
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        // Using the launcher defined in onCreate
                        notificationPolicyLauncher.launch(intent)
                        return@setOnClickListener // Stop here, don't toggle checkbox yet
                    }
                }
                // ---------------------------------

                // --- INTERCEPT: get_location ---
                if (currentItem.name == "get_location" && newState) {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        return@setOnClickListener
                    }
                }
                // -------------------------------

                // Normal toggle behavior
                mutableItems[stableIndex] = currentItem.copy(isEnabled = newState)
                checkBox.isChecked = newState
            }

            // Checkbox click
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                mutableItems[stableIndex] = mutableItems[stableIndex].copy(isEnabled = isChecked)
            }

            val layoutParams = row.layoutParams as LinearLayout.LayoutParams
            layoutParams.bottomMargin = 1.dpToPx()
            row.layoutParams = layoutParams

            container.addView(row)
        }

        scrollView.addView(container)
        dialog.setView(scrollView)
        dialog.window?.let { GlassDialogs.frost(it) }
        dialog.show()

        val titleView = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
        titleView?.paintFlags = titleView.paintFlags.or(Paint.UNDERLINE_TEXT_FLAG)
    }


    private fun showWebSearchEngineDialog() {
        val engines = listOf(
            "default" to "Default (Native if available, fallback Exa)",
            "native" to "Native (Provider's built-in search)",
            "exa" to "Exa (Always use Exa search)",
            "firecrawl" to "Firecrawl (Always use Firecrawl search. Uses your BYOK credits)",
            "parallel" to "Parallel (Always use Parallel search)"
        )

        val currentEngine = sharedPreferencesHelper.getWebSearchEngine()
        var selectedEngine = currentEngine

        GlassAlertDialogBuilder(requireContext(),
            R.style.CustomMaterialAlertDialogTheme
        )
            .setTitle(R.string.dialog_web_search_engine)
            .setSingleChoiceItems(
                engines.map { it.second }.toTypedArray(),
                engines.indexOfFirst { it.first == currentEngine }.takeIf { it >= 0 } ?: 0
            ) { _, which ->
                selectedEngine = engines[which].first
            }
            .setPositiveButton(R.string.action_save) { _, _ ->
                sharedPreferencesHelper.saveWebSearchEngine(selectedEngine)
                showWebSearchContextSizeDialog()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // NEW: Dialog for Context Size
    private fun showWebSearchContextSizeDialog() {
        val sizes = listOf(
            "low" to "Low (Minimal context, basic queries)",
            "medium" to "Medium (Moderate context, general queries)",
            "high" to "High (Extensive context, detailed research)"
        )

        val currentSize = sharedPreferencesHelper.getWebSearchContextSize()
        var selectedSize = currentSize

        GlassAlertDialogBuilder(requireContext(),
            R.style.CustomMaterialAlertDialogTheme
        )
            .setTitle(R.string.dialog_search_context_size)
            .setSingleChoiceItems(
                sizes.map { it.second }.toTypedArray(),
                sizes.indexOfFirst { it.first == currentSize }.takeIf { it >= 0 } ?: 1 // Default to medium
            ) { _, which ->
                selectedSize = sizes[which].first
            }
            .setPositiveButton(R.string.action_save) { _, _ ->
                sharedPreferencesHelper.saveWebSearchContextSize(selectedSize)
                showWebSearchMaxResultsDialog()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    private fun showWebSearchMaxResultsDialog() {
        // Generate list 1 to 20
        val options = (1..20).toList()
        val optionsStrings = options.map { it.toString() }.toTypedArray()

        val currentMax = sharedPreferencesHelper.getWebSearchMaxResults()
        var selectedMax = currentMax

        GlassAlertDialogBuilder(requireContext(),
            R.style.CustomMaterialAlertDialogTheme
        )
            // Combine the title and the message here
            .setTitle(R.string.dialog_max_search_results)
            .setSingleChoiceItems(
                optionsStrings,
                options.indexOf(currentMax).takeIf { it >= 0 } ?: 4 // Index 4 is '5'
            ) { _, which ->
                selectedMax = options[which]
            }
            .setPositiveButton(R.string.action_save) { _, _ ->
                // Final save step
                sharedPreferencesHelper.saveWebSearchMaxResults(selectedMax)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024f)
            else -> String.format("%.1fMB", bytes / 1024f / 1024f)
        }
    }
    fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()
    fun TextView.animateColor(from: Int, to: Int, dur: Long): ValueAnimator? =
        ValueAnimator.ofArgb(from, to).apply {
            duration = dur
            addUpdateListener { setTextColor(it.animatedValue as Int) }
            start()
        }
    private val hideScrollProgress = Runnable {
        if (!Motion.areAnimationsEnabled(requireContext())) { progressBar.alpha = 0f; return@Runnable }
        progressBar.animate().alpha(0f).setDuration(280).setInterpolator(Motion.easeOut).start()
    }

    private fun updateScrollProgress() {
        val offset = chatRecyclerView.computeVerticalScrollOffset()
        val extent = chatRecyclerView.computeVerticalScrollExtent()
        val range = chatRecyclerView.computeVerticalScrollRange() - extent

        if (range > 0f) {
            val progress = (offset.toFloat() / range).coerceIn(0f, 1f)
            progressBar.visibility = View.VISIBLE
            progressBar.scaleX = progress
            // Like an iOS scroll indicator: there while you move, gone once you stop.
            progressBar.removeCallbacks(hideScrollProgress)
            progressBar.animate().cancel()
            progressBar.alpha = 0.6f
            progressBar.postDelayed(hideScrollProgress, 700)
        } else {
            progressBar.visibility = View.GONE
        }
    }
    private fun updateExtendedTopBarVisibility(extendedEnabled: Boolean) {
        extendedTopBarContainer.visibility = if (extendedEnabled) View.VISIBLE else View.GONE
        // Parent scroll must track the same flag or the row stays gone forever
        view?.findViewById<View>(R.id.extendedTopBarScroll)?.visibility =
            if (extendedEnabled) View.VISIBLE else View.GONE

        val model = viewModel.activeChatModel.value // Get current model (may be null on startup)
        val isLan = viewModel.activeModelIsLan()

        val buttons = listOf(
            Triple(reasoningButton, topReasoningButton) {
                model != null && viewModel.isReasoningModel(model)
            },
            Triple(webSearchButton, topWebSearchButton) {
                !isLan && !viewModel.isRpMode()
            },
            Triple(streamButton, topStreamButton) { true },
            //Triple(convoButton, topConvoButton) { true },
            Triple(toolsButton, topToolsButton) { !viewModel.isRpMode() },
           // Triple(presetsButton, topPresetsButton) { true },
            Triple(settingsButton, topSettingsButton) { true }
        )

        buttons.forEach { (popup, top, condition) ->
            val shouldShow = condition()

            if (extendedEnabled) {
                popup.visibility = View.GONE
                top.visibility = if (shouldShow) View.VISIBLE else View.GONE
            } else {
                top.visibility = View.GONE
                popup.visibility = if (shouldShow) View.VISIBLE else View.GONE
            }
        }
        // The sheet's effort row replaces the Reasoning tile; the top-bar toggle stays.
        reasoningButton.visibility = View.GONE
        homeButton.visibility = if (extendedEnabled) View.VISIBLE else View.GONE
        val isPresetsOnChatScreen = viewModel.isPresetsExtendedEnabled.value ?: false
        val rp = viewModel.isRpMode()

        if (rp) {
            presetsButton.visibility = View.GONE
            topPresetsButton.visibility = View.GONE
            presetsButton2.visibility = View.GONE
        } else if (extendedEnabled) {
            // TOP BAR ON: Menu preset is ALWAYS gone. Top preset is ALWAYS visible.
            presetsButton.visibility = View.GONE
            topPresetsButton.visibility = View.VISIBLE
        } else {
            // TOP BAR OFF: Hide the top bar versions
            topPresetsButton.visibility = View.GONE
            // PRESET LOGIC: Only show in menu if it's NOT already on the chat screen
            presetsButton.visibility = if (isPresetsOnChatScreen) View.GONE else View.VISIBLE
        }
        // ---> NEW: Clean up the empty rows here! <---
        updateMenuRowVisibilities()
    }
    private fun updateHomeButtonVisibility() {
        val extendedEnabled = sharedPreferencesHelper.getExtendedTopBarEnabled()
        homeButton.visibility = if (extendedEnabled) View.VISIBLE else View.GONE //backcopyButton.isGone &&
    }
    private fun captureItemToBitmap(position: Int, format: String) {
        val viewHolder = chatRecyclerView.findViewHolderForAdapterPosition(position) as? ChatAdapter.AssistantViewHolder
        if (viewHolder != null) {
            val bitmap = captureViewToBitmapNow(viewHolder.messageContainer)
            if (bitmap != null) {
                viewModel.saveBitmapToDownloads(bitmap, format)
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_failed_capture_view))
            }
        } else {
            AppToast.makeText(requireContext(), getString(R.string.toast_item_not_visible), AppToast.LENGTH_SHORT).show()
        }
    }
    fun copyLatestMessage() {
        chatAdapter.getLatestPlainText()?.let { text ->
            if (text.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied", text))
                AppToast.makeText(requireContext(), getString(R.string.toast_copied), AppToast.LENGTH_SHORT).show()
            }
        }
    }
    fun View.animateOutlineFlash(
        targetColor: Int,
        glowDuration: Long = 1000,
        stayDuration: Long = 500,  // <--- NEW: How long it stays fully lit
        fadeDuration: Long = 1000,
        maxStrokeWidth: Int = 6
    ) {
        val bgColor = Color.TRANSPARENT

        val outlineDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            setStroke(maxStrokeWidth, bgColor)
            cornerRadius = 24f * resources.displayMetrics.density
        }

        this.background = outlineDrawable


        // 1. Fade IN (Transparent -> Target Color)
        val glowAnimator = ValueAnimator.ofArgb(bgColor, targetColor).apply {
            duration = glowDuration
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                outlineDrawable.setStroke(maxStrokeWidth, color)
            }
        }

        // 2. Fade OUT (Target Color -> Transparent)
        val fadeAnimator = ValueAnimator.ofArgb(targetColor, bgColor).apply {
            duration = fadeDuration

            // <--- CHANGED: Wait for glow + stay time before fading out
            startDelay = glowDuration + stayDuration

            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                outlineDrawable.setStroke(maxStrokeWidth, color)
            }
        }

        glowAnimator.start()
        fadeAnimator.start()
    }
    fun onOpenedFromNotification() {
        homeButton.visibility = View.GONE
        backButton.visibility = View.VISIBLE
        backcopyButton.visibility = View.VISIBLE
    }
    private fun updateMenuRowVisibilities() {
        // Grab the rows in order (excluding buttonsRow2 as we discussed)
        val rows = listOf(
            view?.findViewById<LinearLayout>(R.id.buttonsRow1),
            view?.findViewById<LinearLayout>(R.id.buttonsRow3),
            view?.findViewById<LinearLayout>(R.id.buttonsRowComposer),
            view?.findViewById<LinearLayout>(R.id.buttonsRowAux)
        )

        var isFirstVisibleRow = true

        rows.forEach { row ->
            if (row != null) {
                var hasVisibleChild = false
                // Check every child inside this row
                for (i in 0 until row.childCount) {
                    if (row.getChildAt(i).isVisible) {
                        hasVisibleChild = true
                        break
                    }
                }

                if (hasVisibleChild) {
                    row.visibility = View.VISIBLE

                    // Dynamically fix the margins!
                    val params = row.layoutParams as LinearLayout.LayoutParams
                    val marginDp = if (isFirstVisibleRow) 0 else 8 // 0 for the top row, 8 for the rest
                    val marginPx = (marginDp * resources.displayMetrics.density).toInt()

                    if (params.topMargin != marginPx) {
                        params.topMargin = marginPx
                        row.layoutParams = params
                    }

                    // We found the first row, so the next ones are no longer first
                    isFirstVisibleRow = false
                } else {
                    row.visibility = View.GONE
                }
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun startVoiceRecording() {
        // STT disabled
        return
        /*
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        try {
            voiceRecordFile = File(requireContext().cacheDir, "voice_input_${System.currentTimeMillis()}.opus")

            mediaRecorder = MediaRecorder(requireContext()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setOutputFile(voiceRecordFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                //setAudioBitRate(32000)
                prepare()
                start()
            }

            isRecording = true
            speechButton.setIconResource(R.drawable.ic_stop_circle) // Red mic or recording indicator
            speechButton.isSelected = true
            AppToast.makeText(requireContext(), getString(R.string.toast_recording), AppToast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            GlassNotice.show(requireContext(), getString(R.string.toast_recording_failed, e.message ?: ""))
            voiceRecordFile?.delete()
            voiceRecordFile = null
        }
        */
    }

    private fun stopVoiceRecording() {
        // STT disabled
        return
        /*
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            speechButton.setIconResource(R.drawable.ic_mic) // Original mic icon
            speechButton.isSelected = false

            voiceRecordFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    processVoiceRecording(file)
                } else {
                    AppToast.makeText(requireContext(), getString(R.string.toast_recording_empty), AppToast.LENGTH_SHORT).show()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            isRecording = false
            speechButton.setIconResource(R.drawable.ic_mic)
            speechButton.isSelected = false
        }
        */
    }

    private fun processVoiceRecording(file: File) {
        // STT disabled
        file.delete()
        return
        /*
        lifecycleScope.launch {
            try {
                val audioBytes = withContext(Dispatchers.IO) {
                    file.readBytes()
                }

                if(!fromWater) {
                    AppToast.makeText(requireContext(), getString(R.string.toast_transcribing), AppToast.LENGTH_SHORT).show()
                }
                val transcribedText = viewModel.transcribeAudioForInput(
                    audioBytes = audioBytes,
                    audioFormat = "opus",
                    fileName = file.name
                )

                if (!transcribedText.isNullOrBlank()) {
                    if(fromWater){
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Transcribed Text", transcribedText)
                        clipboard.setPrimaryClip(clip)}
                    chatEditText.setText(transcribedText)
                    chatEditText.setSelection(transcribedText.length)
                    // AppToast.makeText(requireContext(), "Transcription complete", AppToast.LENGTH_SHORT).show()
                } else {
                    GlassNotice.show(requireContext(), getString(R.string.toast_transcription_failed))
                }
            } catch (e: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.toast_error_generic, e.message ?: ""))
            } finally {
                file.delete()
                voiceRecordFile = null
                fromWater = false
            }
        }
        */
    }
    private fun insertRpReminderTemplate() {
        val prefix = "_(Reminder: "
        val template = "_(Reminder: )_"
        val editable = chatEditText.text ?: return
        val existing = editable.indexOf(prefix)
        if (existing >= 0) {
            // Already present — focus inside it instead of nesting another wrapper.
            val cursor = (existing + prefix.length).coerceAtMost(editable.length)
            chatEditText.setSelection(cursor)
            chatEditText.requestFocus()
            chatEditText.showKeyboard()
            return
        }
        val start = chatEditText.selectionStart.coerceAtLeast(0)
        val end = chatEditText.selectionEnd.coerceAtLeast(0)
        editable.replace(minOf(start, end), maxOf(start, end), template)
        val cursor = minOf(start, end) + prefix.length
        chatEditText.setSelection(cursor.coerceAtMost(editable.length))
        chatEditText.requestFocus()
        chatEditText.showKeyboard()
    }

    private fun applyPickedModel(modelString: String) {
        val newModelSupportsWebp = viewModel.supportsWebp(modelString)
        val isStagedImageWebp = selectedImageMime == "image/webp"
        val historyHasWebp = viewModel.hasWebpInHistory()
        val hasImagesInCurrentChat = viewModel.hasImagesInChat()
        if (!newModelSupportsWebp && (isStagedImageWebp || historyHasWebp)) {
            AppToast.makeText(
                requireContext(),
                getString(R.string.model_switch_no_webp),
                AppToast.LENGTH_LONG
            ).show()
        } else if (hasImagesInCurrentChat && !viewModel.isVisionModel(modelString)) {
            AppToast.makeText(
                requireContext(),
                getString(R.string.model_switch_no_vision),
                AppToast.LENGTH_LONG
            ).show()
        } else {
            viewModel.setModel(modelString)
            if (viewModel.activeModelIsLan()) {
                checkLocalNetworkPermission()
            }
        }
    }

    private var pickerPopover: PickerPopover? = null

    private fun newPopover(
        anchor: View = modelNameTextView,
        onOpenChange: (Boolean) -> Unit = { open -> modelNameTextView.isSelected = open }
    ): PickerPopover? {
        val root = view as? FrameLayout ?: return null
        pickerPopover?.dismiss(animated = false)
        return PickerPopover(root, anchor, root.findViewById(R.id.chatBackdrop), chatInputContainer).also { p ->
            pickerPopover = p
            onOpenChange(true)
            p.onDismiss = { onOpenChange(false); if (pickerPopover === p) pickerPopover = null }
        }
    }

    /** Grok-style model popover growing out of the composer pill; "Manage models" opens the full list. */
    private fun showModelPopover() {
        val active = viewModel.activeChatModel.value
        val models = (viewModel.getBuiltInModels() + sharedPreferencesHelper.getCustomModels())
            .distinctBy { it.apiIdentifier }
            .sortedBy { it.displayName.lowercase() }
        val rows = models.map { m ->
            PickerPopover.Row(
                title = m.displayName,
                subtitle = modelSubtitle(m),
                iconRes = when {
                    m.isLANModel -> R.drawable.ic_lan
                    m.isImageGenerationCapable -> R.drawable.ic_imgup
                    m.isVisionCapable -> R.drawable.ic_vision
                    m.isReasoningCapable -> R.drawable.ic_reasoning
                    else -> R.drawable.ic_cloud
                },
                selected = m.apiIdentifier == active,
                onClick = { if (m.apiIdentifier != active) applyPickedModel(m.apiIdentifier) }
            )
        }
        val footer = listOf(
            PickerPopover.Row(
                title = getString(R.string.popover_manage_models),
                subtitle = getString(R.string.popover_manage_models_sub),
                iconRes = R.drawable.ic_tune,
                onClick = { openBotModelPicker() }
            )
        )
        newPopover()?.show(getString(R.string.popover_models_title), rows, footer)
    }

    private fun modelSubtitle(m: LlmModel): String {
        val parts = ArrayList<String>()
        parts += if (m.isLANModel) getString(R.string.popover_model_local)
            else m.apiIdentifier.substringBefore('/', "").ifBlank { getString(R.string.popover_model_cloud) }
        if (m.isReasoningCapable) parts += getString(R.string.popover_cap_reasoning)
        if (m.isVisionCapable) parts += getString(R.string.popover_cap_vision)
        if (m.isImageGenerationCapable) parts += getString(R.string.popover_cap_image)
        if (m.isTranscription) parts += getString(R.string.popover_cap_audio)
        if (m.isFree && !m.isLANModel) parts += getString(R.string.popover_cap_free)
        return parts.joinToString(" · ")
    }

    /** RP pill: switch character in place, plus the roleplay home and the RP model. */
    private fun showCharacterPopover() {
        val repo = viewModel.getRpRepository()
        viewLifecycleOwner.lifecycleScope.launch {
            val chars = repo.getAllCharactersOnce().sortedByDescending { it.updatedAt }
            if (view == null) return@launch
            val activeId = viewModel.activeRpCharacter.value?.id
            val ctx = requireContext()
            val rows = chars.map { c ->
                val file = RpAvatarStorage.avatarFile(ctx, c.id)
                PickerPopover.Row(
                    title = c.name,
                    subtitle = c.personality.ifBlank { c.scenario }.lineSequence().firstOrNull()?.take(80),
                    avatar = file.takeIf { it.exists() },
                    monogram = c.name.trim().take(1).uppercase().ifEmpty { "?" },
                    selected = c.id == activeId,
                    onClick = { if (c.id != activeId) startRpWith(c) }
                )
            }
            val model = viewModel.activeChatModel.value?.let { viewModel.getModelDisplayName(it) }
            val footer = listOf(
                PickerPopover.Row(
                    title = getString(R.string.popover_all_characters),
                    subtitle = getString(R.string.popover_all_characters_sub),
                    iconRes = R.drawable.ic_person,
                    onClick = { openRpCharacterLibrary() }
                ),
                PickerPopover.Row(
                    title = getString(R.string.popover_rp_model),
                    subtitle = model,
                    iconRes = R.drawable.ic_tune,
                    onClick = { openBotModelPicker() }
                )
            )
            newPopover()?.show(getString(R.string.popover_characters_title), rows, footer)
        }
    }

    /** Character pill: the panel for the active character, or the picker when there's none yet. */
    private fun showRpCharacterPanel() {
        val llm = sharedPreferencesHelper.isRpLlmMode()
        val character = viewModel.activeRpCharacter.value
        if (character == null && !llm) {
            showCharacterPopover()
            return
        }
        val memoryId = if (llm) null else character?.id
        val title = if (llm) getString(R.string.rp_llm_speaker) else character!!.name
        val subtitle = if (llm) "" else character!!.personality.ifBlank { character.scenario }
            .lineSequence().firstOrNull().orEmpty()
        val memory = sharedPreferencesHelper.getRpMemory(memoryId)
        val hasMemory = memory.isNotBlank()
        val personaName = sharedPreferencesHelper.getRpPersonaName()
        val tiles = buildList {
            add(RpCharacterPanel.Tile(R.string.rp_panel_memory, R.drawable.ic_memory, on = hasMemory || viewModel.currentRpFacts().isNotBlank(), preview = memory.takeIf { hasMemory } ?: viewModel.currentRpFacts().takeIf { it.isNotBlank() }) {
                menuButton.post { showRpMemoryMenu(memoryId, title) }
            })
            add(RpCharacterPanel.Tile(R.string.rp_panel_history, R.drawable.rp_ic_archive) { openHistoryPanel() })
            val voice = sharedPreferencesHelper.getRpVoice(memoryId)
            val tts = if (::textToSpeech.isInitialized) textToSpeech else null
            val tweaks = listOfNotNull(
                when { voice.pitch < 1f -> getString(R.string.rp_voice_low); voice.pitch > 1f -> getString(R.string.rp_voice_high); else -> null },
                when { voice.rate < 1f -> getString(R.string.rp_voice_slow); voice.rate > 1f -> getString(R.string.rp_voice_fast); else -> null },
            )
            val voiceName = RpVoiceDialog.label(this@ChatFragment, tts, voice.name)
            val voiceLabel = if (voiceName == null && tweaks.isEmpty()) null
                else (listOf(voiceName ?: getString(R.string.rp_voice_default)) + tweaks).joinToString(" · ")
            add(RpCharacterPanel.Tile(R.string.rp_panel_voice, R.drawable.ic_volume_up, on = voiceLabel != null, preview = voiceLabel) {
                RpVoiceDialog.show(this@ChatFragment, title, tts, voice) { sharedPreferencesHelper.saveRpVoice(memoryId, it) }
            })
            val layout = sharedPreferencesHelper.getRpLayout(memoryId)
            add(RpCharacterPanel.Tile(R.string.rp_panel_layout, R.drawable.ic_rp_layout, preview = getString(layoutLabel(layout))) {
                menuButton.post { showRpLayoutPicker(memoryId, layout) }
            })
            if (character != null && !llm) {
                val slot = BackgroundPhoto.slotForCharacter(character.id)
                val wallpaper = BackgroundPhoto.file(requireContext(), slot).takeIf { it.isFile }
                add(RpCharacterPanel.Tile(R.string.rp_panel_wallpaper, R.drawable.ic_gallery, on = wallpaper != null, image = wallpaper) {
                    if (wallpaper == null) pickRpWallpaperFor(character.id)
                    else menuButton.post { showRpWallpaperMenu(character) }
                })
            }
            add(RpCharacterPanel.Tile(R.string.rp_panel_persona, R.drawable.rp_ic_persona, preview = personaName.ifBlank { null }) { pushRp(RpPersonaFragment.newInstance()) })
            add(RpCharacterPanel.Tile(R.string.rp_panel_style, R.drawable.ic_sliders) { pushRp(RpSettingsFragment.newInstance()) })
            val pinnedId = if (character != null && !llm) sharedPreferencesHelper.getRpLorebookId(character.id) else null
            add(RpCharacterPanel.Tile(R.string.rp_panel_lore, R.drawable.rp_ic_book, on = pinnedId != null) {
                menuButton.post { showRpLorePicker(if (llm) null else character?.id) }
            })
            if (character != null && !llm) {
                add(RpCharacterPanel.Tile(R.string.rp_panel_edit, R.drawable.ic_edit) { pushRp(RpCharacterEditFragment.newInstance(character.id)) })
                add(RpCharacterPanel.Tile(R.string.rp_panel_new_chat, R.drawable.ic_new_chat, header = true) { startRpWith(character) })
            }
            add(RpCharacterPanel.Tile(R.string.rp_panel_switch, R.drawable.rp_ic_characters, header = true) { menuButton.post { showCharacterPopover() } })
        }
        RpCharacterPanel.show(this, if (llm) null else character, title, subtitle, tiles)
    }

    /** Lore tile: pin a book to this character, or open the library when there is nothing to pin. */
    private fun showRpLorePicker(characterId: Long?) {
        if (characterId == null) {
            pushRp(RpLorebookLibraryFragment.newInstance())
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val books = viewModel.getRpRepository().getAllLorebooksOnce()
            if (view == null) return@launch
            if (books.isEmpty()) {
                pushRp(RpLorebookLibraryFragment.newInstance())
                return@launch
            }
            val pinned = sharedPreferencesHelper.getRpLorebookId(characterId)
            val active = books.firstOrNull { it.isActive }
            val rows = buildList {
                add(
                    PickerPopover.Row(
                        title = getString(R.string.rp_lore_use_active),
                        subtitle = active?.name ?: getString(R.string.rp_ui_lore_none),
                        iconRes = R.drawable.rp_ic_book,
                        selected = pinned == null
                    ) { sharedPreferencesHelper.saveRpLorebookId(characterId, null) }
                )
                books.forEach { book ->
                    add(
                        PickerPopover.Row(
                            title = book.name,
                            subtitle = if (book.isActive) getString(R.string.rp_lore_active_badge) else null,
                            iconRes = R.drawable.rp_ic_book,
                            selected = book.id == pinned
                        ) { sharedPreferencesHelper.saveRpLorebookId(characterId, book.id) }
                    )
                }
            }
            val footer = listOf(
                PickerPopover.Row(
                    title = getString(R.string.rp_lore_edit),
                    iconRes = R.drawable.ic_edit,
                    onClick = { pushRp(RpLorebookLibraryFragment.newInstance()) }
                )
            )
            newPopover()?.show(getString(R.string.rp_panel_lore), rows, footer)
        }
    }

    private fun layoutLabel(layout: String) = when (layout) {
        SharedPreferencesHelper.RP_LAYOUT_BUBBLES -> R.string.rp_layout_bubbles
        SharedPreferencesHelper.RP_LAYOUT_BOOK -> R.string.rp_layout_book
        else -> R.string.rp_layout_classic
    }

    private fun showRpLayoutPicker(characterId: Long?, current: String) {
        val options = listOf(
            Triple(SharedPreferencesHelper.RP_LAYOUT_CLASSIC, R.string.rp_layout_classic, R.string.rp_layout_classic_sub),
            Triple(SharedPreferencesHelper.RP_LAYOUT_BUBBLES, R.string.rp_layout_bubbles, R.string.rp_layout_bubbles_sub),
            Triple(SharedPreferencesHelper.RP_LAYOUT_BOOK, R.string.rp_layout_book, R.string.rp_layout_book_sub),
        )
        val rows = options.map { (key, title, sub) ->
            PickerPopover.Row(getString(title), getString(sub), R.drawable.ic_rp_layout, selected = key == current) {
                sharedPreferencesHelper.saveRpLayout(characterId, key)
                chatAdapter.rpLayout = key
            }
        }
        newPopover()?.show(getString(R.string.rp_panel_layout), rows, emptyList())
    }

    private fun pickRpWallpaperFor(characterId: Long) {
        rpWallpaperFor = characterId
        pickRpWallpaper.launch(
            androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun showRpWallpaperMenu(character: RpCharacter) {
        val slot = BackgroundPhoto.slotForCharacter(character.id)
        val rows = listOf(
            PickerPopover.Row(getString(R.string.rp_wallpaper_change), getString(R.string.rp_wallpaper_change_sub, character.name), R.drawable.ic_gallery) {
                pickRpWallpaperFor(character.id)
            },
            PickerPopover.Row(getString(R.string.rp_wallpaper_remove), getString(R.string.rp_wallpaper_remove_sub), R.drawable.ic_code_trash) {
                BackgroundPhoto.delete(requireContext(), slot)
            },
        )
        newPopover()?.show(getString(R.string.rp_panel_wallpaper), rows, emptyList())
    }

    /** Before reading aloud: the active character's voice in Roleplay, the phone's default elsewhere. */
    private fun applyReadAloudVoice() {
        if (!::textToSpeech.isInitialized) return
        val tts = textToSpeech
        val rp = viewModel.isRpMode()
        val llm = sharedPreferencesHelper.isRpLlmMode()
        val id = viewModel.activeRpCharacter.value?.id
        val v = if (rp && (llm || id != null)) sharedPreferencesHelper.getRpVoice(if (llm) null else id) else null
        runCatching {
            val voice = v?.name?.let { n -> RpVoiceDialog.voicesFor(tts).firstOrNull { it.name == n } } ?: tts.defaultVoice
            if (voice != null && tts.voice?.name != voice.name) tts.voice = voice
            tts.setPitch(v?.pitch ?: 1f)
            tts.setSpeechRate(v?.rate ?: 1f)
        }
    }

    private fun showRpMemoryMenu(characterId: Long?, name: String) {
        val rows = listOf(
            PickerPopover.Row(
                title = getString(R.string.rp_memory_note),
                subtitle = getString(R.string.rp_memory_hint),
                iconRes = R.drawable.ic_memory
            ) { editRpMemory(characterId, name) },
            PickerPopover.Row(
                title = getString(R.string.rp_facts_title),
                subtitle = getString(R.string.rp_facts_hint),
                iconRes = R.drawable.ic_memory
            ) { editRpFacts(name) }
        )
        newPopover()?.show(getString(R.string.rp_panel_memory), rows, emptyList())
    }

    private fun editRpFacts(name: String) {
        GrokInputDialog.show(
            fragment = this,
            title = getString(R.string.rp_facts_title) + " · " + name,
            hint = getString(R.string.rp_facts_hint),
            initialText = viewModel.currentRpFacts(),
            confirmText = getString(R.string.rp_memory_save),
            multiline = true
        ) { text -> viewModel.saveCurrentRpFacts(text) }
    }

    private fun editRpMemory(characterId: Long?, name: String) {
        GrokInputDialog.show(
            fragment = this,
            title = getString(R.string.rp_memory_title, name),
            hint = getString(R.string.rp_memory_hint),
            initialText = sharedPreferencesHelper.getRpMemory(characterId),
            confirmText = getString(R.string.rp_memory_save),
            multiline = true
        ) { text -> sharedPreferencesHelper.saveRpMemory(characterId, text) }
    }

    private fun pushRp(fragment: Fragment) {
        hideKeyboard()
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun startRpWith(character: RpCharacter) {
        val start = { carry: Boolean -> viewModel.startRpChatWithCharacter(character, carry) }
        if (viewModel.currentRpFacts().isNotBlank()) {
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_facts_choice_title),
                message = getString(R.string.rp_facts_choice_body),
                confirmText = getString(R.string.rp_facts_carry),
                onConfirm = { start(true) },
                destructive = false,
                cancelText = getString(R.string.rp_facts_fresh),
                onCancel = { start(false) }
            )
        } else if (viewModel.rpStartChatNeedsConfirm()) {
            GrokConfirmDialog.show(
                fragment = this,
                title = getString(R.string.rp_new_chat_title),
                message = getString(R.string.rp_new_chat_body, character.name),
                confirmText = getString(R.string.rp_new_chat_confirm),
                onConfirm = { start(false) },
                destructive = false
            )
        } else {
            start(false)
        }
    }

    fun openRpCharacterLibrary() {
        hideKeyboard()
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, RpCharacterLibraryFragment.newInstance())
            .addToBackStack(RpCharacterLibraryFragment.BACK_STACK_TAG)
            .commit()
    }

    private fun openBotModelPicker() {
        val picker = BotModelPickerFragment().apply {
            onModelSelected = { modelString -> applyPickedModel(modelString) }
        }
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, picker)
            .addToBackStack(null)
            .commit()
    }

    /** Empty chat (Chat and Roleplay alike): only the large gray mark, Grok-style. No copy. */
    private fun bindEmptyState(@Suppress("UNUSED_PARAMETER") rp: Boolean) {
        val root = view ?: return
        listOf(R.id.emptyGreeting, R.id.emptySubtitle, R.id.emptyAction, R.id.rpHero).forEach {
            root.findViewById<View>(it)?.visibility = View.GONE
        }
        centerWatermarkIcon.visibility = View.VISIBLE
    }

    fun openRpHub() {
        hideKeyboard()
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .add(R.id.fragment_container, RpHubFragment.newInstance())
            .addToBackStack(RpHubFragment.BACK_STACK_TAG)
            .commit()
    }

    private var indicatorPlaced = false
    private var indicatorTargetX = Float.NaN

    /**
     * Slides the short underline beneath the active mode tab (Grok-style), springing between
     * tabs. Position is derived from the tab's text bounds, and re-derived on every layout pass of
     * the tab row (rotation, font changes), so it can never drift off the word.
     */
    /** Where the tab underline sits under [tab], or null before layout. */
    private fun indicatorXFor(tab: TextView): Float? {
        if (tab.width == 0 || modeTabIndicator.width == 0) return null
        val row = tab.parent as View
        val visibleTextW = (tab.width - tab.totalPaddingLeft - tab.totalPaddingRight).toFloat().coerceAtLeast(0f)
        val textW = minOf(tab.paint.measureText(tab.text.toString()), visibleTextW)
        val contentLeft = tab.totalPaddingLeft + (visibleTextW - textW) / 2f
        return row.left + tab.left + contentLeft + (textW - modeTabIndicator.width) / 2f
    }

    private fun placeModeTabIndicator(animate: Boolean) {
        if (!::modeTabIndicator.isInitialized) return
        // While a page is under the finger (or settling) the pager drives the underline.
        if (pagerBusy) return
        val tab = when {
            ::codeMode.isInitialized && codeMode.isActive -> codeMode.tab
            tabRoleplay.isSelected -> tabRoleplay
            else -> tabChat
        }
        val x = indicatorXFor(tab) ?: return
        // A layout pass mid-spring toward the same spot must not cut the animation short.
        if (!animate && kotlin.math.abs(x - indicatorTargetX) < 0.5f) return
        indicatorTargetX = x
        modeTabIndicator.animate().cancel()
        if (animate && indicatorPlaced && Motion.areAnimationsEnabled(requireContext())) {
            modeTabIndicator.animate().translationX(x)
                .setDuration(420).setInterpolator(Motion.spring).start()
        } else {
            modeTabIndicator.translationX = x
        }
        indicatorPlaced = true
    }

    private fun applyRpSwipeChrome(nav: ChatViewModel.RpSwipeNav?) {
        if (!::rpSwipeBar.isInitialized) return
        if (nav == null || !viewModel.isRpMode()) {
            rpSwipeBar.visibility = View.GONE
            return
        }
        val awaiting = viewModel.isAwaitingResponse.value == true
        rpSwipeBar.visibility = View.VISIBLE
        rpSwipeCounter.text = "${nav.index}/${nav.total}"
        // Keep bar visible during regen so the index stays readable, but block interaction.
        rpSwipePrevButton.isEnabled = nav.canPrev && !awaiting
        rpSwipeNextButton.isEnabled = nav.canNext && !awaiting
    }

    private fun updateRpChrome() {
        val rp = viewModel.isRpMode()
        chatAdapter.isRpMode = rp
        tabChat.isSelected = !rp
        tabRoleplay.isSelected = rp
        if (::codeMode.isInitialized) codeMode.selectTabs()
        placeModeTabIndicator(animate = true)
        bindEmptyState(rp)
        systemMessageButton.visibility = if (rp) View.GONE else View.VISIBLE
        menuButton.visibility = View.VISIBLE
        restoreAttachPlusIcon()
        menuButton.contentDescription = getString(
            if (rp) R.string.rp_plus_a11y else R.string.attach_content_description
        )
        // Reminder / streaming live in the + menu now; the loose chip row is gone.
        rpComposerExtras.visibility = View.GONE
        if (!rp) {
            rpSwipeBar.visibility = View.GONE
        }
        updateSendButtonChrome()
        val activeChar = viewModel.activeRpCharacter.value
        val llm = sharedPreferencesHelper.isRpLlmMode()
        // Per-character look: its own wallpaper over the app background, and its chat layout.
        ambientBackground?.photoSlot = if (rp && activeChar != null && !llm) BackgroundPhoto.slotForCharacter(activeChar.id) else null
        chatAdapter.rpLayout = if (rp) sharedPreferencesHelper.getRpLayout(if (llm) null else activeChar?.id) else SharedPreferencesHelper.RP_LAYOUT_CLASSIC
        if (rp && activeChar != null && !llm) {
            chatAdapter.rpSpeakerName = activeChar.name
            chatAdapter.rpSpeakerAvatarUri = activeChar.photoUri
            chatAdapter.rpSpeakerAvatarFile = RpAvatarStorage.avatarFile(requireContext(), activeChar.id)
            chatAdapter.refreshRpSpeakerAvatars()
        } else if (rp && llm) {
            chatAdapter.rpSpeakerName = getString(R.string.rp_llm_speaker)
            chatAdapter.rpSpeakerAvatarUri = null
            chatAdapter.rpSpeakerAvatarFile = null
            chatAdapter.refreshRpSpeakerAvatars()
        } else {
            chatAdapter.rpSpeakerName = null
            chatAdapter.rpSpeakerAvatarUri = null
            chatAdapter.rpSpeakerAvatarFile = null
        }
        reflectToolButtons()
        if (rp) {
            val hadAttachments = selectedImageBytes != null ||
                selectedAudioBytes != null ||
                pendingFiles.isNotEmpty()
            selectedImageBytes = null
            selectedImageMime = null
            selectedAudioBytes = null
            selectedAudioFormat = null
            attachmentPreviewContainer.visibility = View.GONE
            pendingFiles.clear()
            updateAttachmentButton()
            if (hadAttachments) {
                AppToast.makeText(
                    requireContext(),
                    getString(R.string.rp_attachments_disabled),
                    AppToast.LENGTH_SHORT
                ).show()
            }
            applyModelCapabilityChrome(viewModel.activeChatModel.value)
            presetsButton.visibility = View.GONE
            topPresetsButton.visibility = View.GONE
            presetsButton2.visibility = View.GONE
            val charName = activeChar?.name
            modelNameTextView.text = when {
                llm -> getString(R.string.rp_llm_chip)
                !charName.isNullOrBlank() -> charName
                else -> getString(R.string.rp_characters_title)
            }
            modelNameTextView.contentDescription = when {
                llm -> getString(R.string.rp_model_chip_a11y_llm)
                !charName.isNullOrBlank() -> getString(R.string.rp_model_chip_a11y_character, charName)
                else -> getString(R.string.rp_model_chip_a11y_empty)
            }
            applyRpComposerHint()
        } else {
            viewModel.activeChatModel.value?.let { modelNameTextView.text = viewModel.getModelDisplayName(it) }
            chatEditText.hint = getString(R.string.grok_composer_hint)
            applyModelCapabilityChrome(viewModel.activeChatModel.value)
            updateModelSourceIndicator()
        }
        updateExtendedTopBarVisibility(sharedPreferencesHelper.getExtendedTopBarEnabled())
        updateComposerAccessoryVisibility()
    }

    /** Ask shows the saved tool prefs. Roleplay never does, and does not write them. */
    private fun reflectToolButtons() {
        if (!::webSearchButton.isInitialized || !::toolsButton.isInitialized) return
        if (!::topWebSearchButton.isInitialized || !::topToolsButton.isInitialized) return
        val chrome = if (viewModel.isRpMode()) rpMode else askMode
        val web = chrome.webSearchSelected(viewModel.isWebSearchEnabled.value == true)
        val tools = chrome.toolsSelected(viewModel.isToolsEnabled.value == true)
        webSearchButton.isSelected = web
        topWebSearchButton.isSelected = web
        toolsButton.isSelected = tools
        topToolsButton.isSelected = tools
    }

    /** Vision / transcription / image-gen chrome for attach + gen; forced off in RP. */
    private fun applyModelCapabilityChrome(model: String?) {
        if (viewModel.isRpMode()) {
            plusButton.setIconResource(R.drawable.ic_imgup)
            plusButton.icon?.alpha = 102
            plusButton.isEnabled = false
            plusButton.contentDescription = getString(R.string.rp_attach_disabled_a11y)
            attachmentButton.isEnabled = false
            attachmentButton.icon?.alpha = 102
            attachmentButton.contentDescription = getString(R.string.rp_attach_disabled_a11y)
            genButton.visibility = View.GONE
            return
        }
        if (model == null) {
            plusButton.setIconResource(R.drawable.ic_imgup)
            plusButton.icon?.alpha = 102
            plusButton.isEnabled = false
            plusButton.contentDescription = getString(R.string.cd_attach_media)
            attachmentButton.isEnabled = true
            attachmentButton.icon?.alpha = 255
            attachmentButton.contentDescription = getString(R.string.cd_attach_file)
            genButton.visibility = View.GONE
            return
        }
        when {
            viewModel.isTranscriptionModel(model) -> {
                plusButton.setIconResource(R.drawable.ic_uprec)
                plusButton.icon?.alpha = 255
                plusButton.isEnabled = true
                plusButton.contentDescription = getString(R.string.cd_attach_media)
            }
            viewModel.isVisionModel(model) -> {
                plusButton.setIconResource(R.drawable.ic_imgup)
                plusButton.icon?.alpha = 255
                plusButton.isEnabled = true
                plusButton.contentDescription = getString(R.string.cd_attach_media)
            }
            else -> {
                plusButton.setIconResource(R.drawable.ic_imgup)
                plusButton.icon?.alpha = 102
                plusButton.isEnabled = false
                plusButton.contentDescription = getString(R.string.cd_attach_media)
            }
        }
        attachmentButton.isEnabled = true
        attachmentButton.icon?.alpha = 255
        attachmentButton.contentDescription = getString(R.string.cd_attach_file)
        genButton.visibility = if (viewModel.isImageGenerationModel(model)) View.VISIBLE else View.GONE
    }

    private fun substituteVariables(input: String): String {
        if (!input.contains("{{ox")) return input  // 🔥 EARLY EXIT: Instant if no vars (99% cases)

        val now = Date()
        return input.replace(Regex("""\{\{ox(\w+)\}\}""")) { match ->
            val varName = match.groupValues[1].lowercase()
            when (varName) {
                "date" -> dateFmt.format(now)
                "time" -> timeFmt.format(now)
                "datetime" -> datetimeFmt.format(now)
                "hdt" -> humanFmt.format(now)
                else -> match.value
            }
        }
    }

    fun captureViewToBitmapNow(view: View): Bitmap? {
        // If the view is already laid out, use its current size.
        if (view.width > 0 && view.height > 0) {
            val bitmap = createBitmap(view.width, view.height)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            return bitmap
        }

        // If not laid out, measure and layout manually.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(view.layoutParams.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        val measuredHeight = view.measuredHeight
        val heightSpecExact = View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpecExact)

        val bitmap = createBitmap(view.measuredWidth, view.measuredHeight)
        val canvas = Canvas(bitmap)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.draw(canvas)
        return bitmap
    }
    private fun printChatHtml(htmlContent: String) {
        val printManager = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        val currentModel = viewModel._activeChatModel.value ?: "Unknown"
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val dateTime = sdf.format(Date())
        val adapterTitle = "${currentModel.replace("/", "-")}_$dateTime"  // ✅ "x-ai-grok-4.1-fast_2024-10-05_14-30.pdf"
        val jobName = "Chat History"

        val webView = WebView(requireContext()).apply {
            settings.apply {
                javaScriptEnabled = false
                loadWithOverviewMode = true
                useWideViewPort = true
                defaultTextEncodingName = "utf-8"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.createPrintDocumentAdapter(adapterTitle)?.let { adapter ->
                        printManager.print(jobName, adapter, null)
                    }
                }
            }

            // ✅ FIXED: HR lines HIDDEN in print (no sep after user OR assistant). Spacers ONLY via margins: tiny after user (flush to assistant), 2em ONLY after assistant. Clean flow!
            val fullHtml = """
        <!DOCTYPE html>
        <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Chat History</title>
            <style>
                * { box-sizing: border-box; }
                body { 
                    margin: 40px 20px;  
                    padding: 0;         
                    max-width: 100%;    
                    font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif,"Apple Color Emoji","Segoe UI Emoji";
                    font-size: 16px; line-height: 1.5; color: #24292f; background: white;
                }
                .markdown-body { font-size: 16px; line-height: 1.5; }
                h1 { 
                    color: #24292f !important; font-size: 2em !important; font-weight: 600 !important; 
                    text-decoration: underline !important;
                    border-bottom: none !important;
                    padding-bottom: .3em !important; margin: 0 0 1em 0 !important; 
                }
                a { color: #0366d6; text-decoration: none; }
                a:hover, a:focus { text-decoration: underline; }
                @media print { a { text-decoration: underline !important; color: #0366d6 !important; } }
                strong { font-weight: 600; }
                pre, code { font-family: 'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace; font-size: 14px; }
                code { background: #f6f8fa; border-radius: 6px; padding: .2em .4em; }
                pre { background: #f6f8fa; border-radius: 6px; padding: 16px; overflow: auto; margin: 1em 0; }
                blockquote { border-left: 4px solid #dfe2e5; color: #6a737d; padding-left: 1em; margin: 1em 0; }
                table { border-collapse: collapse; width: 100%; margin: 1em 0; }
                th, td { border: 1px solid #d0d7de; padding: .75em; text-align: left; }
                th { background: #f6f8fa; font-weight: 600; }
                hr { border: none; border-top: 1px solid #eaecef; height: 0; margin: 1.5em 0; }
                ul, ol { padding-left: 2em; margin: 1em 0; }
                img { max-width: 100%; height: auto; }
                del { color: #bd2c00; }
                input[type="checkbox"] { margin: 0 .25em 0 0; vertical-align: middle; }
                
                /* Screen tweaks */
                h3[style*="28a745"] + div[style*="background"] {
                    background: #f8f9fa; border-left-color: #28a745;
                }
                
                /* ✅ PRINT: NO HR LINES. Margins ONLY for spacers */
                @media print {
                    body { 
                        margin: 0.5in 0.25in !important;  
                        padding: 0 !important;
                        max-width: none !important;
                        font-size: 12pt !important; line-height: 1.5 !important;
                    }
                    h1 { 
                        page-break-after: avoid; 
                        text-decoration: underline !important; 
                        border-bottom: none !important; 
                    }
                    
                    /* ✅ NO SEPARATOR LINES: Hide all HR */
                    hr { 
                        display: none !important;  /* ✅ GONE – no lines after user OR assistant */
                    }
                    
                    /* ✅ SPACERS VIA MARGINS ONLY: Tiny after USER (flush to assistant). 2em ONLY after ASSISTANT */
                    div[style*="margin-bottom: 2em"]:has(h3[style*="0366d6"]) {
                        margin-bottom: 0.25em !important;  /* ✅ User → assistant: tight */
                    }
                    div[style*="margin-bottom: 2em"]:has(h3[style*="28a745"]) {
                        margin-bottom: 2em !important;  /* ✅ Assistant → next user: spacer ONLY here */
                    }
                    
                    /* ✅ ASSISTANT: Plain text (no bg/border) */
                    h3[style*="28a745"] + div[style*="background: #f6f8fa"],
                    h3[style*="28a745"] + div {
                        background: none !important;
                        background-color: transparent !important;
                        border: none !important;
                        border-left: none !important;
                        border-left-color: transparent !important;
                        padding: 0.25em 0.5em !important;
                        border-radius: 0 !important;
                        margin: 0 !important;
                    }
                    
                    /* USER: Keep bg/border */
                    h3[style*="0366d6"] + div[style*="background: #f6f8fa"] {
    padding: 0.05em 0.5em !important;  /* ✅ Tight user bg in print */
}

                    
                    pre { white-space: pre-wrap; }
                    @page { margin: 0.5in; }
                }
            </style>
        </head><body>
            <div class="markdown-body">$htmlContent</div>
        </body></html>
        """.trimIndent()

            loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
        }
    }
    private fun hasFolderPermission(): Boolean {
        val uriString = sharedPreferencesHelper.getSafFolderUri() ?: return false

        // Verify the permission is actually still held by the OS
        val treeUri = uriString.toUri()
        val persistedUriPermissions = requireContext().contentResolver.persistedUriPermissions
        return persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
    }
}
