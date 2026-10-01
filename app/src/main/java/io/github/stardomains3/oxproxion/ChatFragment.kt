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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.AudioManager
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import coil.dispose
import coil.load
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import kotlinx.coroutines.Job
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

/** A mode switch's thread load lands within this; the empty-state mark treats it as the switch. */
private const val MODE_LOAD_WINDOW_MS = 1500L

interface OnKeyboardShortcutListener {
    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean
}
class ChatFragment : Fragment(R.layout.fragment_chat), OnKeyboardShortcutListener, HistoryPanelHost {
   // private var isFontUpdate = false
    private var menuClosedByTouch = false
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
    /** True while a scene photo is being encoded into the send, so a second tap cannot send it twice. */
    private var photoSendInFlight = false
    private lateinit var audioPicker: ActivityResultLauncher<Array<String>>
    private var selectedAudioBytes: ByteArray? = null
    private var selectedAudioFormat: String? = null
    private var doVolScroll: Boolean = false
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
    /** Roleplay's landing screen; see [RpChatsHome]. */
    private var rpHome: RpChatsHome? = null
    private var rpHomeOpen = false
    /** Where Roleplay was last left: on the characters list (true) or inside a chat. Swiping back lands there. */
    private var rpResumeAtHome = true
    /** Set when a thread was opened on purpose, so the mode change that follows doesn't put the home over it. */
    private var rpHomeSuppressed = false
    private var rpHomeSessions: List<ChatSession> = emptyList()
    private var rpHomeCharacters: List<RpCharacter> = emptyList()
    private var rpHomeRefresh: Job? = null
    private var rpHomeHidComposer = false
    /** The character sheet, an overlay in this fragment's own view so pages can slide over it and leave it open. */
    private var rpPanel: RpCharacterPanel.Host? = null
    /** Back-stack depth when a panel page was opened; the sheet refreshes when the stack is back to it. */
    private var rpPanelDepth = 0
    private val rpPanelBackStackListener = androidx.fragment.app.FragmentManager.OnBackStackChangedListener {
        if (rpPanel?.isShowing == true && parentFragmentManager.backStackEntryCount <= rpPanelDepth) refreshRpPanel()
    }
    private var lastSeenChatMode: ChatMode? = null
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
    private lateinit var backcopyButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var progressBar: View
    private lateinit var pdfChatButton: MaterialButton
    private lateinit var systemMessageButton: MaterialButton
    private lateinit var topBarLayout: ConstraintLayout
    private lateinit var streamButton: MaterialButton
    private lateinit var reasoningButton: MaterialButton
    private val ttsAvailable = true

