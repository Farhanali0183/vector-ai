package com.vector.assistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vector.assistant.R
import com.vector.assistant.audio.VoiceprintMatcher
import com.vector.assistant.databinding.ActivityMainBinding
import com.vector.assistant.service.VectorVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceprintMatcher: VoiceprintMatcher
    private val scope = CoroutineScope(Dispatchers.Main)

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(VectorVoiceService.EXTRA_LOG_MSG) ?: return
            appendLog(msg)
            if (msg.contains("Memory", ignoreCase = true)) {
                updateMemoriesUI()
            }
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            updatePermissionStatusUI()
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                appendLog("All runtime permissions granted!")
            } else {
                appendLog("Some permissions were denied. Some functions might not work.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceprintMatcher = VoiceprintMatcher(this)

        setupUI()
        checkAndRequestPermissions()
        updateVoiceProfileUI()
        updateServiceStateUI()
        updateVipListUI()
        updateMemoriesUI()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusUI()
        updateServiceStateUI()
        updateVipListUI()
        updateMemoriesUI()
        val filter = IntentFilter(VectorVoiceService.BROADCAST_LOG)
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setupUI() {
        // Master Toggle Button (Start / Stop Vector)
        binding.btnToggleService.setOnClickListener {
            if (VectorVoiceService.isServiceRunning) {
                stopVectorService()
            } else {
                startVectorService()
            }
        }

        // Tap on "Suno Vector" glowing orb to talk directly
        binding.pulseContainer.setOnClickListener {
            val intent = Intent(this, VectorVoiceService::class.java).apply {
                action = VectorVoiceService.ACTION_TRIGGER_LISTEN
            }
            ContextCompat.startForegroundService(this, intent)
            updateServiceStateUI(true)
            appendLog("Tapped 'Suno Vector'! Listening for command...")
        }

        // Voice Training Button
        binding.btnTrainVoice.setOnClickListener {
            recordVoiceSampleForTraining()
        }

        // Gemini Pro Key Setup
        val geminiBrain = com.vector.assistant.ai.GeminiBrainClient(this)
        updateGeminiStatusUI(geminiBrain)

        binding.btnSaveGeminiKey.setOnClickListener {
            val key = binding.etGeminiApiKey.text?.toString()?.trim() ?: ""
            if (key.isNotEmpty()) {
                geminiBrain.saveApiKey(key)
                Toast.makeText(this, "Gemini Pro Connected Successfully!", Toast.LENGTH_SHORT).show()
                updateGeminiStatusUI(geminiBrain)
                binding.etGeminiApiKey.setText("")
            } else {
                Toast.makeText(this, "Please enter a valid API key", Toast.LENGTH_SHORT).show()
            }
        }

        // Permission Buttons
        binding.btnPermBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        binding.btnPermAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnPermNotification.setOnClickListener {
            openNotificationListenerSettings()
        }

        binding.btnPermPhone.setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    private fun updateGeminiStatusUI(gemini: com.vector.assistant.ai.GeminiBrainClient) {
        if (gemini.isConnected()) {
            binding.tvGeminiStatus.text = "Status: ACTIVE & CONNECTED (Gemini Pro Intelligence Enabled!)"
            binding.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.btnSaveGeminiKey.text = "UPDATE GEMINI PRO KEY"
        } else {
            binding.tvGeminiStatus.text = "Status: Enter your Gemini API key to activate super intelligence!"
            binding.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnSaveGeminiKey.text = "CONNECT GEMINI PRO"
        }
    }

    private fun startVectorService() {
        val intent = Intent(this, VectorVoiceService::class.java).apply {
            action = VectorVoiceService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        updateServiceStateUI(true)
        appendLog("Vector Service starting...")
    }

    private fun stopVectorService() {
        val intent = Intent(this, VectorVoiceService::class.java).apply {
            action = VectorVoiceService.ACTION_STOP
        }
        startService(intent)
        updateServiceStateUI(false)
        appendLog("Vector Service stopping...")
    }

    private fun updateServiceStateUI(isRunning: Boolean = VectorVoiceService.isServiceRunning) {
        if (isRunning) {
            binding.tvStatusBadge.text = "ACTIVE (ONLINE)"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.btnToggleService.text = "STOP VECTOR ASSISTANT"
            binding.btnToggleService.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.neon_red)
            binding.tvServiceState.text = "Listening for 'Hey Vector' / 'Suno Vector'..."
        } else {
            binding.tvStatusBadge.text = "PAUSED"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.btnToggleService.text = "ACTIVATE VECTOR (START LISTENING)"
            binding.btnToggleService.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.neon_cyan)
            binding.tvServiceState.text = "Vector is currently sleeping. Tap below to wake up."
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordVoiceSampleForTraining() {
        val enrolled = voiceprintMatcher.getEnrolledCount()
        if (enrolled >= 3) {
            voiceprintMatcher.resetProfile()
            Toast.makeText(this, "Profile reset. Let's record fresh samples!", Toast.LENGTH_SHORT).show()
            updateVoiceProfileUI()
            return
        }

        binding.btnTrainVoice.isEnabled = false
        binding.btnTrainVoice.text = "Recording... Please say: 'Hey Vector, Suno Vector' now!"
        binding.trainingProgressBar.visibility = View.VISIBLE

        scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channel, format)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channel,
                    format,
                    bufferSize
                )
                recorder.startRecording()

                // Capture ~2.5 seconds of voice
                val totalSamples = sampleRate * 5 / 2
                val pcmBuffer = ShortArray(totalSamples)
                var read = 0
                while (read < totalSamples) {
                    val count = recorder.read(pcmBuffer, read, totalSamples - read)
                    if (count > 0) read += count else break
                }

                recorder.stop()
                val newCount = voiceprintMatcher.enrollSample(pcmBuffer)

                withContext(Dispatchers.Main) {
                    binding.trainingProgressBar.visibility = View.GONE
                    binding.btnTrainVoice.isEnabled = true
                    updateVoiceProfileUI()
                    appendLog("Voice Sample #$newCount recorded and analyzed successfully!")
                    Toast.makeText(this@MainActivity, "Sample #$newCount saved!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.trainingProgressBar.visibility = View.GONE
                    binding.btnTrainVoice.isEnabled = true
                    appendLog("Error recording voice sample: ${e.message}")
                }
            } finally {
                recorder?.release()
            }
        }
    }

    private fun updateVoiceProfileUI() {
        val count = voiceprintMatcher.getEnrolledCount()
        binding.trainingProgressBar.progress = count

        if (count >= 3) {
            binding.tvVoiceProfileStatus.text =
                "Profile: REGISTERED (Balanced threshold 0.50 - No more voice mismatch!)"
            binding.tvVoiceProfileStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.btnTrainVoice.text = "Reset & Re-Train Voice Profile"
        } else {
            binding.tvVoiceProfileStatus.text =
                "Profile: Open Mode (Instant response to 'Hey Vector' / 'Suno Vector')."
            binding.tvVoiceProfileStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            binding.btnTrainVoice.text = "Record Voice Sample ($count / 3)"
        }
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

        // Android 13 POST_NOTIFICATIONS
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

    private fun openNotificationListenerSettings() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        startActivity(intent)
        Toast.makeText(this, "Find 'Vector Assistant' and allow Notification Access for VIP alerts", Toast.LENGTH_LONG).show()
    }

    private fun updateVipListUI() {
        val vips = com.vector.assistant.service.VectorNotificationListenerService.getVipContacts(this)
        if (vips.isEmpty()) {
            binding.tvVipList.text = "Active VIPs: None (Bol kar add karein: 'Set VIP contact Ali')"
        } else {
            binding.tvVipList.text = "Active VIPs: " + vips.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    private fun updateMemoriesUI() {
        val memoryManager = com.vector.assistant.ai.VectorMemoryManager(this)
        val memories = memoryManager.getAllMemories()
        if (memories.isEmpty()) {
            binding.tvMemoriesStatus.text = "🧠 AI Memory: 0 facts saved (Bol kar save karein: 'Yaad rakho mera office 9 baje hai')"
        } else {
            binding.tvMemoriesStatus.text = "🧠 AI Memory (${memories.size} facts): " + memories.take(2).joinToString(", ") + if (memories.size > 2) "..." else ""
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                val generalIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(generalIntent)
            }
        } else {
            Toast.makeText(this, "Screen-off battery optimization is already disabled!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Find 'Vector Assistant' and turn it ON to enable phone tasks", Toast.LENGTH_LONG).show()
    }

    private fun updatePermissionStatusUI() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            binding.btnPermBattery.text = "ALLOWED"
            binding.btnPermBattery.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.btnPermBattery.isEnabled = false
        }

        val hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (hasPhone && hasContacts) {
            binding.btnPermPhone.text = "ALLOWED"
            binding.btnPermPhone.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.btnPermPhone.isEnabled = false
        }
    }

    private fun appendLog(msg: String) {
        val currentLogs = binding.tvLogs.text.toString()
        val lines = currentLogs.split("\n").takeLast(8)
        val updated = (lines + listOf("• $msg")).joinToString("\n")
        binding.tvLogs.text = updated
    }
}
