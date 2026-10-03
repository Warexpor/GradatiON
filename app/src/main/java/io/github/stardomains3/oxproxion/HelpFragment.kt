package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlin.math.roundToInt
import androidx.core.net.toUri

class HelpFragment : Fragment(R.layout.fragment_help) {
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
                SharedPreferencesHelper(requireContext()).saveSafFolderUri(uri.toString())
                GlassNotice.show(requireContext(), getString(R.string.toast_folder_updated))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
         val dp24 = with(requireContext().resources.displayMetrics) {
            (24 * density).roundToInt()
        }
        // Placeholders in res/raw/help.md: {{ic_name}} becomes that icon inline.
        val icons = mapOf(
            "ic_new_chat" to R.drawable.ic_new_chat,
            "ic_schats" to R.drawable.ic_schats,
            "ic_plus" to R.drawable.ic_plus,
            "ic_sliders" to R.drawable.ic_sliders,
            "ic_mic" to R.drawable.ic_mic,
            "ic_check" to R.drawable.ic_check,
            "ic_send" to R.drawable.ic_send,
            "ic_stop" to R.drawable.ic_stop
        )

        // The glyphs carry whatever color they were drawn in (black, white, light gray), so
        // without the ink tint some vanish on the dark page and others on the light one.
        val ink = ContextCompat.getColor(requireContext(), R.color.xai_ink)
         val iconSpans: Map<String, ImageSpan> = icons.mapValues { (_, resId) ->
            val drawable = ContextCompat.getDrawable(requireContext(), resId)!!.mutate().apply {
                setBounds(0, 0, dp24, dp24)
                setTint(ink)
            }
            ImageSpan(drawable)
        }

        val helpContentTextView = view.findViewById<TextView>(R.id.helpContentTextView)
        val sharedPreferencesHelper = SharedPreferencesHelper(requireContext())
        val selectedFontName = sharedPreferencesHelper.getSelectedFont()
        val typeface = AppFonts.resolveSelectable(requireContext(), selectedFontName)
        val versionName = getAppVersionName(requireContext())
        helpContentTextView.typeface = typeface
        val markwon = Markwon.builder(requireContext())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(CoilImagesPlugin.create(requireContext()))
            .usePlugin(TablePlugin.create(requireContext()))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    val ctx = requireContext()
                    builder
                        .codeTextColor(ContextCompat.getColor(ctx, R.color.markwon_code_text))
                        .codeBackgroundColor(ContextCompat.getColor(ctx, R.color.markwon_code_bg))
                        .codeBlockBackgroundColor(ContextCompat.getColor(ctx, R.color.markwon_code_bg))
                        .blockQuoteColor(ContextCompat.getColor(ctx, R.color.markwon_blockquote))
                        .isLinkUnderlined(true)
                }
            })
            .build()


        val markdownContent = resources.openRawResource(R.raw.help).bufferedReader().use { it.readText() }
            .replace("{{version}}", versionName)

        markwon.setMarkdown(helpContentTextView, markdownContent)
        val text = helpContentTextView.text
        val spannable1 = SpannableStringBuilder(text)
        iconSpans.forEach { (name, span) ->
            val placeholder = "{{$name}}"
            var start = 0
            while (true) {
                start = spannable1.indexOf(placeholder, start)
                if (start == -1) break
                val end = start + placeholder.length
                spannable1.replace(start, end, " ")  // Single space for icon slot
                spannable1.setSpan(span, start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                start += 1
            }
        }
        helpContentTextView.setText(spannable1, TextView.BufferType.SPANNABLE)
        helpContentTextView.movementMethod = LinkMovementMethod.getInstance()
        // Custom handler for licenses link
        val spannable = helpContentTextView.text as? Spannable ?: return
        val urlSpans: Array<URLSpan> = spannable.getSpans(0, spannable.length, URLSpan::class.java)

        for (urlSpan in urlSpans) {
            // In-app links: no app handles these schemes, so a plain URLSpan tap does nothing.
            val action: () -> Unit = when (urlSpan.url) {
                LINK_LICENSES -> {
                    {
                        parentFragmentManager.beginTransaction()
                            .withGrokStackAnimations()
                            .hide(this@HelpFragment)
                            .add(R.id.fragment_container, LicenseListFragment())
                            .addToBackStack(null)
                            .commit()
                    }
                }
                LINK_RESELECT_FOLDER -> {
                    {
                        folderPickerLauncher.launch(
                            "content://com.android.externalstorage.documents/document/primary%3ADownload".toUri()
                        )
                    }
                }
                else -> continue
            }
            val start = spannable.getSpanStart(urlSpan)
            val end = spannable.getSpanEnd(urlSpan)
            val flags = spannable.getSpanFlags(urlSpan)
            spannable.removeSpan(urlSpan)
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = action()

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(requireContext(), R.color.xai_link)
                    ds.isUnderlineText = true
                }
            }, start, end, flags)
        }
        helpContentTextView.text = spannable
        helpContentTextView.isClickable = true

    }
    private fun getAppVersionName(context: Context): String {
        return try {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: getString(R.string.help_version_unknown)
        } catch (e: Exception) {
            getString(R.string.help_version_unknown)
        }
    }

    companion object {
        /** Links in res/raw/help.md that open something in the app instead of a browser. */
        const val LINK_LICENSES = "oxproxion://licenses"
        const val LINK_RESELECT_FOLDER = "action://reselect-folder"
    }
}