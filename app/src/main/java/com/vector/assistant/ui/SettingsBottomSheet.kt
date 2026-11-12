package com.vector.assistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.vector.assistant.R
import com.vector.assistant.ai.GeminiBrainClient
import com.vector.assistant.audio.VoiceprintMatcher
import com.vector.assistant.databinding.DialogSettingsSheetBinding
import com.vector.assistant.model.VectorSessionState
import com.vector.assistant.model.VectorSettings
import com.vector.assistant.service.VectorAccessibilityService
import com.vector.assistant.service.VectorNotificationListenerService
import com.vector.assistant.service.VectorVoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: VectorSettings
    private lateinit var geminiBrain: GeminiBrainClient
    private lateinit var voiceprintMatcher: VoiceprintMatcher

    var onRequestRuntimePermissions: (() -> Unit)? = null
    var onSettingsChanged: (() -> Unit)? = null

    override fun getTheme(): Int = R.style.Theme_VectorBottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            BottomSheetBehavior.from(it).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        settings = VectorSettings(ctx)
        geminiBrain = GeminiBrainClient(ctx)
        voiceprintMatcher = VoiceprintMatcher(ctx)

        setupGeminiSection()
        setupBehaviorControls()
        setupVoiceTrainingSection()
        setupPermissionsSection()
        setupVipAndMemorySection()
        observeDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsUI()
        refreshGeminiUI()
        refreshVoiceProfileUI()
        refreshVipAndMemoryUI()
    }

    private fun setupGeminiSection() {
        refreshGeminiUI()

        binding.btnSheetSaveGemini.setOnClickListener {
            val key = binding.etSheetGeminiKey.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(requireContext(), "Please paste your Gemini API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnSheetSaveGemini.isEnabled = false
            binding.btnSheetSaveGemini.text = "VERIFYING KEY..."

            viewLifecycleOwner.lifecycleScope.launch {
                val result = geminiBrain.verifyAndConnectKey(key)
                if (_binding == null) return@launch
                binding.btnSheetSaveGemini.isEnabled = true

                if (result.success) {
                    binding.etSheetGeminiKey.setText("")
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    VectorSessionState.addLog("Gemini Verified: ${result.model}")
                    onSettingsChanged?.invoke()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    VectorSessionState.addLog("Gemini Verify Failed: ${result.message}")
                }
                refreshGeminiUI()
            }
        }

        binding.btnSheetClearGemini.setOnClickListener {
            geminiBrain.clearApiKey()
            refreshGeminiUI()
            onSettingsChanged?.invoke()
            Toast.makeText(requireContext(), "Gemini key disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshGeminiUI() {
        if (_binding == null) return
        if (geminiBrain.isConnected()) {
            binding.tvSheetGeminiStatus.text = "Status: CONNECTED (${geminiBrain.getWorkingModel()})"
            binding.tvSheetGeminiStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green))
            binding.btnSheetSaveGemini.text = "UPDATE & TEST KEY"
            binding.btnSheetClearGemini.visibility = View.VISIBLE
        } else {
            binding.tvSheetGeminiStatus.text = "Status: Not connected — add key for Urdu/English & screen-off AI"
            binding.tvSheetGeminiStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_amber))
            binding.btnSheetSaveGemini.text = "VERIFY & CONNECT"
            binding.btnSheetClearGemini.visibility = View.GONE
        }
    }

    private fun setupBehaviorControls() {
        binding.switchAutoContinuousChat.isChecked = settings.isAutoContinuousChat
        binding.switchAutoContinuousChat.setOnCheckedChangeListener { _, isChecked ->
            settings.isAutoContinuousChat = isChecked
            onSettingsChanged?.invoke()
        }

        binding.switchWhatsAppAssistant.isChecked = settings.isWhatsAppAssistantEnabled
        binding.switchWhatsAppAssistant.setOnCheckedChangeListener { _, isChecked ->
            settings.isWhatsAppAssistantEnabled = isChecked
        }

        binding.switchHybridWake.isChecked = settings.isSmartHybridWakeEnabled
        binding.switchHybridWake.setOnCheckedChangeListener { _, isChecked ->
            settings.isSmartHybridWakeEnabled = isChecked
            onSettingsChanged?.invoke()
        }

        binding.switchWakeScreen.isChecked = settings.isWakeScreenOnCommand
        binding.switchWakeScreen.setOnCheckedChangeListener { _, isChecked ->
            settings.isWakeScreenOnCommand = isChecked
        }

        binding.switchOverlayHud.isChecked = settings.isOverlayHudEnabled
        binding.switchOverlayHud.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(requireContext())) {
                openOverlayPermissionSettings()
            }
            settings.isOverlayHudEnabled = isChecked
        }

        when (settings.micSensitivityLevel) {
            0 -> binding.chipGroupSensitivity.check(R.id.chipSensHigh)
            2 -> binding.chipGroupSensitivity.check(R.id.chipSensStrict)
            else -> binding.chipGroupSensitivity.check(R.id.chipSensBalanced)
        }
        binding.chipGroupSensitivity.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            settings.micSensitivityLevel = when (checkedId) {
                R.id.chipSensHigh -> 0
                R.id.chipSensStrict -> 2
                else -> 1
            }
        }

        when (settings.ttsVoiceMode) {
            1 -> binding.chipGroupTtsVoice.check(R.id.chipTtsEnglish)
            2 -> binding.chipGroupTtsVoice.check(R.id.chipTtsUrdu)
            else -> binding.chipGroupTtsVoice.check(R.id.chipTtsAuto)
        }
        binding.chipGroupTtsVoice.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            settings.ttsVoiceMode = when (checkedId) {
                R.id.chipTtsEnglish -> 1
                R.id.chipTtsUrdu -> 2
                else -> 0
            }
        }
    }

    private fun setupVoiceTrainingSection() {
        refreshVoiceProfileUI()

        binding.btnSheetTrainVoice.setOnClickListener {
            val hasMic = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                onRequestRuntimePermissions?.invoke()
                return@setOnClickListener
            }
            recordWakeSample()
        }

        binding.btnSheetResetVoice.setOnClickListener {
            voiceprintMatcher.resetProfile()
            voiceprintMatcher.setBiometricsEnforced(false)
            refreshVoiceProfileUI()
            Toast.makeText(requireContext(), "Voice samples reset", Toast.LENGTH_SHORT).show()
        }

        binding.switchStrictVoiceLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !voiceprintMatcher.isProfileEnrolled()) {
                binding.switchStrictVoiceLock.isChecked = false
                Toast.makeText(requireContext(), "Record all 3 samples first!", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            voiceprintMatcher.setBiometricsEnforced(isChecked)
        }
    }

    private fun refreshVoiceProfileUI() {
        if (_binding == null) return
        val count = voiceprintMatcher.getEnrolledCount()
        binding.sheetVoiceProgress.progress = count
        binding.switchStrictVoiceLock.isChecked = voiceprintMatcher.isBiometricsEnforced() && count >= 3

        if (count >= 3) {
            binding.tvSheetVoiceStatus.text = "Enrolled (3/3): Offline 'Hey Vector' DTW + optional Speaker Lock ready."
            binding.tvSheetVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.neon_green))
            binding.btnSheetTrainVoice.text = "Re-Record Sample (3/3)"
        } else {
            binding.tvSheetVoiceStatus.text = "Record $count/3 samples saying 'Hey Vector' or 'Suno Vector' cleanly."
            binding.tvSheetVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.btnSheetTrainVoice.text = "Record Sample ($count/3)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordWakeSample() {
        if (voiceprintMatcher.getEnrolledCount() >= 3) {
            voiceprintMatcher.resetProfile()
        }

        val ctx = requireContext().applicationContext
        val serviceWasActive = VectorVoiceService.isServiceRunning

        binding.btnSheetTrainVoice.isEnabled = false
        binding.btnSheetTrainVoice.text = "Speak 'Hey Vector' now..."

        viewLifecycleOwner.lifecycleScope.launch {
            var savedCount = -1
            try {
                // Temporarily pause service mic so training AudioRecord gets clean exclusive mic access
                if (serviceWasActive) {
                    ctx.startService(Intent(ctx, VectorVoiceService::class.java).apply {
                        action = VectorVoiceService.ACTION_STOP
                    })
                    delay(550L)
                }

                savedCount = withContext(Dispatchers.IO) {
                    val sampleRate = 16000
                    val channel = AudioFormat.CHANNEL_IN_MONO
                    val format = AudioFormat.ENCODING_PCM_16BIT
                    val bufSize = AudioRecord.getMinBufferSize(sampleRate, channel, format).coerceAtLeast(sampleRate)

                    var recorder: AudioRecord? = null
                    try {
                        for (attempt in 0 until 4) {
                            val candidate = try {
                                val r1 = AudioRecord(
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                    sampleRate,
                                    channel,
                                    format,
                                    bufSize
                                )
                                if (r1.state == AudioRecord.STATE_INITIALIZED) r1 else {
                                    r1.release()
                                    val r2 = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channel, format, bufSize)
                                    if (r2.state == AudioRecord.STATE_INITIALIZED) r2 else {
                                        r2.release()
                                        null
                                    }
                                }
                            } catch (_: Exception) {
                                null
                            }
                            if (candidate != null) {
                                recorder = candidate
                                break
                            }
                            delay(250L)
                        }
                        val activeRecorder = recorder ?: return@withContext -1
                        activeRecorder.startRecording()
                        if (activeRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            return@withContext -1
                        }
                        val totalSamples = sampleRate * 2 // 2 seconds
                        val pcm = ShortArray(totalSamples)
                        var read = 0
                        while (read < totalSamples) {
                            val c = activeRecorder.read(pcm, read, totalSamples - read)
                            if (c > 0) read += c else break
                        }
                        activeRecorder.stop()
                        if (read > sampleRate / 4) voiceprintMatcher.enrollSample(pcm.copyOf(read)) else -1
                    } catch (_: Exception) {
                        -1
                    } finally {
                        try { recorder?.release() } catch (_: Exception) {}
                    }
                }
            } finally {
                if (serviceWasActive) {
                    withContext(NonCancellable) {
                        try {
                            ContextCompat.startForegroundService(
                                ctx,
                                Intent(ctx, VectorVoiceService::class.java).apply {
                                    action = VectorVoiceService.ACTION_START
                                }
                            )
                        } catch (_: Exception) {}
                    }
                }
            }

            if (_binding == null) return@launch
            binding.btnSheetTrainVoice.isEnabled = true
            if (savedCount > 0) {
                Toast.makeText(requireContext(), "Sample #$savedCount saved!", Toast.LENGTH_SHORT).show()
                VectorSessionState.addLog("Voice training sample #$savedCount enrolled.")
            } else {
                Toast.makeText(requireContext(), "Recording failed. Check mic permission.", Toast.LENGTH_SHORT).show()
            }
            refreshVoiceProfileUI()
        }
    }

    private fun setupPermissionsSection() {
        binding.btnSheetPermPhone.setOnClickListener {
            onRequestRuntimePermissions?.invoke()
        }
        binding.btnSheetPermBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
        binding.btnSheetPermOverlay.setOnClickListener {
            openOverlayPermissionSettings()
        }
        binding.btnSheetPermAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(requireContext(), "Enable 'Vector Assistant' in Accessibility", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
        binding.btnSheetPermNotification.setOnClickListener {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                Toast.makeText(requireContext(), "Allow Notification Access for Vector Assistant", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
        refreshPermissionsUI()
    }

    private fun refreshPermissionsUI() {
        if (_binding == null) return
        val ctx = requireContext()

        val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (hasMic && hasCall && hasContacts) {
            binding.btnSheetPermPhone.text = "GRANTED"
            binding.btnSheetPermPhone.setTextColor(ContextCompat.getColor(ctx, R.color.neon_green))
        } else {
            binding.btnSheetPermPhone.text = "ALLOW"
            binding.btnSheetPermPhone.setTextColor(ContextCompat.getColor(ctx, R.color.neon_amber))
        }

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) {
            binding.btnSheetPermBattery.text = "UNRESTRICTED"
            binding.btnSheetPermBattery.setTextColor(ContextCompat.getColor(ctx, R.color.neon_green))
        } else {
            binding.btnSheetPermBattery.text = "ALLOW"
            binding.btnSheetPermBattery.setTextColor(ContextCompat.getColor(ctx, R.color.neon_amber))
        }

        if (Settings.canDrawOverlays(ctx)) {
            binding.btnSheetPermOverlay.text = "ENABLED"
            binding.btnSheetPermOverlay.setTextColor(ContextCompat.getColor(ctx, R.color.neon_green))
        } else {
            binding.btnSheetPermOverlay.text = "ENABLE"
            binding.btnSheetPermOverlay.setTextColor(ContextCompat.getColor(ctx, R.color.neon_cyan))
        }

        val isAccActive = VectorAccessibilityService.instance != null
        if (isAccActive) {
            binding.btnSheetPermAccessibility.text = "ACTIVE"
            binding.btnSheetPermAccessibility.setTextColor(ContextCompat.getColor(ctx, R.color.neon_green))
        } else {
            binding.btnSheetPermAccessibility.text = "ENABLE"
            binding.btnSheetPermAccessibility.setTextColor(ContextCompat.getColor(ctx, R.color.neon_cyan))
        }

        val isNotifActive = isNotificationListenerEnabled(ctx)
        if (isNotifActive) {
            binding.btnSheetPermNotification.text = "ACTIVE"
            binding.btnSheetPermNotification.setTextColor(ContextCompat.getColor(ctx, R.color.neon_green))
        } else {
            binding.btnSheetPermNotification.text = "ENABLE"
            binding.btnSheetPermNotification.setTextColor(ContextCompat.getColor(ctx, R.color.neon_cyan))
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val cn = ComponentName(context, VectorNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(cn.flattenToString())
    }

    private fun requestIgnoreBatteryOptimization() {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == false) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        } else {
            Toast.makeText(ctx, "Battery optimization already unrestricted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlayPermissionSettings() {
        val ctx = requireContext()
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {}
        }
    }

    private fun setupVipAndMemorySection() {
        binding.btnSheetAddVip.setOnClickListener {
            val name = binding.etSheetVipName.text?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) {
                VectorNotificationListenerService.addVipContact(requireContext(), name)
                binding.etSheetVipName.setText("")
                refreshVipAndMemoryUI()
            }
        }

        binding.btnSheetClearMemories.setOnClickListener {
            geminiBrain.memoryManager.clearAllMemories()
            refreshVipAndMemoryUI()
            Toast.makeText(requireContext(), "AI memories cleared", Toast.LENGTH_SHORT).show()
        }
        refreshVipAndMemoryUI()
    }

    private fun refreshVipAndMemoryUI() {
        if (_binding == null) return
        val ctx = requireContext()
        val vips = VectorNotificationListenerService.getVipContacts(ctx)
        binding.chipGroupVips.removeAllViews()
        if (vips.isEmpty()) {
            binding.tvSheetVipEmpty.visibility = View.VISIBLE
        } else {
            binding.tvSheetVipEmpty.visibility = View.GONE
            for (vip in vips) {
                val chip = Chip(ctx).apply {
                    text = vip.replaceFirstChar { it.uppercase() }
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        VectorNotificationListenerService.removeVipContact(ctx, vip)
                        refreshVipAndMemoryUI()
                    }
                }
                binding.chipGroupVips.addView(chip)
            }
        }

        val memories = geminiBrain.memoryManager.getAllMemories()
        binding.tvSheetMemories.text = if (memories.isEmpty()) {
            "AI Memory: 0 facts saved (Say 'Yaad rakho...')"
        } else {
            "AI Memory (${memories.size}): " + memories.joinToString(" • ")
        }
    }

    private fun observeDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            VectorSessionState.logs.collect { list ->
                if (_binding != null) {
                    binding.tvSheetDiagnostics.text = list.takeLast(14).joinToString("\n")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onSettingsChanged?.invoke()
    }
}
