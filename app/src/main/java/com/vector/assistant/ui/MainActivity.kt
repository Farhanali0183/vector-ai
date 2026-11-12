package com.vector.assistant.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.vector.assistant.R
import com.vector.assistant.ai.GeminiBrainClient
import com.vector.assistant.databinding.ActivityMainBinding
import com.vector.assistant.model.VectorSessionState
import com.vector.assistant.model.VectorSettings
import com.vector.assistant.model.VectorState
import com.vector.assistant.service.VectorVoiceService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DISMISS_KEYGUARD = "extra_dismiss_keyguard"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: VectorSettings
    private lateinit var geminiBrain: GeminiBrainClient
    private val chatAdapter = ChatAdapter()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            refreshSetupBannerAndHeader()
            if (micGranted && !settings.userExplicitlyPaused && !VectorVoiceService.isServiceRunning) {
                startVectorService()
            } else if (!micGranted) {
                Toast.makeText(this, "Microphone permission is required for Vector voice wake.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockscreenWakeFlags()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = VectorSettings(this)
        geminiBrain = GeminiBrainClient(this)

        setupRecyclerView()
        setupClickListeners()
        observeSessionState()
        checkAndRequestPermissions()
        handleLockscreenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLockscreenIntent(intent)
    }

    private fun enableLockscreenWakeFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun handleLockscreenIntent(incoming: Intent?) {
        if (incoming?.getBooleanExtra(EXTRA_DISMISS_KEYGUARD, false) == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                km?.requestDismissKeyguard(this, null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        VectorVoiceService.isMainActivityVisible = true
        refreshSetupBannerAndHeader()

        // Automatically keep Vector hands-free armed when Mic is granted and user didn't pause
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMic && !settings.userExplicitlyPaused && !VectorVoiceService.isServiceRunning) {
            startVectorService()
        }
    }

    override fun onPause() {
        VectorVoiceService.isMainActivityVisible = false
        super.onPause()
    }

    private fun setupRecyclerView() {
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            itemAnimator = null
        }
    }

    private fun setupClickListeners() {
        // Open Control Center BottomSheet
        binding.btnOpenSettings.setOnClickListener { openSettingsSheet() }
        binding.bannerSetup.setOnClickListener { openSettingsSheet() }
        binding.btnScreenOffBadge.setOnClickListener { openSettingsSheet() }

        // Clear conversation bubbles
        binding.btnClearChat.setOnClickListener {
            VectorSessionState.clearMessages()
            geminiBrain.clearConversationHistory()
            Toast.makeText(this, "Conversation cleared", Toast.LENGTH_SHORT).show()
        }

        // Toggle always-on service Power On / Pause
        binding.btnToggleService.setOnClickListener {
            if (VectorVoiceService.isServiceRunning) {
                stopVectorService()
            } else {
                startVectorService()
            }
        }

        // Toggle hands-free Continuous Live Chat
        binding.btnToggleContinuousChat.setOnClickListener {
            if (!ensureMicPermission()) return@setOnClickListener
            val intent = Intent(this, VectorVoiceService::class.java).apply {
                action = VectorVoiceService.ACTION_TOGGLE_CONTINUOUS
            }
            ContextCompat.startForegroundService(this, intent)
        }

        // Optional tap-to-talk shortcuts on Visor or Mic button
        val triggerQuickListen = View.OnClickListener {
            if (!ensureMicPermission()) return@OnClickListener
            val intent = Intent(this, VectorVoiceService::class.java).apply {
                action = VectorVoiceService.ACTION_TRIGGER_LISTEN
            }
            ContextCompat.startForegroundService(this, intent)
        }
        binding.vectorVisor.setOnClickListener(triggerQuickListen)
        binding.btnMicQuick.setOnClickListener(triggerQuickListen)

        // Text prompt composer
        binding.btnSendPrompt.setOnClickListener { submitTypedPrompt() }
        binding.etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedPrompt()
                true
            } else {
                false
            }
        }

        // Quick command suggestion chips
        binding.chipPrompt1.setOnClickListener { sendPromptText("Ali ko salam bhejo WhatsApp par") }
        binding.chipPrompt2.setOnClickListener { sendPromptText("Is ka koi sweet sa reply do") }
        binding.chipPrompt3.setOnClickListener { sendPromptText("Screen par kya hai parho") }
        binding.chipPrompt4.setOnClickListener { sendPromptText("YouTube par naya trending gana search karo") }
    }

    private fun observeSessionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    VectorSessionState.state.collect { state ->
                        binding.vectorVisor.setVectorState(state)
                        updateStateUI(state)
                    }
                }
                launch {
                    VectorSessionState.statusSubtitle.collect { subtitle ->
                        binding.tvServiceState.text = subtitle
                    }
                }
                launch {
                    VectorSessionState.audioLevel.collect { level ->
                        binding.vectorVisor.setAudioLevel(level)
                    }
                }
                launch {
                    VectorSessionState.isContinuousChat.collect { continuous ->
                        updateContinuousPill(continuous)
                    }
                }
                launch {
                    VectorSessionState.messages.collect { msgs ->
                        chatAdapter.submitList(msgs) {
                            if (msgs.isNotEmpty()) {
                                binding.rvChat.scrollToPosition(msgs.size - 1)
                            }
                        }
                        binding.emptyStateContainer.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun updateStateUI(state: VectorState) {
        val (badgeText, badgeColor, badgeBg) = when (state) {
            VectorState.IDLE -> Triple("● PAUSED", R.color.text_muted, R.drawable.bg_pill_surface)
            VectorState.LISTENING_WAKE -> Triple("● LISTENING", R.color.neon_green, R.drawable.bg_pill_active_green)
            VectorState.LISTENING_COMMAND -> Triple("● HEARING YOU", R.color.neon_cyan, R.drawable.bg_pill_active_cyan)
            VectorState.THINKING -> Triple("● THINKING", R.color.neon_purple, R.drawable.bg_pill_active_purple)
            VectorState.SPEAKING -> Triple("● SPEAKING", R.color.neon_green, R.drawable.bg_pill_active_green)
        }
        binding.tvStatusBadge.text = badgeText
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, badgeColor))
        binding.tvStatusBadge.setBackgroundResource(badgeBg)

        val running = VectorVoiceService.isServiceRunning || state != VectorState.IDLE
        if (running) {
            binding.btnToggleService.text = "Active"
            binding.btnToggleService.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            binding.btnToggleService.setBackgroundResource(R.drawable.bg_pill_active_cyan)
        } else {
            binding.btnToggleService.text = "Power On"
            binding.btnToggleService.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnToggleService.setBackgroundResource(R.drawable.bg_pill_surface)
        }
    }

    private fun updateContinuousPill(active: Boolean) {
        if (active) {
            binding.btnToggleContinuousChat.text = "Live Chat • ON"
            binding.btnToggleContinuousChat.setTextColor(ContextCompat.getColor(this, R.color.neon_purple))
            binding.btnToggleContinuousChat.setBackgroundResource(R.drawable.bg_pill_active_purple)
        } else {
            binding.btnToggleContinuousChat.text = "Live Chat"
            binding.btnToggleContinuousChat.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleContinuousChat.setBackgroundResource(R.drawable.bg_pill_surface)
        }
    }

    private fun refreshSetupBannerAndHeader() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val geminiOk = geminiBrain.isConnected()
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(packageName) == true

        binding.tvHeaderSubtitle.text = if (geminiOk) {
            "${geminiBrain.getWorkingModel()} • ${if (batteryUnrestricted) "Screen-Off Ready" else "Tap Screen-Off"}"
        } else {
            "Connect Gemini API Key in Settings"
        }

        if (batteryUnrestricted) {
            binding.btnScreenOffBadge.text = "Screen-Off ✓"
            binding.btnScreenOffBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.btnScreenOffBadge.setBackgroundResource(R.drawable.bg_pill_active_green)
        } else {
            binding.btnScreenOffBadge.text = "Screen-Off"
            binding.btnScreenOffBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_amber))
            binding.btnScreenOffBadge.setBackgroundResource(R.drawable.bg_pill_surface)
        }

        binding.bannerSetup.visibility = if (!hasMic || !geminiOk) View.VISIBLE else View.GONE
    }

    private fun openSettingsSheet() {
        val existing = supportFragmentManager.findFragmentByTag("VectorSettingsSheet")
        if (existing != null) return
        SettingsBottomSheet().apply {
            onRequestRuntimePermissions = { checkAndRequestPermissions() }
            onSettingsChanged = {
                refreshSetupBannerAndHeader()
                if (VectorVoiceService.isServiceRunning) {
                    startService(Intent(this@MainActivity, VectorVoiceService::class.java).apply {
                        action = VectorVoiceService.ACTION_REFRESH_SETTINGS
                    })
                } else {
                    val hasMic = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMic && !settings.userExplicitlyPaused) {
                        startVectorService()
                    }
                }
            }
        }.show(supportFragmentManager, "VectorSettingsSheet")
    }

    private fun submitTypedPrompt() {
        val text = binding.etPrompt.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.etPrompt.setText("")
        sendPromptText(text)
    }

    private fun sendPromptText(text: String) {
        val intent = Intent(this, VectorVoiceService::class.java).apply {
            action = VectorVoiceService.ACTION_TEXT_COMMAND
            putExtra(VectorVoiceService.EXTRA_TEXT_COMMAND, text)
        }
        startService(intent)
    }

    private fun ensureMicPermission(): Boolean {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            checkAndRequestPermissions()
            Toast.makeText(this, "Please allow Microphone permission first", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun startVectorService() {
        if (!ensureMicPermission()) return
        settings.userExplicitlyPaused = false
        val intent = Intent(this, VectorVoiceService::class.java).apply {
            action = VectorVoiceService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVectorService() {
        settings.userExplicitlyPaused = true
        val intent = Intent(this, VectorVoiceService::class.java).apply {
            action = VectorVoiceService.ACTION_STOP
        }
        startService(intent)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }
}
