package com.example.offlinevoiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import java.util.Locale

/**
 * تطبيق المساعد الصوتي المحلي دون إنترنت (Offline Voice Assistant) - نسخة Kotlin
 * متوافق مع البناء المباشر في GitHub Actions لإنتاج ملف APK
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private var textToSpeech: TextToSpeech? = null

    // عناصر واجهة المستخدم
    private lateinit var btnMic: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvActionResult: TextView
    private lateinit var cardResult: MaterialCardView
    private lateinit var progressBarListening: ProgressBar

    // الصلاحيات المطلوبة للعمل بشكل محلي وكامل
    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    )

    // مسجل طلب الصلاحيات في وقت التشغيل (Runtime Permissions)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (recordAudioGranted) {
            tvStatus.text = "الصلاحيات جاهزة، اضغط على الميكروفون للتحدث"
        } else {
            Toast.makeText(this, "صلاحية الميكروفون ضرورية للاستماع للأوامر", Toast.LENGTH_LONG).show()
            tvStatus.text = "يرجى منح صلاحية الميكروفون ليعمل التطبيق"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ربط عناصر الواجهة
        btnMic = findViewById(R.id.btnMic)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvActionResult = findViewById(R.id.tvActionResult)
        cardResult = findViewById(R.id.cardResult)
        progressBarListening = findViewById(R.id.progressBarListening)

        // تهيئة محرك النطق الصوتي للرد على المستخدم (TTS)
        textToSpeech = TextToSpeech(this, this)

        // فحص الصلاحيات عند بدء التطبيق
        checkAndRequestPermissions()

        // تهيئة التعرف على الصوت
        initSpeechRecognizer()

        // إعداد زر الاستماع
        btnMic.setOnClickListener {
            if (hasAudioPermission()) {
                startOfflineListening()
            } else {
                checkAndRequestPermissions()
            }
        }

        // أزرار سريعة لتجربة الأوامر
        findViewById<Chip>(R.id.chipCallSample).setOnClickListener {
            handleSpokenCommand("اتصل بأحمد")
        }
        findViewById<Chip>(R.id.chipAppSample).setOnClickListener {
            handleSpokenCommand("افتح واتساب")
        }
        findViewById<Chip>(R.id.chipSearchSample).setOnClickListener {
            handleSpokenCommand("ابحث عن مواقيت الصلاة")
        }
    }

    /**
     * إعداد SpeechRecognizer وإجباره على العمل في وضع Offline دون إنترنت
     */
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvStatus.text = "خدمة التعرف على الصوت غير متوفرة على هذا الجهاز"
            btnMic.isEnabled = false
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // إعداد Intent الاستماع الصوتي مع تفعيل خيار العمل بدون إنترنت
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")

            // الأهم: إجبار محرك التعرف الصوتي على العمل محلياً بدون إنترنت (Offline Mode)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        // الاستماع لأحداث التسجيل الصوتي
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                progressBarListening.visibility = View.VISIBLE
                tvStatus.text = "جارٍ الاستماع... تحدث باللغة العربية الآن"
                btnMic.text = "جارٍ الاستماع..."
            }

            override fun onBeginningOfSpeech() {
                tvStatus.text = "تم التقاط صوتك، جارٍ المعالجة محلياً..."
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                progressBarListening.visibility = View.GONE
                tvStatus.text = "انتهى التحدث، جاري فحص الأمر..."
                btnMic.text = "تحدث الآن"
            }

            override fun onError(error: Int) {
                progressBarListening.visibility = View.GONE
                btnMic.text = "تحدث الآن"

                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "تنبيه: محرك الصوت يطلب اتصالاً. تأكد من تنزيل حزمة اللغة العربية دون إنترنت من إعدادات جوجل."
                    SpeechRecognizer.ERROR_NO_MATCH -> "لم يتم تمييز أي كلام، حاول مجدداً بصوت أوضح."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "انتهى الوقت دون سماع صوت، اضغط للتحدث مرة أخرى."
                    SpeechRecognizer.ERROR_AUDIO -> "خطأ في الميكروفون، يرجى إعادة المحاولة."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "يرجى منح صلاحية الميكروفون أولاً."
                    else -> "حدث خطأ في معالجة الصوت (رمز: $error)"
                }
                tvStatus.text = errorMessage
                Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                progressBarListening.visibility = View.GONE
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    tvTranscript.text = recognizedText
                    handleSpokenCommand(recognizedText)
                } else {
                    tvStatus.text = "لم يتم التقاط أي نص"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!partial.isNullOrEmpty()) {
                    tvTranscript.text = partial[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startOfflineListening() {
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر بدء الميكروفون: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * معالجة وتحليل الأمر الصوتي وتنفيذ المطلوب محلياً
     */
    private fun handleSpokenCommand(rawCommand: String) {
        val normalized = normalizeArabic(rawCommand)
        cardResult.visibility = View.VISIBLE

        // 1. فحص أمر إجراء المكالمات: "اتصل بـ أحمد", "اتصل بأحمد", "كلم سارة", "رن على خالد"
        val callRegex = Regex("^(?:اتصل(?:\\s*(?:بـ|ب|علي|على))?|كلم|رن(?:\\s*(?:علي|على))?)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val callMatch = callRegex.find(normalized)

        if (callMatch != null) {
            val contactNameQuery = callMatch.groupValues[1].trim()
            makePhoneCallToContact(contactNameQuery)
            return
        }

        // 2. فحص أمر فتح التطبيقات المثبتة: "افتح واتساب", "شغل الكاميرا", "افتحلي الآلة الحاسبة"
        val appRegex = Regex("^(?:افتح(?:لي)?|شغل(?:ي)?|فتح|تشغيل)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val appMatch = appRegex.find(normalized)

        if (appMatch != null) {
            val appNameQuery = appMatch.groupValues[1].trim()
            openInstalledApplication(appNameQuery)
            return
        }

        // 3. الأمر العام أو البحث في المتصفح: "ابحث عن [س] ", أو أي استعلام آخر
        val searchRegex = Regex("^(?:ابحث(?:\\s*(?:عن|في))?|دور(?:\\s*(?:علي|على|عن))?|سيرش)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val searchMatch = searchRegex.find(normalized)
        val searchQuery = if (searchMatch != null) searchMatch.groupValues[1].trim() else rawCommand

        performWebSearch(searchQuery)
    }

    /**
     * البحث في جهات الاتصال (ContactsProvider) والاتصال المباشر (Intent.ACTION_CALL)
     */
    private fun makePhoneCallToContact(targetName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            tvActionResult.text = "صلاحية قراءة جهات الاتصال غير ممنوحة"
            speakOut("يرجى منح صلاحية جهات الاتصال أولاً")
            return
        }

        tvStatus.text = "جارٍ البحث عن: $targetName في الأسماء..."
        val phoneNumber = findPhoneNumberByName(targetName)

        if (phoneNumber != null) {
            tvActionResult.text = "تم العثور على الرقم: $phoneNumber\nجارٍ الاتصال المباشر..."
            speakOut("جارٍ الاتصال بـ $targetName")

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                }
                startActivity(callIntent)
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                }
                startActivity(dialIntent)
            }
        } else {
            val notFoundMsg = "لم يتم العثور على اسم \"$targetName\" في سجل الهاتف"
            tvActionResult.text = notFoundMsg
            speakOut("لم أجد هذا الاسم في جهات اتصالك")
            Toast.makeText(this, notFoundMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun findPhoneNumberByName(targetName: String): String? {
        val targetNorm = normalizeArabic(targetName)
        val contentResolver = contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                do {
                    val contactName = cursor.getString(nameIndex) ?: ""
                    val contactNorm = normalizeArabic(contactName)

                    if (contactNorm == targetNorm ||
                        contactNorm.contains(targetNorm) ||
                        targetNorm.contains(contactNorm)
                    ) {
                        return cursor.getString(numberIndex)
                    }
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * فحص التطبيقات المثبتة وفتح التطبيق المطابق باستخدام PackageManager
     */
    private fun openInstalledApplication(targetAppName: String) {
        val cleanTarget = normalizeArabic(targetAppName).let {
            if (it.startsWith("ال") && it.length > 3) it.substring(2) else it
        }

        tvStatus.text = "جارٍ البحث عن تطبيق: $targetAppName..."
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfoList = pm.queryIntentActivities(mainIntent, 0)

        var foundPackage: String? = null
        var foundLabel: String? = null

        for (resolveInfo in resolveInfoList) {
            val appLabel = resolveInfo.loadLabel(pm).toString()
            val labelNorm = normalizeArabic(appLabel).let {
                if (it.startsWith("ال") && it.length > 3) it.substring(2) else it
            }

            if (labelNorm == cleanTarget ||
                labelNorm.contains(cleanTarget) ||
                cleanTarget.contains(labelNorm)
            ) {
                foundPackage = resolveInfo.activityInfo.packageName
                foundLabel = appLabel
                break
            }
        }

        if (foundPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(foundPackage)
            if (launchIntent != null) {
                tvActionResult.text = "تم العثور على التطبيق: $foundLabel\nحزمة: $foundPackage\nجارٍ التشغيل..."
                speakOut("جارٍ فتح $foundLabel")
                startActivity(launchIntent)
            } else {
                tvActionResult.text = "تعذر تشغيل التطبيق (لا يوجد واجهة تشغيل)"
            }
        } else {
            val notFound = "التطبيق \"$targetAppName\" غير مثبت على هذا الهاتف"
            tvActionResult.text = notFound
            speakOut("لم أجد هذا التطبيق مثبت على الهاتف")
            Toast.makeText(this, notFound, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performWebSearch(query: String) {
        tvActionResult.text = "جارٍ تنفيذ البحث عن: \"$query\""
        speakOut("جارٍ البحث عن $query")

        try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
            }
            startActivity(searchIntent)
        } catch (e: Exception) {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            )
            startActivity(browserIntent)
        }
    }

    private fun normalizeArabic(input: String): String {
        return input.trim().lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace("ـ", "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .replace(Regex("[^\\u0600-\\u06FFa-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun speakOut(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_response")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("ar"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.setLanguage(Locale.getDefault())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
