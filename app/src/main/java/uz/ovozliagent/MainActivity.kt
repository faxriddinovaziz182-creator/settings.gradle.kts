
package uz.ovozliagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var commandInput: EditText
    private lateinit var micButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    // Ovoz tanish tili. Rus tili uchun "ru-RU", ingliz tili uchun "en-US".
    private val speechLanguage = "uz-UZ"

    private val prefs by lazy { getSharedPreferences("agent", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        commandInput = findViewById(R.id.commandInput)
        micButton = findViewById(R.id.micButton)

        apiKeyInput.setText(prefs.getString("api_key", ""))

        findViewById<Button>(R.id.saveKeyButton).setOnClickListener {
            prefs.edit().putString("api_key", apiKeyInput.text.toString().trim()).apply()
            Toast.makeText(this, "Saqlandi", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.sendButton).setOnClickListener { submitCommand(commandInput.text.toString()) }
        findViewById<Button>(R.id.stopButton).setOnClickListener { PhoneControlService.instance?.stopCommand() }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCommand(commandInput.text.toString()); true
            } else false
        }
        micButton.setOnClickListener { toggleListening() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        renderLogs()
        PhoneControlService.listener = { runOnUiThread { renderLogs() } }
    }

    override fun onPause() {
        PhoneControlService.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val on = PhoneControlService.instance != null
        statusText.text = if (on) "Holat: boshqaruv xizmati yoqilgan ✔"
        else "Holat: boshqaruv xizmati o'chiq ✖ — pastdagi 1-tugma orqali yoqing"
    }

    private fun renderLogs() {
        val text = synchronized(PhoneControlService.logs) { PhoneControlService.logs.joinToString("\n") }
        logText.text = text
    }

    // ------------------------------------------------------------ Ovoz

    private fun toggleListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Ovoz tanish xizmati yo'q (Google ilovasini yangilang)", Toast.LENGTH_LONG).show()
            return
        }
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true; micButton.text = "🎧  Eshitmoqdaman..."
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onError(error: Int) {
                    listening = false; micButton.text = "🎤  Gapirish"
                    Toast.makeText(this@MainActivity, "Ovoz xatosi ($error). Qayta urining.", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    listening = false; micButton.text = "🎤  Gapirish"
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        commandInput.setText(text)
                        submitCommand(text)
                    }
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(i)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleListening()
    }

    // ------------------------------------------------------------ Buyruq

    private fun submitCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val key = prefs.getString("api_key", "").orEmpty().ifBlank { apiKeyInput.text.toString().trim() }
        if (key.isBlank()) {
            Toast.makeText(this, "Avval Claude API kalitini kiriting", Toast.LENGTH_LONG).show()
            return
        }
        val svc = PhoneControlService.instance
        if (svc == null) {
            Toast.makeText(this, "Avval boshqaruv xizmatini yoqing", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        svc.runCommand(cmd, key)
    }
}