    /** The shared engine's callbacks (a binder thread). The voice page's preview utterances are not ours. */
    private val ttsProgress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            if (utteranceId?.startsWith("TTS_SAVE_") == true) {
                val parts = utteranceId.split("_")
                if (parts.size == 4 && parts[0] == "TTS" && parts[1] == "SAVE") {
                    val timestamp = parts[2].toLongOrNull() ?: return
                    val position = parts[3].toIntOrNull() ?: return
                    // Binder thread: the fragment may be gone by now, so no requireContext().
                    val appContext = this@ChatFragment.context?.applicationContext ?: return
                    val tempFile = File(appContext.cacheDir, "temp_tts_${timestamp}.wav")
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

                            val resolver = appContext.contentResolver
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

                        if (success) {
                            noticeFromAnyThread(R.string.tts_saved_to_downloads, fileName)
                        } else {
                            noticeFromAnyThread(R.string.tts_save_failed)
                        }
                    }
                }
            }
            else if (utteranceId == TTS_SPEAK_ID) {
                activity?.runOnUiThread { onSpeechFinished() }  // Run on main thread
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId?.startsWith("TTS_SAVE_") == true) {
                noticeFromAnyThread(R.string.toast_tts_synthesis_error)
            }
            else if (utteranceId == TTS_SPEAK_ID) {
                noticeFromAnyThread(R.string.toast_tts_engine_error)
                activity?.runOnUiThread { onSpeechFinished() }
            }
        }
    }
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
    private lateinit var centerWatermarkIcon: LiquidMarkView
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var removeAttachmentButton: ImageButton
    private lateinit var headerContainer: LinearLayout
    private var overlayView: View? = null
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private var currentCameraUri: Uri? = null
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var localNetworkPermissionLauncher: ActivityResultLauncher<String>
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
    private var isScrollersEnabled = false
    private var isScrollProgressEnabled = false
    private var lastContentLength = 0
    private lateinit var textFilePicker: ActivityResultLauncher<String>
    private val pendingFiles = mutableListOf<AttachedFile>()
    private val MAX_FILE_SIZE = 3 * 1024 * 1024 // 3MB total
    private val MAX_SINGLE_FILE_SIZE = 1024 * 1024 // 1MB per file
    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        parentFragmentManager.addOnBackStackChangedListener(rpPanelBackStackListener)
        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())
        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCamera()
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_camera_permission))
            }
        }
        localNetworkPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            // Granting needs no word: the system sheet closing is the answer.
            if (!isGranted) GlassNotice.show(requireContext(), getString(R.string.toast_lan_permission))
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

            currentCameraUri = null  // Always reset after callback
            val resolver = requireContext().contentResolver
            if (result.resultCode == Activity.RESULT_OK && imageUri != null) {
                // A 12 MB photo read and delete on the main thread stalls the sheet closing.
                viewLifecycleOwner.lifecycleScope.launch {
                    val rawBytes = withContext(Dispatchers.IO) {
                        try {
                            // Read raw bytes first (fresh stream, one-time read)
                            resolver.openInputStream(imageUri)?.use { it.readBytes() }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (rawBytes == null) {
                        GlassNotice.show(requireContext(), getString(R.string.toast_failed_read_image))
                        return@launch
                    }
                    if (rawBytes.size > 12_000_000) {
                        GlassNotice.show(requireContext(), getString(R.string.toast_image_too_large))
                        withContext(Dispatchers.IO) { runCatching { resolver.delete(imageUri, null, null) } }
                        return@launch
                    }
                    // The gallery copy stays. What we send is upright and small; EXIF rotation
                    // would otherwise reach the model sideways.
                    stagePickedPhoto(rawBytes)
                    resolver.notifyChange(imageUri, null)
                }
            } else {
                // Cancelling needs no word; a failed capture does.
                if (result.resultCode != Activity.RESULT_CANCELED) {
                    GlassNotice.show(requireContext(), getString(R.string.toast_capture_failed))
                }
                imageUri?.let { uri ->
                    // Clean up the placeholder.
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { resolver.delete(uri, null, null) }
                    }
                }
            }
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

                GlassNotice.show(requireContext(), getString(R.string.toast_folder_granted))
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
            // One at a time, with the size and type checks inside processTextFile.
            uris.forEach { uri -> processTextFile(uri) }
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
        setupRpHome(view, savedInstanceState)
        modelNameShell = view.findViewById(R.id.modelNameShell)
        tabChat = view.findViewById(R.id.tabChat)
        tabRoleplay = view.findViewById(R.id.tabRoleplay)
        modeTabIndicator = view.findViewById(R.id.modeTabIndicator)
        // A rebuilt view starts the underline at x=0; the old target belongs to the old view.
        indicatorPlaced = false
        indicatorTargetX = Float.NaN
        pager = null
        pagerOrigin = null
        pagerBusy = false
        attachmentPreviewContainer = view.findViewById(R.id.attachmentPreviewContainer)
        previewImageView = view.findViewById(R.id.previewImageView)
        centerWatermarkIcon = view.findViewById(R.id.centerWatermarkIcon)
        applyChatMark()
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
        // The engine itself starts on first use (TtsHolder); the screen only listens and holds it open.
        TtsHolder.hold(this)
        TtsHolder.addListener(ttsProgress)
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
            .textSetter { tv, text, type, done -> ChatAdapter.setReplyText(tv, text, type); done.run() }
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
        // Formatters are created once; scrolling must not allocate them.
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
        // The extended top bar's toggles and the return buttons under the bar are 40dp discs.
        TouchTargets.expand(
            extendedTopBarContainer,
            view.findViewById(R.id.topReasoningButton), view.findViewById(R.id.topWebSearchButton),
            view.findViewById(R.id.topStreamButton), view.findViewById(R.id.topConvoButton),
            view.findViewById(R.id.topToolsButton), view.findViewById(R.id.topPresetsButton),
            view.findViewById(R.id.topSettingsButton)
        )
        TouchTargets.expand(topBarLayout, backcopyButton, backButton, homeButton)

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
                updateComposerAccessoryVisibility()
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
                        GlassNotice.show(requireContext(), getString(R.string.toast_streaming_music))
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
                    viewModel.setPendingUserImageUri(null)
                    updateSendButtonChrome()
                    if (viewModel.isRpMode()) applyRpComposerHint()
                    GlassNotice.show(requireContext(), getString(R.string.toast_image_removed_no_vision))
                }
                // Clear staged audio if model doesn't support transcription
                if (selectedAudioBytes != null && !viewModel.isTranscriptionModel(model)) {
                    selectedAudioBytes = null
                    selectedAudioFormat = null
                    attachmentPreviewContainer.visibility = View.GONE
                    GlassNotice.show(requireContext(), getString(R.string.toast_audio_removed_no_transcription))
                }
            }

            // Update button visibility based on current model and preference
            updateExtendedTopBarVisibility(sharedPreferencesHelper.getExtendedTopBarEnabled())
            updateModelSourceIndicator()
        }

        var appliedComposerMode: ChatMode? = null
        viewModel.chatMode.observe(viewLifecycleOwner) { mode ->
            val nowRp = mode == ChatMode.RP
            if (nowRp && lastSeenChatMode != ChatMode.RP) {
                // Roleplay opens where it was left: the characters list, or the chat you were in.
                // A thread opened on purpose always wins.
                rpHomeOpen = !rpHomeSuppressed && rpResumeAtHome
                rpHomeSuppressed = false
            } else if (!nowRp) {
                if (lastSeenChatMode == ChatMode.RP) rpResumeAtHome = rpHomeOpen
                rpHomeOpen = false
                rpHomeSuppressed = false
            }
            lastSeenChatMode = mode
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
        viewModel.rpRewriteDone.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { done ->
                GlassNotice.show(requireContext(), getString(R.string.rp_rewrite_done), getString(R.string.rp_rewrite_undo)) {
                    viewModel.undoRpRewrite(done)
                }
            }
        }
        viewModel.rpSwipeNav.observe(viewLifecycleOwner) { nav ->
            applyRpSwipeChrome(nav)
        }
        // Code mode (third tab, off until Settings > Modes) lives in its own package; see CodeModeHost.
        codeMode = io.github.stardomains3.oxproxion.code.CodeModeHost(this, view)
        codeMode.onTabsChanged = {
            val rp = viewModel.isRpMode()
            tabChat.isSelected = !codeMode.isActive && !rp
            tabRoleplay.isSelected = !codeMode.isActive && rp
            placeModeTabIndicator(animate = true)
            updateRpHome()
        }
        // Tabs slide like the swipe: both pages side by side, never an empty frame between.
        listOf(tabChat, tabRoleplay, codeMode.tab).forEach { tab ->
            tab.setOnClickListener {
                // The Roleplay tab, tapped while you're in a chat, goes back to the chats list.
                if (tab === tabRoleplay && viewModel.isRpMode() && !codeMode.isActive && pager == null) {
                    hideKeyboard()
                    openRpHome()
                } else {
                    pageToTab(tab)
                }
            }
        }
        tabRoleplay.setOnLongClickListener {
            openRpHub()
            true
        }
        tabRoleplay.contentDescription = getString(R.string.mode_tab_roleplay_a11y)
        refreshModeTabs()
        watchModeSwitches()
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
            chatAdapter.continuingFrom = viewModel.continuationText
            chatAdapter.setMessages(messages)
            restoreListSpot()
            chatRecyclerView.post { updateJumpToBottom() }
            updateSendButtonChrome()
            val hasMessages = messages.isNotEmpty()
            centerWatermarkIcon.isClickable = false
            if(hasMessages){
                // Right after a mode swipe the list is the other mode's thread landing, not a
                // conversation starting: the mark just goes, or it swells on the new page.
                val justSwitched = android.os.SystemClock.uptimeMillis() - modeSwitchedAt < MODE_LOAD_WINDOW_MS
                if (!justSwitched && emptyStateContainer.isVisible && emptyStateContainer.alpha > 0f && Motion.areAnimationsEnabled(requireContext())) {
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
                val wasHidden = !emptyStateContainer.isVisible
                emptyStateContainer.animate().cancel()
                emptyStateContainer.scaleX = 1f
                emptyStateContainer.scaleY = 1f
                emptyStateContainer.visibility = View.VISIBLE
                bindEmptyState(viewModel.isRpMode())
                val landing = pager == null && wasHidden &&
                    android.os.SystemClock.uptimeMillis() - modeSwitchedAt < MODE_LOAD_WINDOW_MS
                if (landing && Motion.areAnimationsEnabled(requireContext())) {
                    // An empty thread arriving after the slide settled fades in instead of popping.
                    emptyStateContainer.alpha = 0f
                    emptyStateContainer.animate().alpha(1f).setDuration(220).setInterpolator(Motion.easeOut).start()
                } else {
                    emptyStateContainer.alpha = 1f
                }
            }
            if(sharedPreferencesHelper.getScrollersPreference()){
                chatRecyclerView.post {
                    val canScrollUp = chatRecyclerView.canScrollVertically(-1)
                    val canScrollDown = chatRecyclerView.canScrollVertically(1)
                    scrollToTopButton.setShownAnimated(canScrollUp)
                    scrollToBottomButton.setShownAnimated(canScrollDown)
                }
            }
            resetChatButton.isVisible = hasMessages
            updateMenuRowVisibilities()
           // pdfChatButton.isVisible = hasMessages
            //copyChatButton.isVisible = hasMessages
            buttonsRow2.isVisible = hasMessages
            view?.findViewById<View>(R.id.exportLabel)?.isVisible = hasMessages
        }

        viewModel.isAwaitingResponse.observe(viewLifecycleOwner) { isAwaiting ->
            chatAdapter.replyInFlight = isAwaiting
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
                        // Ink and the error color are the same gray in this palette, so the chip has nothing to flash.
                        modelNameTextView.setTextColor(baseColor)
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

            if (!isAwaiting &&
                sharedPreferencesHelper.getDisableWebSearchAfterSend() &&
                viewModel.isWebSearchEnabled.value == true) {
                viewModel._isWebSearchEnabled.value = false
                sharedPreferencesHelper.saveWebSearchEnabled(false)
            }
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
                GlassNotice.show(requireContext(), resultMessage)
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
                GlassNotice.show(requireContext(), message)
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
            event.getContentIfNotHandled()?.let { message -> showFolderNotice(message) }
        }
        viewModel.presetAppliedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                updateSystemMessageButtonState()
                convoButton.isSelected = sharedPreferencesHelper.getConversationModeEnabled()
            }
        }
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
        val photo = selectedImageBytes != null
        chatEditText.hint = when {
            llm && photo -> getString(R.string.rp_composer_hint_photo)
            llm -> getString(R.string.rp_composer_hint_llm)
            activeChar != null && photo -> getString(R.string.rp_composer_hint_photo)
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
        val size = (40 * density).toInt()
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
        val dock = root.findViewById<ViewGroup>(R.id.composerDock)
        val code = root.findViewById<View>(R.id.codeModeContainer)
        val fade = root.findViewById<View>(R.id.composerFade)
        val list = root.findViewById<View>(R.id.chatRecyclerView)
        val barTop = topBar.paddingTop
        val dockBottom = dock.paddingBottom
        frame.clipToPadding = false
        // The keyboard is laid out frame by frame from its own animation, never translated: the
        // composer, the transcript's bottom padding and (when the reader is at the newest message)
        // its scroll all move in one layout pass. A translate-then-hand-off scheme always left a
        // frame where one of them was a step behind, which flashed at the end of every slide.
        var imeAnimating = false
        var barsTop = 0
        var barsBottom = 0
        fun applyKb(kb: Int) {
            val bottom = barsBottom + kb
            if (frame.paddingTop != barsTop || frame.paddingBottom != bottom) frame.setPadding(0, barsTop, 0, bottom)
            // Everything in the chat frame keeps its place; only the backdrop bleeds out.
            (backdrop.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                if (lp.topMargin != -barsTop || lp.bottomMargin != -bottom) {
                    lp.topMargin = -barsTop
                    lp.bottomMargin = -bottom
                    backdrop.layoutParams = lp
                }
            }
            if (dock.paddingBottom != dockBottom + bottom) {
                dock.setPadding(dock.paddingLeft, dock.paddingTop, dock.paddingRight, dockBottom + bottom)
            }
            if (code.paddingBottom != bottom) code.setPadding(0, 0, 0, bottom)
            if (kb != imePx) {
                val delta = kb - imePx
                imePx = kb
                layTranscriptForKeyboard(delta)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            barsTop = bars.top
            barsBottom = bars.bottom
            v.setPadding(bars.left, 0, bars.right, 0)
            topBar.setPadding(topBar.paddingLeft, barTop + bars.top, topBar.paddingRight, topBar.paddingBottom)
            // Mid-animation these insets are already the end state: the callback owns the keyboard.
            if (imeAnimating) {
                applyKb(imePx)
            } else {
                imeFollow = !list.canScrollVertically(1)
                applyKb(maxOf(0, ime.bottom - bars.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }
        // The fade under the composer follows where the composer is drawn this frame, not where
        // a posted layout last put it: scaled from its bottom edge, so it never lags a frame
        // behind the keyboard (it used to drop away, or stand a keyboard tall, at the handoff).
        val fadeGap = 28 * resources.displayMetrics.density
        val empty = root.findViewById<ViewGroup>(R.id.emptyStateContainer)
        val emptyContent = empty.getChildAt(0) as ViewGroup
        // Overflow when the room is short draws (then scales) instead of being cut off.
        emptyContent.clipChildren = false
        content.viewTreeObserver.addOnPreDrawListener {
            if (fade.height > 0 && dock.height > 0) {
                var top = dock.height
                for (i in 0 until dock.childCount) {
                    val c = dock.getChildAt(i)
                    if (c.visibility == View.VISIBLE) top = minOf(top, c.top)
                }
                val composerTop = dock.top + top + dock.translationY
                val want = ((fade.parent as View).height - composerTop + fadeGap).coerceAtLeast(1f)
                val scale = want / fade.height
                if (fade.pivotY != fade.height.toFloat()) fade.pivotY = fade.height.toFloat()
                if (abs(fade.scaleY - scale) > 0.001f) fade.scaleY = scale
                fitEmptyState(empty, emptyContent, dock.top + top + dock.translationY, fade.parent as View)
            }
            true
        }
        val imeType = WindowInsetsCompat.Type.ime()
        ViewCompat.setWindowInsetsAnimationCallback(content, object : androidx.core.view.WindowInsetsAnimationCompat.Callback(
            androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        ) {
            override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                if (animation.typeMask and imeType == 0) return
                imeAnimating = true
                imeFollow = !list.canScrollVertically(1)
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                if (imeAnimating) applyKb(maxOf(0, insets.getInsets(imeType).bottom - barsBottom))
                return insets
            }

            override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                if (animation.typeMask and imeType == 0 || !imeAnimating) return
                imeAnimating = false
                val ri = ViewCompat.getRootWindowInsets(content)
                if (ri != null) applyKb(maxOf(0, ri.getInsets(imeType).bottom - barsBottom))
                else ViewCompat.requestApplyInsets(content)
            }
        })
        ViewCompat.requestApplyInsets(content)
    }

    /** Keyboard height above the nav bar that is laid out right now. */
    private var imePx = 0
    /** The transcript's bottom padding without the keyboard: the composer and its margin. */
    private var listChromePad = -1
    /** The reader was at the newest message when the keyboard started moving: keep it in view. */
    private var imeFollow = false

    /**
     * The keyboard moved by [delta]: grow (or shrink) the transcript's bottom padding by it and,
     * when following, move the last message by the same amount, all in the coming layout pass.
     */
    private fun layTranscriptForKeyboard(delta: Int) {
        val list = chatRecyclerView
        if (listChromePad < 0) return
        val pad = listChromePad + imePx
        if (list.paddingBottom != pad) list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, pad)
        placeBottomFloaters()
        if (!imeFollow) return
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        val last = (list.adapter?.itemCount ?: 0) - 1
        val lastView = lm.findViewByPosition(last) ?: return
        val lp = lastView.layoutParams as ViewGroup.MarginLayoutParams
        lm.scrollToPositionWithOffset(last, lm.getDecoratedTop(lastView) - lp.topMargin - list.paddingTop - delta)
    }

    /** The scroll buttons and font controls ride above the composer and the keyboard. */
    private fun placeBottomFloaters() {
        val root = view ?: return
        if (chromeBottom < 0) return
        listOfNotNull<View>(extBG, fontSizeControlsContainer, root.findViewById(R.id.jumpToBottomButton)).forEach { v ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            val base = chromeBaseMargins.getOrPut(v) { lp.bottomMargin }
            if (lp.bottomMargin != base + chromeBottom + imePx) {
                lp.bottomMargin = base + chromeBottom + imePx
                v.layoutParams = lp
            }
        }
    }

    private val emptyLoc = IntArray(2)
    private val chromeLoc = IntArray(2)

    /**
     * The empty-state mark and greeting sit centred in the room between the top bar and the
     * composer as it is drawn this frame, and shrink when that room gets short. Run every frame,
     * so they glide and scale with the keyboard instead of jumping when its posted layout lands.
     */
    private fun fitEmptyState(empty: ViewGroup, content: ViewGroup, composerTop: Float, chromeParent: View) {
        if (!empty.isShown || content.height == 0) return
        empty.getLocationInWindow(emptyLoc)
        chromeParent.getLocationInWindow(chromeLoc)
        val bottom = composerTop + chromeLoc[1] - emptyLoc[1]
        val lp = content.layoutParams as ViewGroup.MarginLayoutParams
        // Its natural height: a short room clamps the column, and the rest overflows below it.
        var natural = 0
        for (i in 0 until content.childCount) {
            val c = content.getChildAt(i)
            if (c.visibility == View.GONE) continue
            val clp = c.layoutParams as ViewGroup.MarginLayoutParams
            natural += c.height + clp.topMargin + clp.bottomMargin
        }
        natural = maxOf(natural, content.height)
        val room = bottom - empty.paddingTop - lp.bottomMargin
        val scale = (room / natural).coerceIn(0.5f, 1f)
        val target = (empty.paddingTop + bottom - lp.bottomMargin) / 2f
        val ty = target - (content.top + natural / 2f)
        content.pivotX = content.width / 2f
        content.pivotY = natural / 2f
        if (abs(content.translationY - ty) > 0.5f) content.translationY = ty
        if (abs(content.scaleX - scale) > 0.001f) {
            content.scaleX = scale
            content.scaleY = scale
        }
    }

    private fun setupGlassChrome(root: View) {
        val backdrop = root.findViewById<GlassBackdropLayout>(R.id.chatBackdrop)
        val topGlass = root.findViewById<View>(R.id.topBarGlass)
        topGlass.background?.mutate()?.alpha = 0
        topBarFade = topGlass.background
        (chatInputContainer as? GlassLinearLayout)?.glass?.source = backdrop
        (headerContainer as? GlassLinearLayout)?.glass?.source = backdrop
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyChromeInsets() }
        topGlass.addOnLayoutChangeListener(relayout)
        root.findViewById<View>(R.id.composerDock).addOnLayoutChangeListener(relayout)
        chatInputContainer.addOnLayoutChangeListener(relayout)
        // The Controls card hangs off the composer: follow it when it moves or resizes.
        chatInputContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (headerContainer.isVisible) headerContainer.post { placeControlsCard() }
        }
        rpComposerExtras.addOnLayoutChangeListener(relayout)
        chatRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateTopBarEdge()
                updateJumpToBottom()
            }
        })
    }

    private var topBarFade: android.graphics.drawable.Drawable? = null

    /** The dim under the floating controls; kept at full strength (see [edgeAlphaForList]). */
    private fun updateTopBarEdge() {
        val fade = topBarFade
            ?: view?.findViewById<View>(R.id.topBarGlass)?.background?.also { topBarFade = it }
            ?: return
        // A page swipe blends the fade itself (applyPagerProgress); the list below is mid-switch.
        if (pager != null) return
        val a = edgeAlphaForList()
        edgeAnimator?.cancel()
        if (abs(fade.alpha - a) > 48 && Motion.areAnimationsEnabled(requireContext())) {
            // A jump (a mode's list landing, a reload) eases over instead of blinking.
            edgeAnimator = ValueAnimator.ofInt(fade.alpha, a).apply {
                duration = 240
                interpolator = Motion.easeOut
                addUpdateListener { fade.alpha = it.animatedValue as Int }
                start()
            }
        } else if (fade.alpha != a) {
            fade.alpha = a
        }
    }

    private var edgeAnimator: ValueAnimator? = null

    /** The dim under the top bar stays on, empty page or not, so the tabs always read apart. */
    private fun edgeAlphaForList(): Int = 255

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
        // The keyboard part is laid out frame by frame (layTranscriptForKeyboard), not from here.
        val bottom = (dock.height - contentTop - imePx).coerceAtLeast(0)
        if (top == chromeTop && bottom == chromeBottom) return
        val grew = if (chromeBottom >= 0) bottom - chromeBottom else 0
        chromeTop = top
        chromeBottom = bottom
        // Post: we are inside a layout pass; padding/margin changes request another one.
        root.post {
            if (view == null) return@post
            val d = resources.displayMetrics.density
            val atBottom = !chatRecyclerView.canScrollVertically(1)
            listChromePad = bottom + (14 * d).toInt()
            chatRecyclerView.setPadding(
                chatRecyclerView.paddingLeft,
                top + (8 * d).toInt(),
                chatRecyclerView.paddingRight,
                listChromePad + imePx
            )
            if (atBottom && grew > 0) chatRecyclerView.post { chatRecyclerView.scrollBy(0, grew) }
            placeBottomFloaters()
            (progressBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    progressBar.layoutParams = lp
                }
            }
            emptyStateContainer.setPadding(0, top, 0, bottom)
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
        if (!Motion.areAnimationsEnabled(requireContext())) {
            followPending = true
            chatRecyclerView.doOnPreDraw {
                followPending = false
                val overflow = streamOverflow()
                if (followStream && !listDragging && overflow > 0) chatRecyclerView.scrollBy(0, overflow)
            }
            return
        }
        if (followTicker == null) {
            followLastFrameNs = 0L
            followTicker = followFrame.also { android.view.Choreographer.getInstance().postFrameCallback(it) }
        }
    }

    /** How far the last row's bottom sits below the composer's top edge, in px. */
    private fun streamOverflow(): Int {
        val last = chatAdapter.itemCount - 1
        val child = layoutManager.findViewByPosition(last) ?: return 0
        return child.bottom - (chatRecyclerView.height - chatRecyclerView.paddingBottom)
    }

    private var followTicker: android.view.Choreographer.FrameCallback? = null
    private var followLastFrameNs = 0L
    private var followCarry = 0f

    /**
     * Glides toward the growing edge instead of jumping a whole line per wrap: each frame
     * covers a share of the remaining distance (time constant 50ms, ~90% in 110ms), so a new
     * line reads as the page easing up. Stops once caught up or when the reader takes over.
     */
    private val followFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (view == null || !followStream || listDragging) {
                followTicker = null
                return
            }
            val overflow = streamOverflow()
            if (overflow <= 0) {
                followTicker = null
                followCarry = 0f
                return
            }
            val dtMs = if (followLastFrameNs == 0L) 16f
                else ((frameTimeNanos - followLastFrameNs) / 1_000_000f).coerceIn(4f, 50f)
            followLastFrameNs = frameTimeNanos
            val share = 1f - kotlin.math.exp(-dtMs / 50f)
            followCarry += overflow * share
            val step = followCarry.toInt().coerceAtLeast(1).coerceAtMost(overflow)
            followCarry = (followCarry - step).coerceAtLeast(0f)
            chatRecyclerView.scrollBy(0, step)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private var jumpShown = false
    private var jumpButton: View? = null

    /** The jump button rises in once the latest message is more than a short way below. */
    private fun updateJumpToBottom() {
        val b = jumpButton ?: view?.findViewById<View>(R.id.jumpToBottomButton)?.also { jumpButton = it } ?: return
        val show = chatAdapter.itemCount > 0 && remainingBelow() > 160 * resources.displayMetrics.density
        if (!show) {
            // Hide immediately. A page reset cancels the fade and would leave the button up
            // on a thread that has nothing below.
            jumpShown = false
            b.animate().cancel()
            b.alpha = 0f
            b.isVisible = false
            return
        }
        if (jumpShown && b.isVisible) return
        jumpShown = true
        b.animate().cancel()
        val lift = 8f * resources.displayMetrics.density
        if (!Motion.areAnimationsEnabled(requireContext())) {
            b.isVisible = true
            b.alpha = 1f
            return
        }
        b.isVisible = true
        b.translationY = lift
        b.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Motion.iosOut).start()
    }

    private fun remainingBelow(): Int = chatRecyclerView.run {
        computeVerticalScrollRange() - computeVerticalScrollOffset() - computeVerticalScrollExtent()
    }

    /**
     * The scroll-down button: an eased glide rather than a cut. From far up, the list dips,
     * cuts to a screen and a half short of the end, and glides the rest while it comes back.
     */
    private fun glideToBottom() {
        val last = chatAdapter.itemCount - 1
        if (last < 0) return
        val rv = chatRecyclerView
        if (!Motion.areAnimationsEnabled(requireContext()) || rv.height == 0) {
            layoutManager.scrollToPositionWithOffset(last, -1000000)
            return
        }
        followStream = true
        rv.stopScroll()
        val screen = rv.height
        // The scroll range is an estimate while rows of very different heights are unmeasured, so
        // one glide can stop half a screen short. Check where it landed and finish the job.
        fun glide(attempt: Int = 0) {
            val left = remainingBelow()
            if (left <= 0 && !rv.canScrollVertically(1)) return
            if (attempt >= 3) {
                layoutManager.scrollToPositionWithOffset(last, -1000000)
                return
            }
            val ms = (380 + 180 * left.coerceAtLeast(0) / screen.toFloat()).toInt().coerceIn(380, 680)
            rv.smoothScrollBy(0, left.coerceAtLeast(screen / 2), Motion.iosOut, ms)
            rv.postDelayed({
                if (isAdded && rv.canScrollVertically(1)) glide(attempt + 1)
            }, ms + 60L)
        }
        if (remainingBelow() > screen * 3) {
            rv.animate().cancel()
            rv.animate().alpha(0f).setDuration(110).setInterpolator(Motion.easeOut).withEndAction {
                rv.scrollBy(0, remainingBelow() - (screen * 1.5f).toInt())
                glide()
                rv.animate().alpha(1f).setDuration(260).setInterpolator(Motion.easeOut).start()
            }.start()
        } else {
            glide()
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
            onInstructMessage = { position ->
                // Rewrite: the title says it; the field only needs its placeholder.
                val wrapper = layoutInflater.inflate(R.layout.dialog_instruct, null)
                val input = wrapper.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.instructInput)
                val quote = wrapper.findViewById<android.widget.TextView>(R.id.instructQuote)
                val reply = viewModel.chatMessages.value?.getOrNull(position)?.let { viewModel.getMessageText(it.content) }.orEmpty()
                val snippet = RpRewrite.snippet(reply)
                if (snippet.isEmpty()) quote.visibility = View.GONE else quote.text = snippet
                fun fill(note: String) {
                    input.setText(note)
                    input.setSelection(note.length)
                }
                wrapper.findViewById<View>(R.id.rewriteShorter).setOnClickListener {
                    fill(getString(R.string.rp_rewrite_shorter_note))
                }
                wrapper.findViewById<View>(R.id.rewriteLonger).setOnClickListener {
                    fill(getString(R.string.rp_rewrite_longer_note))
                }
                wrapper.findViewById<View>(R.id.rewriteDialogue).setOnClickListener {
                    fill(getString(R.string.rp_rewrite_dialogue_note))
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
                        requireContext().hideKeyboard(input)
                        val text = input.text?.toString()?.trim().orEmpty()
                        if (text.isBlank()) {
                            dialog.dismiss()
                            return@setOnClickListener
                        }
                        // Keep dialog open on soft-fail so the typed note isn't lost.
                        if (!viewModel.rewriteRpReply(position, text)) return@setOnClickListener
                        dialog.dismiss()
                        // The last reply streams in at the end; an earlier one changes where it is.
                        if (position >= chatAdapter.itemCount - 1) scrollChatToLatestEnd()
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
            onCollapse = {
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

        chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = this@ChatFragment.layoutManager
            // The list is match_parent; its own size does not depend on message rows.
            // Skipping that check keeps a streaming rebind from requesting a full layout.
            setHasFixedSize(true)
            setItemViewCacheSize(8)
        }
        chatAdapter.onStreamVisualUpdate = { followStreamingEdge() }
        chatAdapter.showThinking = sharedPreferencesHelper.isShowThinkingBlocks()
        chatAdapter.onMessageMenu = { anchor, items -> showMessageMenu(anchor, items) }
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
                if (isScrollersEnabled) refreshScrollButtons()
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
        // Land any slide in flight while its views still exist; its end action must not fire into the next view.
        if (pager != null) dropPager()
        finishSettleNow()
        controlTileOrder = null
        rpHomeAnim?.cancel()
        rpHomeAnim = null
        rpUiWas = null
        ambientBackground = null
        topBarFade = null
        jumpButton = null
        scrollerCanUp = null
        scrollerCanDown = null
        pickerPopover?.dismiss(animated = false)
        // Its scrim and card live in the view being torn down; a menu left open would outlive it.
        messageMenu?.dismiss(animated = false)
        parentFragmentManager.removeOnBackStackChangedListener(rpPanelBackStackListener)
        rpPanel = null
        TtsHolder.removeListener(ttsProgress)
        // Leaving mid-reply cuts it off; the engine itself idles out once no screen holds it.
        if (isSpeaking) TtsHolder.ready()?.stop()
        isSpeaking = false
        currentSpeakingPosition = -1
        TtsHolder.release(this)
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
        clearPreview()
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
            clearPreview()
            currentTempImageFile?.delete()
            currentTempImageFile = null
            updateSendButtonChrome()
            if (viewModel.isRpMode()) applyRpComposerHint()
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
            if (!sharedPreferencesHelper.hasWorkspaceGrant()) {
                WorkspacePaths.ensureWorkspaceExists()
                GlassNotice.show(requireContext(), getString(R.string.toast_gradation_folder))
                folderPickerLauncher.launch(null)
            } else {
                WorkspacePaths.ensureWorkspaceExists()
                // hideMenu()
                viewModel.toggleToolsEnabled()
            }
        }
        sendChatButton.setOnClickListener {
            // A recording still being transcribed would land in the field after the send went out.
            if (dictation?.finishNow() == false) return@setOnClickListener
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
                    selectedAudioBytes == null && !photoSendInFlight && viewModel.canContinueRpStory()
                ) {
                    viewModel.continueRpStory()
                    return@setOnClickListener
                }
                // RP takes photos only; reject files and audio before file prepend so drafts stay clean.
                if (viewModel.isRpMode() && (selectedAudioBytes != null || pendingFiles.isNotEmpty())) {
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
                    if (selectedImageBytes != null && !viewModel.isVisionModel(viewModel.activeChatModel.value)) {
                        GlassNotice.show(requireContext(), getString(R.string.toast_image_need_vision))
                        return@setOnClickListener
                    }
                    // RP: validate before clearing the composer so character-gate failures keep the draft.
                    if (viewModel.isRpMode()) {
                        if (!viewModel.canSendRpMessage()) {
                            GlassNotice.show(requireContext(), getString(R.string.rp_select_character))
                            return@setOnClickListener
                        }
                        if (viewModel.isAwaitingResponse.value == true) {
                            GlassNotice.show(requireContext(), getString(R.string.rp_wait_for_reply))
                            return@setOnClickListener
                        }
                        val photo = selectedImageBytes
                        if (photo == null) {
                            if (!viewModel.sendRpUserMessage(substitutedPrompt)) return@setOnClickListener
                            chatEditText.setText("")
                            chatEditText.text.clear()
                            return@setOnClickListener
                        }
                        if (photoSendInFlight) return@setOnClickListener
                        photoSendInFlight = true
                        val photoMime = selectedImageMime
                        val draft = substitutedPrompt
                        // The photo stays staged until the send is accepted, so a second tap
                        // (or Continue) can't fire a second turn while the bytes are encoded.
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val base64 = withContext(Dispatchers.Default) { Base64.encodeToString(photo, Base64.NO_WRAP) }
                                if (!isAdded) return@launch
                                if (selectedImageBytes !== photo) return@launch
                                if (!viewModel.sendRpUserMessage(draft, imageUrl = "data:$photoMime;base64,$base64")) return@launch
                                chatEditText.setText("")
                                selectedImageBytes = null
                                selectedImageMime = null
                                attachmentPreviewContainer.visibility = View.GONE
                                updateSendButtonChrome()
                                applyRpComposerHint()
                            } finally {
                                photoSendInFlight = false
                            }
                        }
                        return@setOnClickListener
                    }

                    chatEditText.setText("")
                    chatEditText.text.clear()

                    val stagedImage = selectedImageBytes
                    val stagedImageMime = selectedImageMime
                    fun imageContent(base64: String) = run {
                        val imageUrl = "data:$stagedImageMime;base64,$base64"
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
                    }
                    if (stagedImage != null) {
                        // A 12 MB photo is a 16 MB string: encode it off the main thread.
                        lifecycleScope.launch {
                            val base64 = withContext(Dispatchers.Default) { Base64.encodeToString(stagedImage, Base64.NO_WRAP) }
                            viewModel.sendUserMessage(imageContent(base64), substitutedSystemPrompt)
                        }
                    } else {
                        viewModel.sendUserMessage(JsonPrimitive(substitutedPrompt), substitutedSystemPrompt) //#subpromptcode
                    }
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
                GlassNotice.show(requireContext(), getString(R.string.toast_image_params_google_only))
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
        chatAdapter.onSpeakerClick = {
            hideKeyboard()
            showRpCharacterPanel()
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
            if (rpHome?.isShown == true) return@setOnClickListener openRpCharacterLibrary()
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
            // Roleplay keeps no history of its own: inside a chat this is the way back to the characters.
            // On the list it goes straight to Settings, the one thing History offered Roleplay.
            if (viewModel.isRpMode()) {
                return@setOnClickListener if (rpHome?.isShown == true) openSettingsFromHistory() else openRpHome()
            }
            openHistoryPanel()
        }

        pdfChatButton.setOnClickListener {
            val messages = viewModel.chatMessages.value ?: emptyList()

            if (messages.isEmpty()) {
                GlassNotice.show(requireContext(), getString(R.string.toast_no_chat_export))
                return@setOnClickListener
            }

            pdfChatButton.setIconResource(R.drawable.ic_check)
            Handler(Looper.getMainLooper()).postDelayed({
                hideMenu()
                pdfChatButton.setIconResource(R.drawable.ic_pdfnew)
            }, 500)

            lifecycleScope.launch {
                val modelIdentifier = viewModel.activeChatModel.value ?: getString(R.string.unknown_model)
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
                        showFolderNotice(getString(R.string.toast_pdf_saved))
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
                flashCopied(copyChatButton)
                true  // Consume the long press
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_copy))
                true
            }
        }

        copyChatButton.setOnClickListener {
            val chatText = viewModel.getFormattedChatHistoryPlainText()  // Use the new plain-text function
            if (chatText.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Chat History", chatText)
                clipboard.setPrimaryClip(clip)
                flashCopied(copyChatButton)
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_copy))
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
            clearPreview()
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
            clearPreview()
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
                    GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_print))
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
                viewModel.saveMarkdownToDownloads(chatText)  // The ViewModel reports the save through toolUiEvent.
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_save))
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
                    GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_save))
                }
            }
        }
        saveMarkdownFileButton.setOnLongClickListener {
            hideMenu()
            val chatText = viewModel.getFormattedChatHistoryTxt()
            if (chatText.isNotBlank()) {
                viewModel.saveTxtToDownloads(chatText)
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_save))
            }
            true  // Required for onLongClickListener
        }
        saveHtmlButton.setOnClickListener {
            hideMenu()
            lifecycleScope.launch {
                val innerHtml = viewModel.getFormattedChatHistoryStyledHtml()
                if (innerHtml.isNotBlank()) {
                    viewModel.saveHtmlToDownloads(innerHtml)
                } else {
                    GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_save))
                }
            }
        }


        controlsButton.setOnClickListener {
            hideKeyboard()
            dismissAttachPopup()
            // In Roleplay this button is the character menu, like the speaker line; the
            // controls moved into the + menu.
            if (viewModel.isRpMode()) {
                if (headerContainer.isVisible) hideMenu()
                showRpCharacterPanel()
                return@setOnClickListener
            }
            if (headerContainer.isVisible) hideMenu() else showMenu()
        }
        menuButton.setOnClickListener {
            hideKeyboard()
            showAttachSheet()
        }
        menuButton.setOnLongClickListener {
            val inputText = chatEditText.text.toString().trim()
            if (inputText.isBlank()) {
                GlassNotice.show(requireContext(), getString(R.string.toast_no_text_to_correct))
            } else if (viewModel.activeChatApiKey.isBlank()) {
                GlassNotice.show(requireContext(), getString(R.string.toast_api_key_missing))
            } else {
                menuButton.isSelected = true
                menuButton.setIconResource(R.drawable.ic_magic)

                lifecycleScope.launch {
                    val corrected = viewModel.correctText(inputText)
                    if (!corrected.isNullOrBlank()) {
                        chatEditText.setText(corrected)
                        chatEditText.setSelection(corrected.length)
                    } else {
                        GlassNotice.show(requireContext(), getString(R.string.toast_correction_failed))
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
                GlassNotice.show(requireContext(), getString(R.string.toast_streaming_required_lyria))
            } else {
                // Normal toggle for other models or if turning it ON for Lyria
                viewModel.toggleStreaming()
            }
        }

        requireView().findViewById<MaterialButton>(R.id.thoughtsButton).let { thoughts ->
            thoughts.isSelected = sharedPreferencesHelper.isShowThinkingBlocks()
            thoughts.setOnClickListener {
                val show = !sharedPreferencesHelper.isShowThinkingBlocks()
                sharedPreferencesHelper.saveShowThinkingBlocks(show)
                thoughts.isSelected = show
                chatAdapter.showThinking = show
                chatAdapter.notifyItemRangeChanged(0, chatAdapter.itemCount)
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
                Pair(getString(R.string.font_plus_jakarta_sans), R.font.jakarta_regular as Int?),
                Pair(getString(R.string.font_inter), R.font.inter_regular),
                Pair(getString(R.string.font_system_default), null),
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

        requireView().findViewById<View>(R.id.jumpToBottomButton).setOnClickListener { glideToBottom() }

        scrollToBottomButton.setOnClickListener {
            chatRecyclerView.post {
                glideToBottom()
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
                    GlassNotice.show(requireContext(), getString(R.string.toast_clipboard_no_text))
                }
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_paste))
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
                    GlassNotice.show(requireContext(), getString(R.string.toast_clipboard_no_text))
                }
            } else {
                GlassNotice.show(requireContext(), getString(R.string.toast_nothing_to_paste))
            }
            true
        }
        dictation = VoiceDictation(
            fragment = this,
            input = chatEditText,
            micButton = speechButton,
            wave = requireView().findViewById(R.id.voiceWave),
            // In Roleplay the pill is gone for good (the character chip replaced it): leave it be.
            swapOut = { if (viewModel.isRpMode()) emptyList() else listOf(modelNameTextView) },
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
                previewImageView.dispose()
                previewImageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                previewImageView.setImageResource(android.R.drawable.ic_media_play) // or use a custom ic_audio
                attachmentPreviewContainer.visibility = View.VISIBLE
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
            GlassNotice.show(context, context.getString(R.string.toast_tts_text_truncated))
        }

        TtsHolder.whenReady(context) { tts ->
            if (!isAdded || view == null) return@whenReady
            if (tts == null) {
                GlassNotice.show(context, context.getString(R.string.toast_tts_failed))
                return@whenReady
            }
            try {
                // The voice page's previews leave their pitch and speed on the shared engine.
                applyReadAloudVoice(tts)
                val timestamp = System.currentTimeMillis()
                val utteranceId = "TTS_SAVE_${timestamp}_${position}"
                val tempFile = File(context.cacheDir, "temp_tts_${timestamp}.wav")
                // val fileName = "TTS_${timestamp}_msg${position}.wav"  // For Toast tracking

                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                }

                val result = tts.synthesizeToFile(
                    safeText,
                    params,
                    tempFile,
                    utteranceId
                )

                // Queued: the save notice follows when the file is written.
                if (result != TextToSpeech.SUCCESS) {
                    GlassNotice.show(context, context.getString(R.string.toast_tts_wav_failed, result))
                }

            } catch (e: Exception) {
                GlassNotice.show(context, context.getString(R.string.toast_tts_queue_error, e.message ?: ""))
            }
        }
    }

    private fun speakText(text: String, position: Int) {
        if (isSpeaking && position == currentSpeakingPosition) {
            TtsHolder.ready()?.stop()
            onSpeechFinished()
            return
        }
        val context = requireContext()
        // The first read-aloud starts the shared engine; the stop icon waits for it.
        TtsHolder.whenReady(context) { tts ->
            if (!isAdded || view == null) return@whenReady
            if (tts == null) {
                GlassNotice.show(context, context.getString(R.string.toast_tts_failed))
                return@whenReady
            }
            applyReadAloudVoice(tts)
            if (isSpeaking) {
                tts.stop()
                onSpeechFinished()
            }
            isSpeaking = true
            currentSpeakingPosition = position
            chatAdapter.updateTtsState(isSpeaking, currentSpeakingPosition)
            updateIconDirectlyOrNotify(position, R.drawable.ic_msg_stop)
            val safeText = text.take(3900)
            if (safeText.length < text.length) {
                GlassNotice.show(context, context.getString(R.string.toast_tts_text_truncated))
            }
            tts.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, TTS_SPEAK_ID)
        }
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
            updateIconDirectlyOrNotify(pos, R.drawable.ic_msg_speak)
        }
    }

    /** TextToSpeech calls back on a binder thread: hop to main, and do nothing if the screen is gone. */
    private fun noticeFromAnyThread(@androidx.annotation.StringRes res: Int, vararg args: Any) {
        val host = activity ?: return
        host.runOnUiThread { if (isAdded) GlassNotice.show(host, getString(res, *args)) }
    }

    /** Says what was saved and offers the workspace folder, for the tools and the chat exports. */
    private fun showFolderNotice(message: CharSequence) {
        GlassNotice.show(requireContext(), message, getString(R.string.action_open_folder)) { openWorkspaceFolder() }
    }

    private fun openWorkspaceFolder() {
        WorkspacePaths.ensureWorkspaceExists()
        val path = WorkspacePaths.workspaceDirForRead()
        // The file manager gets a file:// URI, which StrictMode would otherwise refuse.
        try {
            StrictMode::class.java.getMethod("disableDeathOnFileUriExposure").invoke(null)
        } catch (e: Exception) {
            // Hidden API gone on this build: the chooser still opens.
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType("file://${path.absolutePath}".toUri(), "resource/folder")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.chooser_open_file_manager)))
    }

    /** Copy confirmation for a menu icon button: the glyph turns into a check for a moment, with a tick. */
    private fun flashCopied(button: MaterialButton) {
        Haptics.tap(button)
        button.setIconResource(R.drawable.ic_check)
        button.postDelayed({ if (button.isAttachedToWindow) button.setIconResource(R.drawable.ic_copi) }, 950L)
    }

    /** Empties the attachment thumbnail, and cancels a Coil load still on its way to it. */
    private fun clearPreview() {
        previewImageView.dispose()
        previewImageView.setImageDrawable(null)
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
            Haptics.tap(group)
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
            Haptics.tap(more)
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
            placeControlsCard()
        }
    }

    /** Mirror model + reasoning state into the sheet's quick controls. */
    private fun updateQuickControls() {
        val group = effortGroup ?: return
        val root = view ?: return
        val model = viewModel.activeChatModel.value
        root.findViewById<TextView>(R.id.controlsModelName).text =
            model?.let { viewModel.getModelDisplayName(it) } ?: ""
        root.findViewById<ImageView>(R.id.controlsModelIcon).setImageResource(
            model?.let { ModelBrands.of(it)?.icon }
                ?: if (viewModel.activeModelIsLan()) R.drawable.ic_local_network else R.drawable.ic_cloudnew
        )
        val supported = viewModel.canRequestReasoning(model)
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
        Haptics.tap(controlsButton)
        overlayView?.visibility = View.VISIBLE
        headerContainer.animate().cancel()
        placeControlsCard()
        headerContainer.apply {
            visibility = View.VISIBLE
            if (anim) {
                // Grows up out of the composer like the other popovers; rows settle in behind it.
                // Measured by placeControlsCard, so this works on the first open too.
                pivotX = measuredWidth / 2f
                pivotY = measuredHeight.toFloat()
                alpha = 0f; scaleX = 0.86f; scaleY = 0.86f; translationY = 14f * d
                animate().alpha(1f).setDuration(160).setInterpolator(Motion.easeOut).start()
                controlsCardAnim = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    this,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
                    android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f)
                ).setDuration(460).apply { interpolator = Motion.spring; start() }
                staggerPanelRows(d)
            } else {
                alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f
            }
        }
        // The dim now sits over the whole screen, top bar included.
        dimOverlay?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(0.6f).setDuration(260).setInterpolator(Motion.iosOut).start()
        }
        // Fade empty-state (mark + prompt) while menu is open
        if (emptyStateContainer.isVisible) {
            emptyStateContainer.visibility = View.GONE
        }
    }

    private var controlsCardAnim: android.animation.Animator? = null

    /**
     * Pin the Controls card above the composer, spanning it edge to edge (the same geometry
     * [PickerPopover] uses), and cap the "More controls" grid to the room left under the top bar.
     */
    private fun placeControlsCard() {
        val parent = headerContainer.parent as? View ?: return
        val composer = chatInputContainer
        if (parent.height == 0 || composer.width == 0) return
        val d = resources.displayMetrics.density
        val parentLoc = IntArray(2).also { parent.getLocationInWindow(it) }
        val composerLoc = IntArray(2).also { composer.getLocationInWindow(it) }
        val composerTop = composerLoc[1] - parentLoc[1]
        val gap = (8 * d).toInt()
        val lp = headerContainer.layoutParams as FrameLayout.LayoutParams
        val width = composer.width
        val left = composerLoc[0] - parentLoc[0]
        val bottom = parent.height - composerTop + gap
        // Room: from the top bar's bottom edge down to the card's bottom.
        val bar = view?.findViewById<View>(R.id.topBarGlass)
        val barBottom = bar?.let { b -> IntArray(2).also { b.getLocationInWindow(it) }[1] - parentLoc[1] + b.height } ?: 0
        val room = (composerTop - gap - barBottom - gap).coerceAtLeast((200 * d).toInt())
        val scroll = headerContainer.findViewById<View>(R.id.controlsMoreScroll)
        val oldScrollHeight = scroll.layoutParams.height
        // Measure at natural height, then cap.
        scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        headerContainer.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val over = headerContainer.measuredHeight - room
        val scrollHeight = if (over > 0) (scroll.measuredHeight - over).coerceAtLeast(0)
        else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.width != width || lp.leftMargin != left || lp.bottomMargin != bottom ||
            lp.gravity != (Gravity.BOTTOM or Gravity.START) || oldScrollHeight != scrollHeight
        ) {
            lp.width = width
            lp.leftMargin = left
            lp.rightMargin = 0
            lp.bottomMargin = bottom
            lp.gravity = Gravity.BOTTOM or Gravity.START
            scroll.layoutParams = scroll.layoutParams.apply { height = scrollHeight }
            headerContainer.layoutParams = lp
        } else {
            scroll.layoutParams.height = oldScrollHeight
        }
        if (over > 0) headerContainer.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
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

    /**
     * Roleplay "+": a photo for the scene (from the library or the camera) and a scene reminder;
     * the model's controls and the Roleplay hub sit under the line. Files and audio stay in Chat.
     */
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
        rows += PickerPopover.Row(getString(R.string.grok_attach_gallery), getString(R.string.rp_plus_photo_sub), R.drawable.ic_gallery) {
            menuButton.post { launchGalleryPicker() }
        }
        rows += PickerPopover.Row(getString(R.string.grok_attach_camera), getString(R.string.attach_camera_sub), R.drawable.ic_camera) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchCamera()
            }
        }
        rows += PickerPopover.Row(getString(R.string.rp_plus_reminder), getString(R.string.rp_plus_reminder_sub), R.drawable.ic_nav_prompts) {
            insertRpReminderTemplate()
        }
        // The composer's old settings button is the character menu here, so the model's controls
        // (streaming among them) live under +.
        val footer = listOf(
            PickerPopover.Row(getString(R.string.rp_plus_controls), getString(R.string.rp_plus_controls_sub), R.drawable.ic_sliders) {
                menuButton.post { showMenu() }
            },
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
        val resolver = requireContext().applicationContext.contentResolver
        val mime = resolver.getType(uri)
        // HEIC and anything else BitmapFactory can read is turned into a JPEG below.
        if (mime != null && !mime.startsWith("image/")) {
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
            stagePickedPhoto(bytes)
        }
    }

    /**
     * Turn a picked or captured photo into the JPEG we actually send, and show it in the composer.
     * The bubble keeps a copy we own: the picker's link does not survive leaving the screen.
     */
    private fun stagePickedPhoto(raw: ByteArray) {
        viewLifecycleOwner.lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.Default) { ScenePhoto.encode(raw) }
            if (!isAdded) return@launch
            if (jpeg == null) {
                GlassNotice.show(requireContext(), getString(R.string.toast_unsupported_image_format))
                return@launch
            }
            val model = viewModel.activeChatModel.value
            if (model != null && !viewModel.isVisionModel(model)) {
                GlassNotice.show(requireContext(), getString(R.string.toast_image_need_vision))
            }
            val stored = withContext(Dispatchers.IO) { ScenePhoto.store(requireContext(), jpeg) }
            selectedImageBytes = jpeg
            selectedImageMime = ScenePhoto.MIME
            previewImageView.scaleType = ImageView.ScaleType.CENTER_CROP
            previewImageView.load(jpeg)
            attachmentPreviewContainer.visibility = View.VISIBLE
            viewModel.setPendingUserImageUri(stored?.toString())
            updateSendButtonChrome()
            if (viewModel.isRpMode()) applyRpComposerHint()
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
        controlsCardAnim?.cancel()
        controlsCardAnim = null
        dimOverlay?.animate()?.alpha(0f)?.setDuration(menuMs)?.setInterpolator(Motion.iosIn)?.withEndAction {
            dimOverlay?.visibility = View.GONE
        }?.start()
        if (headerContainer.visibility == View.VISIBLE) animateTopBarDim(0f, menuMs)
        // Shrinks back toward the composer, the way it came (from wherever a drag left it).
        headerContainer.animate()
            .alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .translationY(headerContainer.translationY + 10f * d)
            .setDuration(menuMs).setInterpolator(Motion.iosIn).withEndAction {
                headerContainer.visibility = View.GONE
                headerContainer.alpha = 1f
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

    private fun scrollToPreviousScreen() = scrollByScreen(-1)

    private fun scrollToNextScreen() = scrollByScreen(1)

    private fun scrollByScreen(direction: Int) {
        chatRecyclerView.post {
            chatRecyclerView.smoothScrollBy(0, direction * chatRecyclerView.height)
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
    // Held here: SharedPreferences keeps its change listeners only weakly.
    private var roleplaySwitchWatcher: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var codeSwitchWatcher: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Settings can sit on top of the chat without hiding it (opened from the history panel), so
     * neither onResume nor onHiddenChanged runs when a mode switch flips. Follow the prefs.
     */
    private fun watchModeSwitches() {
        val prefs = sharedPreferencesHelper.mainPrefs
        val rp = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SharedPreferencesHelper.KEY_ROLEPLAY_ENABLED -> onModeSwitchChanged()
                SharedPreferencesHelper.KEY_CHAT_MARK -> applyChatMark()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(rp)
        roleplaySwitchWatcher = rp
        // Prefs only: building the hub here would open the Code database on every chat open.
        val store = io.github.stardomains3.oxproxion.code.store.CodeStore(requireContext())
        val code = store.addEnabledListener { onModeSwitchChanged() }
        codeSwitchWatcher = code
        viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                prefs.unregisterOnSharedPreferenceChangeListener(rp)
                store.removeEnabledListener(code)
                if (roleplaySwitchWatcher === rp) roleplaySwitchWatcher = null
                if (codeSwitchWatcher === code) codeSwitchWatcher = null
            }
        })
    }

    private fun onModeSwitchChanged() {
        if (view == null) return
        refreshModeTabs()
        (childFragmentManager.findFragmentById(R.id.historyDrawerContainer) as? SavedChatsFragment)?.refreshModeRows()
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
        if (!rpOn) closeRpPanel(animated = false)
        if (::codeMode.isInitialized) codeMode.refresh()
        modeTabIndicator.post { placeModeTabIndicator(animate = false) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            pickerPopover?.dismiss(animated = false)
            messageMenu?.dismiss(animated = false)
        }
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
        view?.findViewById<View>(R.id.rootLayout)?.animate()?.cancel()
    }

    /**
     * Where the chat sits while the history panel's left edge is at [panelX] (-w closed, 0 open):
     * pinned to the panel's right edge, like the next page of a pager. A slower parallax slide
     * left both screens' look-alike chrome (round top buttons, bottom capsules) visible at once,
     * reading as a doubled UI.
     */
    private fun historyChatOffset(panelX: Float, w: Float): Float = w + panelX

    /**
     * Wide, deliberate swipes across the whole chat, read as pages
     * History | Chat | Roleplay | Models: finger right goes one page left, finger left one page
     * right. Inside the open history, a leftward swipe drags it closed.
     */
    // ── Mode pager ────────────────────────────────────────────────────────────────────────
    // Chat, Roleplay and Code share one set of views, so the neighbour page can't be laid out
    // beside the current one. Instead: snapshot the current page, switch to the next mode
    // behind the snapshot, and slide the two side by side. Taps and swipes both use it.

    private class Pager(
        val target: TextView, val origin: TextView, val direction: Int, val shot: ImageView, val w: Float,
        /** Scroll-edge fade of the page being left, so the dim under the tabs blends across. */
        val edgeFrom: Int,
        val tabColors: android.content.res.ColorStateList,
    )

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
    private fun modePages(): List<View> = allModePages().filter { it.isVisible }

    private fun allModePages(): List<View> {
        val root = view ?: return emptyList()
        return listOfNotNull(
            root.findViewById(R.id.chatFrameView),
            root.findViewById(R.id.composerDock),
            root.findViewById(R.id.composerFade),
            root.findViewById(R.id.jumpToBottomButton),
            root.findViewById(R.id.codeModeContainer),
            // The characters list is a page too: left out, it sat still while the chat slid past it.
            root.findViewById(R.id.rpHome)
        )
    }

    /**
     * Park every page layer back at rest. Cancels their animators first: a cancelled slide's
     * page animators end on the same frame as the snapshot's, and one landing after this reset
     * would leave the chat (composer included) parked a page off screen.
     */
    private fun restModePages() {
        allModePages().forEach { page ->
            page.animate().cancel()
            page.translationX = 0f
            // The jump button's alpha is its shown state. Forcing 1 brings it back on an empty page.
            if (page.id != R.id.jumpToBottomButton) page.alpha = 1f
        }
        updateJumpToBottom()
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
            modeSwitchedAt = android.os.SystemClock.uptimeMillis()
            captureListSpot()?.let { modeSpots[current] = it }
            modeThreads[current] = chatAdapter.currentMessages()
            pendingSpot = modeSpots[target]
            viewModel.toggleChatMode()
            showCachedThread(target)
        }
        return true
    }

    /** Each mode's thread as last shown, so swiping back paints it in the same frame. */
    private val modeThreads = HashMap<ChatMode, List<FlexibleMessage>>()

    /**
     * Put the mode's last-seen thread and scroll spot on screen now, before its reload lands:
     * the slide shows the right page, and the reload finds nothing to change.
     */
    private fun showCachedThread(mode: ChatMode) {
        val cached = modeThreads[mode] ?: return
        chatAdapter.setMessages(cached)
        emptyStateContainer.animate().cancel()
        emptyStateContainer.scaleX = 1f
        emptyStateContainer.scaleY = 1f
        emptyStateContainer.alpha = 1f
        emptyStateContainer.isVisible = cached.isEmpty()
        if (cached.isEmpty()) bindEmptyState(mode == ChatMode.RP)
        val last = cached.lastIndex
        if (last >= 0) {
            val spot = modeSpots[mode]
            if (spot == null || spot.atBottom || spot.position > last) {
                layoutManager.scrollToPositionWithOffset(last, -1000000)
            } else {
                layoutManager.scrollToPositionWithOffset(spot.position, spot.offset)
            }
        }
        pendingSpot = null
        updateJumpToBottom()
        chatRecyclerView.post {
            updateJumpToBottom()
            if (isScrollersEnabled) {
                scrollToTopButton.setShownAnimated(chatRecyclerView.canScrollVertically(-1))
                scrollToBottomButton.setShownAnimated(chatRecyclerView.canScrollVertically(1))
            }
        }
    }

    /** Where the reader was in a mode's thread, so swiping back lands on the same lines. */
    private data class ListSpot(val sessionId: Long?, val position: Int, val offset: Int, val atBottom: Boolean)

    private val modeSpots = HashMap<ChatMode, ListSpot>()
    private var pendingSpot: ListSpot? = null

    private fun captureListSpot(): ListSpot? {
        if (chatAdapter.itemCount == 0) return null
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        val top = layoutManager.findViewByPosition(first)?.top ?: 0
        return ListSpot(
            viewModel.getCurrentSessionId(), first, top - chatRecyclerView.paddingTop,
            atBottom = !chatRecyclerView.canScrollVertically(1)
        )
    }

    /** The thread that just landed is the one we left: put the reader back where they were. */
    private fun restoreListSpot() {
        val spot = pendingSpot ?: return
        if (android.os.SystemClock.uptimeMillis() - modeSwitchedAt > MODE_LOAD_WINDOW_MS) { pendingSpot = null; return }
        if (spot.sessionId == null || spot.sessionId != viewModel.getCurrentSessionId()) return
        pendingSpot = null
        val last = chatAdapter.itemCount - 1
        if (last < 0) return
        // Straight away, not posted: the next layout lands on the spot, with no frame elsewhere.
        if (spot.atBottom || spot.position > last) {
            layoutManager.scrollToPositionWithOffset(last, -1000000)
        } else {
            layoutManager.scrollToPositionWithOffset(spot.position, spot.offset)
        }
    }

    /** When Ask/RP last flipped; the next thread to load belongs to the switch, not to a new chat. */
    private var modeSwitchedAt = 0L

    private fun openPager(target: TextView, direction: Int): Boolean {
        val root = view ?: return false
        val content = root.findViewById<FrameLayout>(R.id.rootLayout)
        val topBar = root.findViewById<View>(R.id.topBarGlass)
        if (content.width == 0) return false
        val origin = currentTab()
        val edgeFrom = topBar.background?.alpha ?: 0
        pagerBusy = true
        modePages().forEach { it.animate().cancel(); it.translationX = 0f; it.alpha = 1f }
        // The top bar stays on screen above both pages, so leave it out of the picture.
        val bar = topBar.visibility
        topBar.visibility = View.INVISIBLE
        val bmp = snapshotPage(content)
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
        Haptics.tap(target)
        edgeAnimator?.cancel()
        pager = Pager(target, origin, direction, shot, content.width.toFloat(), edgeFrom, origin.textColors)
        movePager(0f)
        return true
    }

    /**
     * How far the slide has come, 0 (old page) to 1 (new page): inactive tabs sit on tertiary
     * and lighten toward ink with the finger, so the page you're leaving fades and the one
     * you're entering brightens in lockstep with the underline.
     */
    private fun applyPagerProgress(p: Pager, t: Float) {
        val k = t.coerceIn(0f, 1f)
        val ink = ContextCompat.getColor(requireContext(), R.color.xai_ink)
        val dim = ContextCompat.getColor(requireContext(), R.color.xai_tertiary)
        val eval = android.animation.ArgbEvaluator()
        p.origin.setTextColor(eval.evaluate(k, ink, dim) as Int)
        p.target.setTextColor(eval.evaluate(k, dim, ink) as Int)
        view?.findViewById<View>(R.id.topBarGlass)?.background?.alpha =
            (p.edgeFrom + (edgeAlphaForList() - p.edgeFrom) * k).toInt()
    }

    /** Hand the tabs back to their selected-state colors and the fade back to the list. */
    private fun releasePagerChrome(p: Pager) {
        p.origin.setTextColor(p.tabColors)
        p.target.setTextColor(p.tabColors)
        updateTopBarEdge()
    }

    /**
     * The page as it looks on screen. Drawn through the GPU so the composer's glass edge, the
     * liquid mark and the ambient field come out as they are; a software canvas drops them
     * (the glow went missing mid-swipe) and is only the fallback.
     */
    private fun snapshotPage(content: View): Bitmap? {
        if (content.isHardwareAccelerated) {
            runCatching {
                HardwareRaster.render(content.width, content.height, software = false) { content.draw(it) }
            }.getOrNull()?.let { return it }
        }
        // Software fallback: the liquid mark only has a frame to draw if it read one back.
        if (::centerWatermarkIcon.isInitialized) centerWatermarkIcon.prepareSnapshot()
        return runCatching { content.drawToBitmap(Bitmap.Config.ARGB_8888) }.getOrNull()
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
        applyPagerProgress(p, abs(dx) / w)
    }

    /**
     * Finish the slide either way; on cancel the origin mode comes back behind the snapshot.
     * [velocity] is the finger's release speed (px/s); a tab tap has none.
     */
    private fun settlePager(commit: Boolean, velocity: Float? = null) {
        val p = pager ?: return
        pager = null
        val w = p.w
        val anim = Motion.areAnimationsEnabled(requireContext())
        val shotTo = if (commit) p.direction * w else 0f
        val pageTo = if (commit) 0f else -p.direction * w
        val lineTo = indicatorXFor(if (commit) p.target else p.origin)
        var finished = false
        // Runs once, whichever comes first: the animation ending, or a new gesture landing it
        // early ([finishSettleNow], which also wants the snapshot gone before it takes another).
        val done = { immediate: Boolean ->
            if (!finished) {
                finished = true
                settleDone = null
                p.shot.animate().cancel()
                if (!commit) switchToTab(p.origin)
                restModePages()
                if (immediate) {
                    removeShot(p.shot)
                } else {
                    // Give an async mode reload a beat to draw before the snapshot goes.
                    lingeringShot = p.shot
                    p.shot.postDelayed({ removeShot(p.shot) }, if (commit) 0L else 120L)
                }
                pagerBusy = false
                pagerOrigin = null
                placeModeTabIndicator(animate = false)
                releasePagerChrome(p)
            }
        }
        if (!anim) { done(false); return }
        settleDone = done
        // A released swipe carries its speed on in one spring shared by every layer, so the
        // pages stay edge to edge. A tap has no speed to carry and uses the push curve.
        val fling = velocity?.let { Motion.flingX(p.shot, shotTo, it, response = 0.38f) }
        val curve: android.animation.TimeInterpolator = fling ?: Motion.iosPush
        val duration = fling?.duration ?: 340L
        p.shot.animate().translationX(shotTo).setDuration(duration).setInterpolator(curve)
            .setUpdateListener { applyPagerProgress(p, abs(p.shot.translationX) / w) }
            .withEndAction { p.shot.animate().setUpdateListener(null); done(false) }.start()
        modePages().forEach { it.animate().translationX(pageTo).setDuration(duration).setInterpolator(curve).start() }
        if (lineTo != null) {
            modeTabIndicator.animate().translationX(lineTo).setDuration(duration).setInterpolator(curve).start()
        }
    }

    /** Drop an open pager without animating (direction flipped, or the drag went elsewhere). */
    private fun dropPager() {
        val p = pager ?: return
        pager = null
        switchToTab(p.origin)
        restModePages()
        releasePagerChrome(p)
        lingeringShot = p.shot
        p.shot.postDelayed({ removeShot(p.shot) }, 120L)
        pagerBusy = false
    }

    /** The settle in flight, if any: finishes it on the spot (see [finishSettleNow]). */
    private var settleDone: ((Boolean) -> Unit)? = null
    /** A snapshot waiting out its last frames on screen. */
    private var lingeringShot: ImageView? = null

    private fun removeShot(shot: ImageView) {
        (shot.parent as? ViewGroup)?.removeView(shot)
        (shot.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
        shot.setImageDrawable(null)
        if (lingeringShot === shot) lingeringShot = null
    }

    /**
     * A new touch that turns into a drag while the last slide is still settling: land that slide
     * now. Left running, its end action would fire mid-drag and reset the pages, the underline
     * and the mode under the finger, and the new snapshot would include the old one.
     */
    private fun finishSettleNow() {
        settleDone?.invoke(true)
        lingeringShot?.let { removeShot(it) }
        rpHomeAnim?.end()
    }

    /** Tab tap: the same side-by-side slide as a swipe, in the tabs' order. */
    private fun pageToTab(tab: TextView) {
        if (pager != null || pagerBusy) return
        val from = currentTab()
        if (tab == from) return
        val order = modeTabsInOrder()
        val direction = if (order.indexOf(tab) < order.indexOf(from)) 1 else -1
        if (!Motion.areAnimationsEnabled(requireContext())) {
            if (switchToTab(tab)) Haptics.tap(tab)
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
        /**
         * Top of the bottom bar band (Chat/RP composer and its extras, or Code's composer), in
         * root coordinates. Everything from there down belongs to the bar: its pills and chips
         * scroll sideways, so a page swipe must never start on it.
         */
        fun bottomBarTop(): Float? {
            val bars = listOfNotNull(
                dock, rpComposerExtras, attachmentPreviewContainer,
                root.findViewById<View>(R.id.codeHomeComposer)
            ).filter { it.isShown && it.height > 0 }
            if (bars.isEmpty()) return null
            root.getLocationInWindow(rootLoc)
            return bars.minOf { v -> v.getLocationInWindow(vLoc); (vLoc[1] - rootLoc[1]).toFloat() } - 6f * d
        }
        (root as? SwipeNavLayout)?.listener = object : SwipeNavLayout.Listener {
            override fun canStart(x: Float, y: Float): Boolean =
                historyDrawerContainer?.visibility != View.VISIBLE &&
                    pickerPopover?.isShowing != true &&
                    !headerContainer.isVisible &&
                    !inside(topBar, x, y) && y < (bottomBarTop() ?: Float.MAX_VALUE) &&
                    parentFragmentManager.backStackEntryCount == 0

            var historyDrag = false

            override fun onDrag(dx: Float) {
                val direction = if (dx > 0) 1 else -1
                if (pager == null && (settleDone != null || lingeringShot != null || rpHomeAnim != null)) finishSettleNow()
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

            private fun releaseVelocity() = (root as? SwipeNavLayout)?.releaseVelocity ?: 0f

            override fun onCommit(direction: Int) {
                Haptics.tap(content, android.view.HapticFeedbackConstants.GESTURE_END)
                if (historyDrag) {
                    historyDrag = false
                    pagerOrigin = null
                    settleHistoryDrag(releaseVelocity())
                    return
                }
                if (pager == null) { onCancel(); return }
                settlePager(commit = true, velocity = releaseVelocity())
            }

            override fun onCancel() {
                val v = releaseVelocity()
                if (historyDrag) {
                    historyDrag = false
                    pagerOrigin = null
                    cancelHistoryDrag(animate = true, velocity = v)
                    return
                }
                if (pager != null) { settlePager(commit = false, velocity = v); return }
                pagerOrigin = null
                modePages().firstOrNull()?.let { first ->
                    // Springs back from a lean; a little give suits a rubber band.
                    val fling = Motion.Fling(-first.translationX, v * -kotlin.math.sign(first.translationX), response = 0.36f, damping = 0.8f)
                    modePages().forEach { p ->
                        p.animate().translationX(0f).alpha(1f).setDuration(fling.duration).setInterpolator(fling).start()
                    }
                }
                placeModeTabIndicator(animate = true)
            }
        }
        (root as? SwipeNavLayout)?.apply {
            // Slightly lighter pull than before: about a sixth of the width commits.
            commitFraction = 0.16f
        }

        val drawer = historyDrawerContainer as? SwipeNavLayout ?: return
        drawer.commitFraction = 0.22f
        drawer.listener = object : SwipeNavLayout.Listener {
            override fun onDrag(dx: Float) {
                cancelDrawerAnimation()
                val w = drawer.width.coerceAtLeast(1).toFloat()
                val t = dx.coerceAtMost(0f)
                drawer.translationX = t
                content.translationX = historyChatOffset(t, w)
                historyDrawerScrim?.alpha = 0.35f * (1f + t / w)
            }

            override fun onCommit(direction: Int) {
                if (direction >= 0) { onCancel(); return }
                if (!Motion.areAnimationsEnabled(requireContext())) { closeHistoryPanel(animated = false); return }
                flingHistory(open = false, velocity = drawer.releaseVelocity) { closeHistoryPanel(animated = false) }
            }

            override fun onCancel() {
                flingHistory(open = true, velocity = drawer.releaseVelocity) {}
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
                // In Roleplay a rightward swipe pages back to Chat; the drawer is not on that path.
                if (viewModel.isRpMode() && !codeMode.isActive) return false
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
        view?.findViewById<View>(R.id.rootLayout)?.translationX = historyChatOffset(x - w, w)
    }

    /**
     * The panel, the chat pinned to its edge and the scrim, all on one spring from the release
     * [velocity] (px/s, + = rightward), so the chat never drifts off the panel's edge.
     */
    private fun flingHistory(open: Boolean, velocity: Float, end: () -> Unit) {
        val panel = historyDrawerContainer ?: return
        val w = (view?.width ?: panel.width).coerceAtLeast(1).toFloat()
        val fling = Motion.flingX(panel, if (open) 0f else -w, velocity, response = 0.38f)
        historyDrawerScrim?.animate()?.alpha(if (open) 0.35f else 0f)?.setDuration(fling.duration)?.setInterpolator(fling)?.start()
        view?.findViewById<View>(R.id.rootLayout)?.animate()
            ?.translationX(historyChatOffset(if (open) 0f else -w, w))?.setDuration(fling.duration)?.setInterpolator(fling)?.start()
        panel.animate().translationX(if (open) 0f else -w).setDuration(fling.duration).setInterpolator(fling)
            .withEndAction(end).start()
    }

    private fun settleHistoryDrag(velocity: Float = 0f) {
        val panel = historyDrawerContainer ?: return
        flingHistory(open = true, velocity = velocity) { panel.setLayerType(View.LAYER_TYPE_NONE, null) }
    }

    private fun cancelHistoryDrag(animate: Boolean, velocity: Float = 0f) {
        val panel = historyDrawerContainer ?: return
        val scrim = historyDrawerScrim
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
        flingHistory(open = false, velocity = velocity) {
            root?.translationX = 0f
            done()
        }
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
            val chat = view?.findViewById<View>(R.id.rootLayout)
            if (!anim) {
                panel.translationX = 0f
                chat?.translationX = historyChatOffset(0f, w.toFloat())
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
            // The chat is the next page: it leaves pinned to the panel's edge.
            chat?.animate()
                ?.translationX(historyChatOffset(0f, w.toFloat()))?.setDuration(drawerMs)?.setInterpolator(Motion.iosPush)?.start()
        }
    }

    fun onBackPressed(): Boolean {
        if (::codeMode.isInitialized && codeMode.onBackPressed()) return true
        if (rpPanel?.isShowing == true) {
            closeRpPanel()
            return true
        }
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
                GlassNotice.show(requireContext(), getString(R.string.toast_no_camera_app))
                requireContext().contentResolver.delete(imageUri, null, null)
                currentCameraUri = null  // NEW: Clean up
            }
        } ?: run {
            GlassNotice.show(requireContext(), getString(R.string.toast_could_not_create_image))
        }
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
                            GlassNotice.show(requireContext(), getString(R.string.toast_pdf_no_read_access))
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
                        GlassNotice.show(requireContext(), getString(R.string.toast_pdf_no_pages))
                        pdfRenderer.close()
                    }
                    1 -> {
                        val bitmap = renderPdfPageToBitmap(pdfRenderer, 0)
                        processPdfBitmap(bitmap)
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


    private suspend fun processPdfBitmap(bitmap: Bitmap) {
        if (discardAttachmentIfRp()) {
            bitmap.recycle()
            return
        }
        // A page rendered at 2x is several megapixels: PNG-encoding it and writing the preview
        // file would stall the main thread, so both happen on IO.
        val tempPngFile = File(requireContext().cacheDir, "pdf_page_${System.currentTimeMillis()}.png")
        val bytes = withContext(Dispatchers.IO) {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
            bitmap.recycle()
            byteArrayOutputStream.toByteArray().also { if (it.size <= 12_000_000) tempPngFile.writeBytes(it) }
        }

        if (bytes.size > 12_000_000) {
            GlassNotice.show(requireContext(), getString(R.string.toast_pdf_page_too_large))
            return
        }

        selectedImageBytes = bytes
        selectedImageMime = "image/png"
        currentTempImageFile = tempPngFile  // Track for cleanup

        val pngUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            tempPngFile
        )

        // Set for ViewModel (enables bubble preview)
        viewModel.setPendingUserImageUri(pngUri.toString())

        previewImageView.scaleType = ImageView.ScaleType.CENTER_CROP
        previewImageView.load(tempPngFile)
        attachmentPreviewContainer.visibility = View.VISIBLE
    }




    private fun showPageSelectionDialog(
        pdfRenderer: PdfRenderer,
        parcelFd: ParcelFileDescriptor,
        pageCount: Int,
        tempPdfFile: File?
    ) {
        val pageTitles = (1..pageCount).map { getString(R.string.pdf_page_n, it) }.toTypedArray()
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
            .setPositiveButton(R.string.action_convert) { _, _ ->
                converting = true
                lifecycleScope.launch {
                    try {
                        val bitmap = renderPdfPageToBitmap(pdfRenderer, selectedPage)
                        processPdfBitmap(bitmap)
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
            selectedImageBytes != null ||
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
            .setPositiveButton(R.string.action_remove_all) { _, _ ->
                pendingFiles.clear()
                updateAttachmentButton()
            }
            .setNegativeButton(R.string.action_close, null)

        val dialog = builder.show()

        // Apply dim amount of 0.8f
        dialog.window?.let { GlassDialogs.frost(it) }
    }
    private fun showWebSearchEngineDialog() {
        pickOne(
            title = R.string.dialog_web_search_engine,
            items = listOf("default", "native", "exa", "firecrawl", "parallel"),
            labels = intArrayOf(
                R.string.web_search_engine_default,
                R.string.web_search_engine_native,
                R.string.web_search_engine_exa,
                R.string.web_search_engine_firecrawl,
                R.string.web_search_engine_parallel,
            ),
            current = sharedPreferencesHelper.getWebSearchEngine(),
            fallbackIndex = 0,
        ) { engine ->
            sharedPreferencesHelper.saveWebSearchEngine(engine)
            showWebSearchContextSizeDialog()
        }
    }

    private fun showWebSearchContextSizeDialog() {
        pickOne(
            title = R.string.dialog_search_context_size,
            items = listOf("low", "medium", "high"),
            labels = intArrayOf(
                R.string.web_search_context_low,
                R.string.web_search_context_medium,
                R.string.web_search_context_high,
            ),
            current = sharedPreferencesHelper.getWebSearchContextSize(),
            fallbackIndex = 1,
        ) { size ->
            sharedPreferencesHelper.saveWebSearchContextSize(size)
            showWebSearchMaxResultsDialog()
        }
    }

    private fun showWebSearchMaxResultsDialog() {
        val options = (1..20).toList()
        pickOne(
            title = R.string.dialog_max_search_results,
            items = options,
            labels = options.map { it.toString() }.toTypedArray(),
            currentIndex = options.indexOf(sharedPreferencesHelper.getWebSearchMaxResults()).takeIf { it >= 0 } ?: 4,
        ) { count ->
            sharedPreferencesHelper.saveWebSearchMaxResults(count)
        }
    }

    private fun <T> pickOne(
        title: Int,
        items: List<T>,
        labels: IntArray,
        current: String,
        fallbackIndex: Int,
        onSave: (T) -> Unit,
    ) {
        pickOne(
            title = title,
            items = items,
            labels = labels.map { getString(it) }.toTypedArray(),
            currentIndex = items.indexOfFirst { it.toString() == current }.takeIf { it >= 0 } ?: fallbackIndex,
            onSave = onSave,
        )
    }

    private fun <T> pickOne(
        title: Int,
        items: List<T>,
        labels: Array<String>,
        currentIndex: Int,
        onSave: (T) -> Unit,
    ) {
        var selected = items[currentIndex]
        GlassAlertDialogBuilder(requireContext(), R.style.CustomMaterialAlertDialogTheme)
            .setTitle(title)
            .setSingleChoiceItems(labels, currentIndex) { _, which -> selected = items[which] }
            .setPositiveButton(R.string.action_save) { _, _ -> onSave(selected) }
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
    private var scrollerCanUp: Boolean? = null
    private var scrollerCanDown: Boolean? = null

    /** Scroll-button visibility. onScrolled already has a laid-out list, so no posted runnable per pixel. */
    private fun refreshScrollButtons() {
        val up = chatRecyclerView.canScrollVertically(-1)
        val down = chatRecyclerView.canScrollVertically(1)
        if (up == scrollerCanUp && down == scrollerCanDown) return
        scrollerCanUp = up
        scrollerCanDown = down
        scrollToTopButton.setShownAnimated(up)
        scrollToBottomButton.setShownAnimated(down)
    }

    private var progressArmedAt = 0L

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
            // Reschedule the hide at most every 50ms so a fling does not post a callback per pixel.
            if (progressBar.alpha < 0.59f) {
                progressBar.animate().cancel()
                progressBar.alpha = 0.6f
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - progressArmedAt >= 50L) {
                progressArmedAt = now
                progressBar.removeCallbacks(hideScrollProgress)
                progressBar.postDelayed(hideScrollProgress, 700)
            }
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

        val buttons = listOf(
            Triple(reasoningButton, topReasoningButton) {
                model != null && viewModel.canRequestReasoning(model)
            },
            // Web search left the chat chrome; presets can still switch it on.
            Triple(webSearchButton, topWebSearchButton) { false },
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
    fun copyLatestMessage() {
        chatAdapter.getLatestPlainText()?.let { text ->
            if (text.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied", text))
                // The app steps aside right after, so a tick is the only confirmation there is time for.
                Haptics.tap(backcopyButton, android.view.HapticFeedbackConstants.CONFIRM)
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
    /** Tiles in the composer and aux rows, in layout order; captured once because reflow moves them. */
    private var controlTileOrder: List<View>? = null

    /** Packs the visible tiles into the leading slots so hidden ones leave no holes in the grid. */
    private fun reflowControlTiles() {
        val root = view ?: return
        val slots = listOf(R.id.buttonsRowComposer, R.id.buttonsRowAux).flatMap { id ->
            val row = root.findViewById<LinearLayout>(id) ?: return
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<FrameLayout>()
        }
        if (slots.isEmpty()) return
        val tiles = controlTileOrder
            ?: slots.flatMap { slot -> (0 until slot.childCount).map { slot.getChildAt(it) } }
                .also { controlTileOrder = it }
        val (shown, hidden) = tiles.partition { it.isVisible }
        slots.forEach { it.removeAllViews() }
        shown.forEachIndexed { i, tile -> slots[i.coerceAtMost(slots.lastIndex)].addView(tile) }
        hidden.forEach { slots.last().addView(it) }
    }

    private fun updateMenuRowVisibilities() {
        reflowControlTiles()
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
                    val child = row.getChildAt(i)
                    if (child is MaterialButton && child.isVisible) {
                        hasVisibleChild = true
                        break
                    }
                    if (child is ViewGroup) {
                        for (j in 0 until child.childCount) {
                            val nested = child.getChildAt(j)
                            if (nested is MaterialButton && nested.isVisible) {
                                hasVisibleChild = true
                                break
                            }
                        }
                    }
                    if (hasVisibleChild) break
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
            GlassNotice.show(requireContext(), getString(R.string.model_switch_no_webp))
        } else if (hasImagesInCurrentChat && !viewModel.isVisionModel(modelString)) {
            GlassNotice.show(requireContext(), getString(R.string.model_switch_no_vision))
        } else {
            viewModel.setModel(modelString)
            if (viewModel.activeModelIsLan()) {
                checkLocalNetworkPermission()
            }
        }
    }


    // ── Roleplay home ──────────────────────────────────────────────────────────────────────

    private fun setupRpHome(root: View, savedInstanceState: Bundle?) {
        parentFragmentManager.setFragmentResultListener(RpChatHistoryFragment.RESULT, viewLifecycleOwner) { _, result ->
            onRpHistoryPicked(result)
        }
        val homeRoot = root.findViewById<View>(R.id.rpHome)
        rpHome = RpChatsHome(
            homeRoot,
            onOpen = { row ->
                val session = row.sessionId
                if (session != null) {
                    viewModel.loadChat(session)
                    closeRpHome()
                } else row.character?.let { startRpWith(it, ask = false) }
            },
            onMenu = { anchor, row -> showRpHomeMenu(anchor, row) }
        )
        rpHomeOpen = savedInstanceState?.getBoolean(STATE_RP_HOME) ?: viewModel.isRpMode()
        rpResumeAtHome = savedInstanceState?.getBoolean(STATE_RP_RESUME) ?: true
        lastSeenChatMode = if (viewModel.isRpMode()) ChatMode.RP else null
        // Keep the first row clear of the floating bar, whatever height it has.
        val topGlass = root.findViewById<View>(R.id.topBarGlass)
        topGlass.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> rpHome?.setTopInset(v.height) }
        val savedChats = ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[SavedChatsViewModel::class.java]
        savedChats.sessionsForMode(ChatMode.RP).observe(viewLifecycleOwner) { sessions ->
            rpHomeSessions = sessions.orEmpty()
            refreshRpHome()
        }
        viewModel.getRpRepository().allCharacters.observe(viewLifecycleOwner) { chars ->
            rpHomeCharacters = chars.orEmpty()
            refreshRpHome()
        }
        viewModel.rpThreadOpenedEvent.observe(viewLifecycleOwner) { event ->
            if (event.getContentIfNotHandled() != null) {
                rpHomeSuppressed = true
                closeRpHome()
            }
        }
    }

    /** Roleplay's characters list: a row each, with the last line of the newest chat if there is one. */
    private fun refreshRpHome() {
        val home = rpHome ?: return
        val sessions = rpHomeSessions
        val characters = rpHomeCharacters
        rpHomeRefresh?.cancel()
        rpHomeRefresh = viewLifecycleOwner.lifecycleScope.launch {
            val app = requireContext().applicationContext
            // Normally already open; only a cold first open is worth the hop off Main.
            val dao = (if (AppDatabase.isOpen()) AppDatabase.getDatabase(app) else withContext(Dispatchers.IO) { AppDatabase.getDatabase(app) }).chatDao()
            val llm = getString(R.string.rp_llm_speaker)
            val none = getString(R.string.rp_home_no_preview)
            val start = getString(R.string.rp_home_start)
            val heads = RpChatSummaries.build(sessions, characters, emptyMap(), llm, none, start)
            val previews = heads.mapNotNull { row ->
                val id = row.sessionId ?: return@mapNotNull null
                val last = dao.getLastMessage(id)
                val text = last?.let { RpChatSummaries.previewOf(it.content) }.orEmpty()
                id to if (last?.role == "user" && text.isNotBlank()) getString(R.string.rp_home_you, text) else text
            }.toMap()
            home.submit(RpChatSummaries.build(sessions, characters, previews, llm, none, start))
            updateRpHome()
        }
    }

    private var rpUiWas: Boolean? = null
    private var rpShowWas = false
    private var rpHomeAnim: android.animation.ValueAnimator? = null

    /** Whether the home shows follows the mode and [rpHomeOpen]; the composer steps aside while it does. */
    private fun updateRpHome() {
        val root = view ?: return
        val show = rpHomeOpen && viewModel.isRpMode() && !codeMode.isActive
        val rpUi = viewModel.isRpMode() && !codeMode.isActive
        // A thread opening from the list (or closing back to it) slides; a mode switch does not.
        val slide = rpUiWas == true && rpUi && show != rpShowWas && root.isAttachedToWindow &&
            root.width > 0 && Motion.areAnimationsEnabled(requireContext())
        val settled = rpHomeAnim == null
        if (!settled && show != rpShowWas) rpHomeAnim?.end()
        rpUiWas = rpUi
        val changed = show != rpShowWas
        rpShowWas = show
        val homeView = root.findViewById<View>(R.id.rpHome)
        if (slide && changed) {
            slideRpHome(root, homeView, toHome = show)
        } else if (rpHomeAnim == null) {
            rpHome?.show(show)
            // Mid-swipe the list arrives with the other pages, not on top of them.
            if (show) pager?.let { p -> homeView?.translationX = p.shot.translationX - p.direction * p.w }
        }
        // The top bar follows: on the list, Settings and the character manager; inside a chat, the way back to the list.
        val inThread = rpUi && !show
        openSavedChatsButton.setIconResource(when {
            inThread -> R.drawable.ic_chevron_left
            show -> R.drawable.ic_gear
            else -> R.drawable.ic_grok_menu
        })
        openSavedChatsButton.contentDescription = getString(when {
            inThread -> R.string.rp_home_back
            show -> R.string.settings_title
            else -> R.string.cd_history
        })
        newChatButton.setIconResource(if (show) R.drawable.rp_ic_characters else R.drawable.ic_new_chat)
        newChatButton.contentDescription = getString(if (show) R.string.rp_home_manage else R.string.grok_new_conversation)
        // While the chat slides away the composer goes with it; it is hidden when the slide ends.
        if (!(slide && changed && show)) applyRpHomeComposer(root, show)
    }

    /** Only undo what the home did itself: Code mode hides the same composer for its own reasons. */
    private fun applyRpHomeComposer(root: View, show: Boolean) {
        if (show == rpHomeHidComposer) return
        rpHomeHidComposer = show
        val state = if (show) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.composerDock)?.visibility = state
        root.findViewById<View>(R.id.composerFade)?.visibility = state
    }

    /**
     * The thread slides over the list from the right while the list drifts left and dims (and the
     * other way round going back). The chat layers borrow a little elevation so they draw above it.
     */
    private fun slideRpHome(root: View, home: View?, toHome: Boolean) {
        home ?: return
        val w = root.width.toFloat()
        val d = resources.displayMetrics.density
        val layers = listOfNotNull(
            root.findViewById<View>(R.id.chatFrameView),
            root.findViewById<View>(R.id.composerFade),
            root.findViewById<View>(R.id.composerDock)
        )
        if (!toHome) applyRpHomeComposer(root, false)
        home.visibility = View.VISIBLE
        layers.forEach { it.translationZ = 8 * d }
        // The bar stays put above the sliding layers, which would otherwise draw over it.
        val bar = root.findViewById<View>(R.id.topBarGlass)
        bar?.translationZ = 16 * d
        val parallax = 0.25f * w
        fun place(p: Float) {
            // p: 0 = list in front, 1 = thread in front
            layers.forEach { it.translationX = w * (1f - p) }
            home.translationX = -parallax * p
            home.alpha = 1f - 0.5f * p
        }
        val from = if (toHome) 1f else 0f
        val to = 1f - from
        place(from)
        rpHomeAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 380
            interpolator = Motion.iosOut
            addUpdateListener { place(it.animatedValue as Float) }
            val finish = {
                layers.forEach { it.translationX = 0f; it.translationZ = 0f }
                bar?.translationZ = 0f
                home.translationX = 0f
                home.alpha = 1f
                rpHomeAnim = null
                if (view != null) {
                    rpHome?.show(rpShowWas)
                    applyRpHomeComposer(root, rpShowWas)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = finish()
            })
            start()
        }
    }

    /** Back to the chats list. */
    fun openRpHome() {
        if (!viewModel.isRpMode()) return
        rpHomeOpen = true
        updateRpHome()
    }

    /** A thread is open: the composer and transcript take over from the list. */
    fun closeRpHome() {
        if (!rpHomeOpen) return
        rpHomeOpen = false
        updateRpHome()
    }

    /** The ⋮ on a chats-list row: start over with that character, edit it, or delete the chat. */
    private fun showRpHomeMenu(anchor: View, row: RpChatSummary) {
        val root = view as? FrameLayout ?: return
        messageMenu?.dismiss(animated = false)
        val items = buildList {
            if (row.character != null) {
                // A tap already starts the first chat with a character you have not talked to.
                if (row.sessionId != null) add(MessageMenu.Item(getString(R.string.rp_home_menu_new), R.drawable.ic_new_chat) { startRpWith(row.character) })
                add(MessageMenu.Item(getString(R.string.rp_home_menu_edit), R.drawable.ic_msg_edit) { pushRp(RpCharacterEditFragment.newInstance(row.character.id)) })
            } else if (row.isLlm) {
                add(MessageMenu.Item(getString(R.string.rp_home_menu_new), R.drawable.ic_new_chat) {
                    closeRpHome()
                    viewModel.startRpLlmChat()
                })
            }
            // A character you have not talked to has no chat to delete.
            val session = row.sessionId
            if (session != null) add(MessageMenu.Item(getString(R.string.rp_home_menu_delete), R.drawable.ic_msg_delete, destructive = true) {
                GrokConfirmDialog.show(
                    fragment = this@ChatFragment,
                    title = getString(R.string.rp_home_delete_title),
                    message = getString(R.string.rp_home_delete_body, row.name),
                    confirmText = getString(R.string.rp_menu_delete),
                    onConfirm = {
                        sharedPreferencesHelper.setSessionPinned(session, false)
                        viewModel.notifySessionDeleted(session)
                        ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[SavedChatsViewModel::class.java]
                            .deleteSession(session)
                    }
                )
            })
        }
        messageMenu = MessageMenu(root, anchor, root.findViewById(R.id.chatBackdrop)).also { m ->
            m.onDismiss = { if (messageMenu === m) messageMenu = null }
            m.show(items, viewLifecycleOwner)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RP_HOME, rpHomeOpen)
        outState.putBoolean(STATE_RP_RESUME, rpResumeAtHome)
    }

    private var pickerPopover: PickerPopover? = null
    private var messageMenu: MessageMenu? = null

    /** The ⋮ on a reply. A second tap on the same ⋮ folds it. */
    private fun showMessageMenu(anchor: View, items: List<MessageMenu.Item>) {
        val root = view as? FrameLayout ?: return
        if (messageMenu?.isOpenOn(anchor) == true) {
            messageMenu?.dismiss()
            return
        }
        pickerPopover?.dismiss(animated = false)
        messageMenu?.dismiss(animated = false)
        anchor.isSelected = true
        messageMenu = MessageMenu(
            root, anchor, root.findViewById(R.id.chatBackdrop),
            topBound = root.findViewById(R.id.topBarGlass),
            bottomBound = chatInputContainer.takeIf { it.isShown }
        ).also { m ->
            m.onDismiss = { anchor.isSelected = false; if (messageMenu === m) messageMenu = null }
            m.show(items, viewLifecycleOwner)
        }
    }

    private fun newPopover(
        anchor: View = modelNameTextView,
        /** Surface the card clears; defaults to the composer. Pass [anchor] to hug a mid-list control. */
        edge: View = chatInputContainer,
        onOpenChange: (Boolean) -> Unit = { open -> modelNameTextView.isSelected = open }
    ): PickerPopover? {
        val root = view as? FrameLayout ?: return null
        // Second tap on the control that opened it folds the card.
        if (pickerPopover?.isOpenOn(anchor) == true) {
            pickerPopover?.dismiss()
            return null
        }
        pickerPopover?.dismiss(animated = false)
        return PickerPopover(root, anchor, root.findViewById(R.id.chatBackdrop), edge).also { p ->
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
            .sortedBy { ModelNames.withoutProvider(it.displayName, it.apiIdentifier).lowercase() }
        val rows = models.map { m ->
            PickerPopover.Row(
                title = ModelNames.withoutProvider(m.displayName, m.apiIdentifier),
                subtitle = ModelRow.subtitle(requireContext(), m),
                iconRes = ModelBrands.of(m)?.icon ?: if (m.isLANModel) R.drawable.ic_local_network else 0,
                monogram = if (ModelBrands.of(m) == null && !m.isLANModel) ModelRow.monogramFor(m) else null,
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

    /** RP pill: switch character in place, plus the roleplay home and the RP model. */
    private fun showCharacterPopover(anchor: View = newChatButton) {
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
            newPopover(anchor = anchor, edge = anchor, onOpenChange = { })
                ?.show(getString(R.string.popover_characters_title), rows, footer)
        }
    }

    /** Speaker line: the panel for the active character, or the picker when there's none yet. */
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
        val personaOn = sharedPreferencesHelper.isRpPersonaEnabled()
        val personaName = sharedPreferencesHelper.activeRpPersonaName()
        val cast = character?.takeIf { !llm }
        val tiles = buildList {
            // Three rows of three: the story (its chats, what is remembered, the world), the people
            // (the character, how they sound, who you are), and how it looks.
            add(RpCharacterPanel.Tile(R.string.rp_panel_history, RpTileArt.Kind.HISTORY) {
                pushRp(RpChatHistoryFragment.newInstance(cast?.id))
            })
            add(RpCharacterPanel.Tile(R.string.rp_panel_memory, RpTileArt.Kind.MEMORY, on = hasMemory || viewModel.currentRpFacts().isNotBlank()) {
                pushRp(RpMemoryFragment.newInstance(memoryId, title))
            })
            val pinnedId = cast?.let { sharedPreferencesHelper.getRpLorebookId(it.id) }
            add(RpCharacterPanel.Tile(R.string.rp_panel_lore, RpTileArt.Kind.LORE, on = pinnedId != null) {
                openRpLore(cast)
            })
            if (cast != null) {
                add(RpCharacterPanel.Tile(R.string.rp_panel_edit, RpTileArt.Kind.EDIT) { pushRp(RpCharacterEditFragment.newInstance(cast.id)) })
            }
            val voice = sharedPreferencesHelper.getRpVoice(memoryId)
            // Named voices label as "Voice n" from the engine's list, so a saved one starts it and redraws when it is up.
            val tts = TtsHolder.ready()
            if (voice.name != null && tts == null) {
                TtsHolder.whenReady(requireContext()) { if (it != null && rpPanel?.isShowing == true) refreshRpPanel() }
            }
            val tweaks = listOfNotNull(
                when { voice.pitch < 1f -> getString(R.string.rp_voice_low); voice.pitch > 1f -> getString(R.string.rp_voice_high); else -> null },
                when { voice.rate < 1f -> getString(R.string.rp_voice_slow); voice.rate > 1f -> getString(R.string.rp_voice_fast); else -> null },
            )
            val voiceName = RpVoiceDialog.label(this@ChatFragment, tts, voice.name)
            val voiceLabel = if (voiceName == null && tweaks.isEmpty()) null
                else (listOf(voiceName ?: getString(R.string.rp_voice_default)) + tweaks).joinToString(" · ")
            add(RpCharacterPanel.Tile(R.string.rp_panel_voice, RpTileArt.Kind.VOICE, on = voiceLabel != null, preview = voiceLabel) {
                pushRp(RpVoiceFragment.newInstance(memoryId, title))
            })
            // The card is the portrait alone; the name would crowd it, and off shows the empty silhouette.
            val personaPhoto = sharedPreferencesHelper.getRpPersonaPhoto()?.takeIf { personaOn }
                ?.let { RpAvatarStorage.personaFile(requireContext(), it) }?.takeIf { it.isFile }
            add(RpCharacterPanel.Tile(R.string.rp_panel_persona, RpTileArt.Kind.PERSONA, image = personaPhoto, letter = personaName.trim().ifBlank { null }) { pushRp(RpPersonaFragment.newInstance()) })
            if (cast != null) {
                val slot = BackgroundPhoto.slotForCharacter(cast.id)
                val wallpaper = BackgroundPhoto.file(requireContext(), slot).takeIf { it.isFile }
                add(RpCharacterPanel.Tile(R.string.rp_panel_wallpaper, RpTileArt.Kind.WALLPAPER, on = wallpaper != null, image = wallpaper) {
                    pushRp(RpWallpaperFragment.newInstance(cast.id, cast.name))
                })
            }
            val layout = sharedPreferencesHelper.getRpLayout(memoryId)
            val layoutArt = when (layout) {
                SharedPreferencesHelper.RP_LAYOUT_BUBBLES -> RpTileArt.Kind.LAYOUT_BUBBLES
                SharedPreferencesHelper.RP_LAYOUT_BOOK -> RpTileArt.Kind.LAYOUT_BOOK
                else -> RpTileArt.Kind.LAYOUT_CLASSIC
            }
            add(RpCharacterPanel.Tile(R.string.rp_panel_layout, layoutArt, spoken = getString(layoutLabel(layout))) {
                pushRp(RpLayoutFragment.newInstance(memoryId, title))
            })
            add(RpCharacterPanel.Tile(R.string.rp_panel_style, RpTileArt.Kind.STYLE) { pushRp(RpSettingsFragment.newInstance(R.string.rp_panel_style)) })
        }
        val content = RpCharacterPanel.content(this, cast, title, subtitle, tiles)
        val host = rpPanel ?: RpCharacterPanel.Host(requireView() as FrameLayout).also { rpPanel = it }
        hideKeyboard()
        host.show(content) { if (rpPanel === host) rpPanel = null }
    }

    /** Rebuild the open sheet in place (a page it opened may have changed a tile), with no slide. */
    private fun refreshRpPanel() {
        if (!isAdded || view == null) return
        // The look this chat was drawn with can have changed too: layout and wallpaper.
        ambientBackground?.photoSlot = null
        updateRpChrome()
        showRpCharacterPanel()
    }

    private fun closeRpPanel(animated: Boolean = true) {
        rpPanel?.dismiss(animated)
    }

    /** Lore tile: pin a book to this character; with no books yet it opens the library instead. */
    private fun openRpLore(cast: RpCharacter?) {
        if (cast == null) {
            pushRp(RpLorebookLibraryFragment.newInstance())
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val any = viewModel.getRpRepository().getAllLorebooksOnce().isNotEmpty()
            if (view == null) return@launch
            pushRp(if (any) RpLorePinFragment.newInstance(cast.id, cast.name) else RpLorebookLibraryFragment.newInstance())
        }
    }

    /** What the chats page picked: a chat to open, or a fresh one with the same character. */
    private fun onRpHistoryPicked(result: Bundle) {
        closeRpPanel(animated = false)
        if (result.containsKey(RpChatHistoryFragment.OPEN)) {
            viewModel.loadChat(result.getLong(RpChatHistoryFragment.OPEN))
            return
        }
        val character = viewModel.activeRpCharacter.value
        if (sharedPreferencesHelper.isRpLlmMode() || character == null) {
            closeRpHome()
            viewModel.startRpLlmChat()
        } else startRpWith(character)
    }

    private fun layoutLabel(layout: String) = when (layout) {
        SharedPreferencesHelper.RP_LAYOUT_BUBBLES -> R.string.rp_layout_bubbles
        SharedPreferencesHelper.RP_LAYOUT_BOOK -> R.string.rp_layout_book
        else -> R.string.rp_layout_classic
    }

    /** Before reading aloud: the active character's voice in Roleplay, the phone's default elsewhere. */
    private fun applyReadAloudVoice(tts: TextToSpeech) {
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

    /** A full-screen page over the chat and the open panel: it slides in, the panel stays put under it. */
    private fun pushRp(fragment: Fragment) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        hideKeyboard()
        rpPanelDepth = parentFragmentManager.backStackEntryCount
        parentFragmentManager.beginTransaction()
            .withGrokPushOver()
            .add(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * [ask] is off for the first chat with a character picked from the list: nothing on screen is
     * being replaced, so there is no chat to confirm over and no facts to carry.
     */
    private fun startRpWith(character: RpCharacter, ask: Boolean = true) {
        val start = { carry: Boolean ->
            closeRpHome()
            viewModel.startRpChatWithCharacter(character, carry)
        }
        if (!ask) {
            start(false)
        } else if (viewModel.currentRpFacts().isNotBlank()) {
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
        applyChatMark()
    }

    /** Empty-chat icon: off, the flat vector, or the liquid glass mark. */
    private fun applyChatMark() {
        if (!::centerWatermarkIcon.isInitialized) return
        centerWatermarkIcon.markStyle = when (sharedPreferencesHelper.getChatMarkStyle()) {
            SharedPreferencesHelper.CHAT_MARK_OFF -> LiquidMarkView.MarkStyle.OFF
            SharedPreferencesHelper.CHAT_MARK_PLAIN -> LiquidMarkView.MarkStyle.PLAIN
            else -> LiquidMarkView.MarkStyle.LIQUID
        }
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
        if (!animate && indicatorPlaced && kotlin.math.abs(x - indicatorTargetX) < 0.5f) return
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
        controlsButton.setIconResource(if (rp) R.drawable.ic_rp_scene else R.drawable.ic_sliders)
        controlsButton.contentDescription = getString(if (rp) R.string.cd_rp_scene else R.string.cd_controls)
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
            // A staged photo goes along to the scene; files and audio stay in Chat.
            val hadAttachments = selectedAudioBytes != null || pendingFiles.isNotEmpty()
            if (selectedAudioBytes != null) attachmentPreviewContainer.visibility = View.GONE
            selectedAudioBytes = null
            selectedAudioFormat = null
            pendingFiles.clear()
            updateAttachmentButton()
            if (hadAttachments) {
                GlassNotice.show(requireContext(), getString(R.string.rp_attachments_disabled))
            }
            applyModelCapabilityChrome(viewModel.activeChatModel.value)
            presetsButton.visibility = View.GONE
            topPresetsButton.visibility = View.GONE
            presetsButton2.visibility = View.GONE
            val charName = activeChar?.name
            modelNameTextView.visibility = View.GONE
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
            modelNameTextView.visibility = View.VISIBLE
            viewModel.activeChatModel.value?.let { modelNameTextView.text = viewModel.getModelDisplayName(it) }
            chatEditText.hint = getString(R.string.grok_composer_hint)
            applyModelCapabilityChrome(viewModel.activeChatModel.value)
            updateModelSourceIndicator()
        }
        updateExtendedTopBarVisibility(sharedPreferencesHelper.getExtendedTopBarEnabled())
        updateComposerAccessoryVisibility()
        updateRpHome()
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
        if (!input.contains("{{ox")) return input

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
                    font-size: 16px; line-height: 1.5; color: #222222; background: white;
                }
                .markdown-body { font-size: 16px; line-height: 1.5; }
                h1 { 
                    color: #222222 !important; font-size: 2em !important; font-weight: 600 !important; 
                    text-decoration: underline !important;
                    border-bottom: none !important;
                    padding-bottom: .3em !important; margin: 0 0 1em 0 !important; 
                }
                a { color: #333333; text-decoration: none; }
                a:hover, a:focus { text-decoration: underline; }
                @media print { a { text-decoration: underline !important; color: #333333 !important; } }
                strong { font-weight: 600; }
                pre, code { font-family: 'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace; font-size: 14px; }
                code { background: #f4f4f4; border-radius: 6px; padding: .2em .4em; }
                pre { background: #f4f4f4; border-radius: 6px; padding: 16px; overflow: auto; margin: 1em 0; }
                blockquote { border-left: 4px solid #dddddd; color: #6b6b6b; padding-left: 1em; margin: 1em 0; }
                table { border-collapse: collapse; width: 100%; margin: 1em 0; }
                th, td { border: 1px solid #d4d4d4; padding: .75em; text-align: left; }
                th { background: #f4f4f4; font-weight: 600; }
                hr { border: none; border-top: 1px solid #e8e8e8; height: 0; margin: 1.5em 0; }
                ul, ol { padding-left: 2em; margin: 1em 0; }
                img { max-width: 100%; height: auto; }
                del { color: #6b6b6b; }
                input[type="checkbox"] { margin: 0 .25em 0 0; vertical-align: middle; }
                
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
                    div[style*="margin-bottom: 2em"]:has(> div[style*="padding: 0.05em"]) {
                        margin-bottom: 0.25em !important;  /* ✅ User → assistant: tight */
                    }
                    div[style*="margin-bottom: 2em"]:not(:has(> div[style*="padding: 0.05em"])) {
                        margin-bottom: 2em !important;  /* ✅ Assistant → next user: spacer ONLY here */
                    }
                    
                    /* ✅ ASSISTANT: Plain text (no bg/border) */
                    h3 + div:not([style*="padding: 0.05em"]) {
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
                    h3 + div[style*="padding: 0.05em"] {
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
}

private const val TTS_SPEAK_ID = "tts_utterance"
private const val STATE_RP_HOME = "rp_home_open"
private const val STATE_RP_RESUME = "rp_resume_at_home"
