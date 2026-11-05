package uz.ovozliagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Telefonni "ko'z va qo'l" bilan boshqaruvchi xizmat:
 * ekranni matn ko'rinishida o'qiydi, bosadi, yozadi, aylantiradi.
 * Agent ham shu xizmat ichida ishlaydi, shuning uchun ilova yopilsa ham davom etadi.
 */
class PhoneControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneControlService? = null

        /** Ekrandagi jurnal (MainActivity ko'rsatadi) */
        val logs = ArrayList<String>()

        @Volatile
        var listener: ((String) -> Unit)? = null

        @Volatile
        var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("uz", "UZ"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                ttsReady = true
            }
        }
        addLog("Xizmat ulandi ✔")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------------------------------------------------------- Agent

    fun addLog(line: String) {
        synchronized(logs) {
            logs.add(line)
            if (logs.size > 200) logs.removeAt(0)
        }
        listener?.invoke(line)
    }

    fun runCommand(command: String, apiKey: String) {
        if (running) {
            addLog("Avvalgi buyruq hali bajarilmoqda. 'To'xtatish' tugmasini bosing.")
            return
        }
        running = true
        addLog("🗣 $command")
        job = scope.launch {
            try {
                val agent = ClaudeAgent(this@PhoneControlService, apiKey) { addLog(it) }
                val result = agent.run(command)
                addLog("✅ $result")
                speak(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                addLog("⏹ To'xtatildi")
            } catch (e: Exception) {
                addLog("❌ Xato: ${e.message}")
                speak("Xatolik yuz berdi")
            } finally {
                running = false
            }
        }
    }

    fun stopCommand() {
        job?.cancel()
        running = false
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent")
    }

    // ---------------------------------------------------------------- Ekranni o'qish

    fun snapshot(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "(ekranni o'qib bo'lmadi)"
        val sb = StringBuilder("Hozirgi ilova: ${root.packageName}\n")
        walk(root, sb)
        val s = sb.toString()
        return if (s.length > 5000) s.take(5000) + "\n...(qisqartirildi)" else s
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (!n.isVisibleToUser) return
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val useful = text.isNotEmpty() || desc.isNotEmpty() || n.isEditable || n.isClickable || n.isScrollable
        if (useful) {
            val idx = nodes.size
            nodes.add(n)
            val flags = ArrayList<String>()
            if (n.isClickable) flags.add("bosiladi")
            if (n.isEditable) flags.add("matn kiritish")
            if (n.isScrollable) flags.add("aylanadi")
            if (n.isChecked) flags.add("belgilangan")
            sb.append("[").append(idx).append("] ")
            if (text.isNotEmpty()) sb.append("\"").append(text.take(80)).append("\" ")
            if (desc.isNotEmpty() && desc != text) sb.append("(").append(desc.take(60)).append(") ")
            if (flags.isNotEmpty()) sb.append("{").append(flags.joinToString(", ")).append("}")
            sb.append("\n")
        }
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { walk(it, sb) }
        }
    }

    // ---------------------------------------------------------------- Harakatlar

    fun click(index: Int): String {
        val node = nodes.getOrNull(index) ?: return "Xato: [$index] raqamli element yo'q"
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "Bosildi [$index]"
            }
            cur = cur.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (tap(r.exactCenterX(), r.exactCenterY())) "Koordinata bo'yicha bosildi [$index]"
        else "Xato: bosib bo'lmadi [$index]"
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun typeText(text: String, index: Int): String {
        val node = when {
            index >= 0 -> nodes.getOrNull(index)
            else -> findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: nodes.firstOrNull { it.isEditable }
        } ?: return "Xato: matn kiritiladigan maydon topilmadi"
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "Yozildi: $text" else "Xato: matn yozib bo'lmadi"
    }

    /** Klaviaturadagi Enter / Qidirish tugmasi */
    fun submit(): String {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: nodes.firstOrNull { it.isEditable }
            ?: return "Xato: faol maydon yo'q"
        if (Build.VERSION.SDK_INT >= 30) {
            val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return "Enter bosildi"
        }
        return "Enter ishlamadi, ekrandagi 'Qidirish' tugmasini click bilan bosing"
    }

    fun scroll(direction: String): String {
        val node = nodes.firstOrNull { it.isScrollable } ?: return "Xato: aylantiriladigan joy yo'q"
        val action = if (direction == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        return if (node.performAction(action)) "Aylantirildi ($direction)" else "Xato: aylantirib bo'lmadi"
    }

    fun press(key: String): String {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Xato: noma'lum tugma"
        }
        return if (performGlobalAction(action)) "Bosildi: $key" else "Xato: $key ishlamadi"
    }

    fun openUrl(url: String): String {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            "Ochildi: $url"
        } catch (e: Exception) {
            "Xato: ${e.message}"
        }
    }

    fun openApp(name: String): String {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = name.lowercase().trim()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) }
        if (match == null) {
            val labels = apps.map { it.loadLabel(pm).toString() }.sorted().take(60)
            return "Xato: '$name' ilovasi topilmadi. O'rnatilgan ilovalar: ${labels.joinToString(", ")}"
        }
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Xato: ilovani ochib bo'lmadi"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        return "Ochildi: ${match.loadLabel(pm)}"
    }
}
