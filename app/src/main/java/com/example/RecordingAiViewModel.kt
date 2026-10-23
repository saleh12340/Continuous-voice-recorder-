package com.example

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "RecordingAiViewModel"

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Streaming(val text: String) : AiUiState
    data class Success(val text: String) : AiUiState
    data class Error(val message: String) : AiUiState
}

enum class AiTaskType(val titleAr: String) {
    SUMMARY("تلخيص ذكي شامل"),
    MEETING_MINUTES("محضر رسمي منظم"),
    ACTION_ITEMS("استخراج المهام والقرارات"),
    CUSTOM_QUESTION("سؤال أو تحليل مخصص")
}

class RecordingAiViewModel : ViewModel() {

    // Lazy initialization mandated by Firebase AI Logic skill
    private val model by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    private val _uiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    private val _currentTask = MutableStateFlow(AiTaskType.SUMMARY)
    val currentTask: StateFlow<AiTaskType> = _currentTask.asStateFlow()

    fun setTask(task: AiTaskType) {
        _currentTask.value = task
    }

    fun clearState() {
        _uiState.value = AiUiState.Idle
    }

    fun setInitialSummary(savedSummary: String?) {
        if (!savedSummary.isNullOrBlank()) {
            _uiState.value = AiUiState.Success(savedSummary)
        } else {
            _uiState.value = AiUiState.Idle
        }
    }

    fun processRecordingAi(
        recording: RecordingModel,
        userNotes: String = "",
        customPrompt: String = "",
        taskType: AiTaskType = _currentTask.value,
        onSuccess: ((String) -> Unit)? = null
    ) {
        _currentTask.value = taskType
        val prompt = buildPrompt(recording, userNotes, customPrompt, taskType)

        viewModelScope.launch {
            _uiState.value = AiUiState.Loading
            val accumulated = StringBuilder()

            try {
                // Realistic 60-second timeout mandated by skill
                withTimeout(60_000L) {
                    model.generateContentStream(prompt).collect { chunk ->
                        val textChunk = chunk.text.orEmpty()
                        accumulated.append(textChunk)
                        _uiState.value = AiUiState.Streaming(accumulated.toString())
                    }
                }

                val finalResult = accumulated.toString().trim()
                if (finalResult.isNotEmpty()) {
                    _uiState.value = AiUiState.Success(finalResult)
                    onSuccess?.invoke(finalResult)
                } else {
                    _uiState.value = AiUiState.Error("لم يُرجع النموذج أي محتوى. يرجى المحاولة مجدداً.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini generation timed out", e)
                _uiState.value = AiUiState.Error("انتهت مهلة الاتصال بنموذج Gemini (60 ثانية). يرجى التحقق من اتصال الإنترنت والمحاولة ثانية.")
            } catch (e: CancellationException) {
                // Rethrow CancellationException to preserve cooperative coroutine cancellation
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini generation failed", e)
                val errorMsg = e.localizedMessage ?: "فشلت عملية الذكاء الاصطناعي."
                _uiState.value = AiUiState.Error("خطأ Gemini: $errorMsg")
            }
        }
    }

    private fun buildPrompt(
        recording: RecordingModel,
        userNotes: String,
        customPrompt: String,
        taskType: AiTaskType
    ): String {
        val metaInfo = """
            معلومات ملف التسجيل الصوتي:
            - اسم الملف: ${recording.fileName}
            - تاريخ التسجيل: ${recording.date}
            - وقت البدء: ${recording.startTime}
            - مدة التسجيل: ${recording.duration}
            - حجم الملف: ${recording.fileSize}
            ${if (userNotes.isNotBlank()) "- ملاحظات المستخدم وسياق التسجيل: $userNotes" else ""}
        """.trimIndent()

        return when (taskType) {
            AiTaskType.SUMMARY -> """
                أنت مساعد ذكي متخصص في تحليل وتلخيص التسجيلات الصوتية باللغة العربية.
                $metaInfo
                
                المطلوب: قم بتقديم تلخيص احترافي وشامل للتسجيل استناداً إلى البيانات وسياق الملاحظات المرفقة:
                1. 📌 ملخص تنفيذي موجز (Executive Summary)
                2. 🔑 أهم الأفكار والنقاط الأساسية
                3. 🏷️ تصنيف التسجيل والوسوم المقترحة (مثال: #اجتماع, #دراسة, #ملاحظة_شخصية, #مكالمة)
                4. 💡 التوصيات والخطوات القادمة
                
                اجعل التنسيق واضحاً وأنيقاً باستخدام النقاط والعناوين الفرعية.
            """.trimIndent()

            AiTaskType.MEETING_MINUTES -> """
                أنت خبير سكرتارية وتوثيق إداري.
                $metaInfo
                
                المطلوب: قم بصياغة محضر اجتماع رسمي (Meeting Minutes) احترافي ومنظم للتسجيل الصوتي يحتوي على:
                - عنوان الجلسة وتاريخها ومدتها
                - جدول الأعمال والمحاور المطروحة
                - ملخص المداولات والنقاشات
                - القرارات المعتمدة والمسؤوليات الموزعة
            """.trimIndent()

            AiTaskType.ACTION_ITEMS -> """
                أنت محلل تنفيذي للمهام.
                $metaInfo
                
                المطلوب: استخرج قائمة دقيقة بجميع المهام العملية والقرارات (Action Items):
                - قائمة المهام المحددة المطلوب إنجازها
                - المواعيد المقترحة أو الأولويات (عالية / متوسطة / منخفضة)
                - نقاط للمتابعة في الجلسات القادمة
            """.trimIndent()

            AiTaskType.CUSTOM_QUESTION -> """
                أنت مساعد ذكي مخصص للتسجيلات الصوتية.
                $metaInfo
                
                سؤال المستخدم أو طلبه المخصص:
                $customPrompt
                
                أجب بشكل دقيق ومباشر ومفيد باللغة العربية.
            """.trimIndent()
        }
    }
}
