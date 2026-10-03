package io.github.stardomains3.oxproxion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.SwitchCompat

class ToolsFragment : Fragment(R.layout.fragment_tools) {

    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPolicyLauncher: ActivityResultLauncher<Intent>
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>

    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            // Granting shows on the row itself; only a refusal needs saying.
            if (!isGranted) GlassNotice.show(requireContext(), getString(R.string.toast_location_permission))
            refreshUI()
        }

        notificationPolicyLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            refreshUI()
        }

        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
                sharedPreferencesHelper.saveSafFolderUri(uri.toString())
                refreshUI()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())

        WorkspacePaths.ensureWorkspaceExists()

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        setupToolsList(view)
    }

    private fun setupToolsList(rootView: View) {
        val container = rootView.findViewById<LinearLayout>(R.id.tools_container)
        container.removeAllViews()

        val enabledTools = sharedPreferencesHelper.getEnabledTools()
        val hasStoredPrefs = sharedPreferencesHelper.hasEnabledToolsStored()
        val effectiveEnabledSet = if (!hasStoredPrefs) {
            emptySet()
        } else {
            enabledTools
        }

        var allItems = ToolItem.getAllToolItems(effectiveEnabledSet, requireContext())

        // Filter Brave
        val braveApiKey = sharedPreferencesHelper.getApiKeyFromPrefs("brave_search_api_key")
        val hasBraveKey = braveApiKey.isNotEmpty()
        allItems = allItems.filter { item ->
            if (item.name == "brave_search" || item.name == "brave_news" || item.name == "find_nearby_places") {
                hasBraveKey
            } else {
                true
            }
        }


        // Check Permissions
        val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val hasNotificationPolicy = notificationManager.isNotificationPolicyAccessGranted
        val hasLocationPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasSafPermission = sharedPreferencesHelper.hasWorkspaceGrant()

        val inflater = layoutInflater

        for (item in allItems) {
            val row = inflater.inflate(R.layout.item_tool_toggle2, container, false)
            val checkBox = row.findViewById<SwitchCompat>(R.id.checkbox_tool)
            val titleTv = row.findViewById<TextView>(R.id.text_tool_title)
            val descTv = row.findViewById<TextView>(R.id.text_tool_desc)
            val permissionWarning = row.findViewById<TextView>(R.id.text_permission_warning)
            checkBox.applyGrokionSwitchStyle()
            titleTv.text = item.displayName
            descTv.text = item.description

            var needsPermission = false
            var permissionGranted = true
            var permissionIntent: Intent? = null
            var isSafTool = false

            if (item.name == "get_location") {
                needsPermission = true
                permissionGranted = hasLocationPermission
            } else if (item.name == "set_sound_mode") {
                needsPermission = true
                permissionGranted = hasNotificationPolicy
                permissionIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
            // 👇 Handle SAF Tools
            else if (ToolItem.needsFolderGrant(item.name)) {
                needsPermission = true
                permissionGranted = hasSafPermission
                isSafTool = true
            }

            checkBox.isChecked = item.isEnabled
            checkBox.isEnabled = ToolItem.toolSwitchEnabled(needsPermission, permissionGranted, item.isEnabled)

            if (needsPermission && !permissionGranted) {
                if (isSafTool) {
                    permissionWarning.text = getString(R.string.tools_select_folder)
                } else {
                    permissionWarning.text = getString(R.string.tools_permission_required)
                }
                permissionWarning.visibility = View.VISIBLE

                row.setOnClickListener {
                    if (isSafTool) {
                        WorkspacePaths.ensureWorkspaceExists()
                        folderPickerLauncher.launch(null)
                    } else if (item.name == "get_location") {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    } else if (permissionIntent != null) {
                        notificationPolicyLauncher.launch(permissionIntent)
                    }
                }
            } else {
                permissionWarning.visibility = View.GONE
                row.setOnClickListener {
                    checkBox.toggle()
                }
            }

            var ignoreToggle = false
            checkBox.setOnCheckedChangeListener { button, isChecked ->
                if (ignoreToggle) return@setOnCheckedChangeListener
                val accepted = ToolItem.toolEnabledAfterUserToggle(needsPermission, permissionGranted, isChecked)
                if (accepted == null) {
                    ignoreToggle = true
                    button.isChecked = false
                    ignoreToggle = false
                    return@setOnCheckedChangeListener
                }
                sharedPreferencesHelper.saveEnabledTools(
                    ToolItem.enabledToolsAfterToggle(sharedPreferencesHelper.getEnabledTools(), item.name, accepted)
                )
                if (!accepted) {
                    button.isEnabled = ToolItem.toolSwitchEnabled(needsPermission, permissionGranted, toolOn = false)
                }
            }

            container.addView(row)
        }
    }

    private fun refreshUI() {
        val container = view?.findViewById<LinearLayout>(R.id.tools_container)
        container?.let {
            setupToolsList(requireView())
        }
    }
}