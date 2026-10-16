package com.example.screencapture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TranslationManager(
    context: Context,
    private val sourceLang: String = TranslateLanguage.ENGLISH,
    private val targetLang: String = TranslateLanguage.VIETNAMESE
) {
    companion object {
        private const val TAG = "TranslationManager"
        private const val MIN_BITMAP_DIMENSION = 32
        private const val UPSCALE_HEIGHT_THRESHOLD = 180
        private const val GTX_TIMEOUT_MS = 1500
        const val EMPTY_TEXT_MESSAGE = "Không tìm thấy văn bản trong vùng chọn"
    }

    val glossaryManager = GlossaryManager(context.applicationContext)
    private val translationCache = LruCache<String, String>(500)

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(sourceLang)
        .setTargetLanguage(targetLang)
        .build()

    private val mlKitTranslator: Translator = Translation.getClient(translatorOptions)

    private var isReleased = false

    suspend fun prepareLanguageModel(): Boolean = withContext(Dispatchers.IO) {
        if (isReleased) return@withContext false
        return@withContext try {
            val conditions = DownloadConditions.Builder().build()
            mlKitTranslator.downloadModelIfNeeded(conditions).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tải gói ngôn ngữ Offline", e)
            false
        }
    }

    suspend fun recognizeAndTranslate(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        if (isReleased || bitmap.isRecycled) return@withContext ""

        if (bitmap.width < MIN_BITMAP_DIMENSION || bitmap.height < MIN_BITMAP_DIMENSION) {
            return@withContext ""
        }

        val ocrBitmap = if (bitmap.height < UPSCALE_HEIGHT_THRESHOLD) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
        } else {
            bitmap
        }

        try {
            val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
            val visionText = recognizer.process(inputImage).await()

            if (visionText.textBlocks.isEmpty()) {
                return@withContext EMPTY_TEXT_MESSAGE
            }

            val glossaryHash = glossaryManager.getConfigHash()
            val translatedParagraphs = mutableListOf<String>()

            for (block in visionText.textBlocks) {
                val blockText = block.lines
                    .joinToString(separator = " ") { it.text.trim() }
                    .trim()

                if (blockText.isEmpty()) continue

                val cacheKey = "${glossaryHash}_$blockText"
                val cachedTranslation = translationCache.get(cacheKey)
                if (cachedTranslation != null) {
                    translatedParagraphs.add(cachedTranslation)
                    continue
                }

                val maskedResult = glossaryManager.preprocessAndMask(blockText)
                val rawTranslated = translateBlock(maskedResult.maskedText)
                val finalBlockTranslation = glossaryManager
                    .unmask(rawTranslated, maskedResult.maskMap)
                    .trim()

                if (finalBlockTranslation.isNotEmpty()) {
                    translationCache.put(cacheKey, finalBlockTranslation)
                    translatedParagraphs.add(finalBlockTranslation)
                }
            }

            if (translatedParagraphs.isEmpty()) {
                return@withContext EMPTY_TEXT_MESSAGE
            }

            return@withContext translatedParagraphs.joinToString(separator = "\n\n")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi OCR hoặc Dịch", e)
            return@withContext "Đã xảy ra lỗi khi dịch: ${e.localizedMessage}"
        } finally {
            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
        }
    }

    suspend fun translateBlock(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        // LỚP 1: Cổng GTX (0đ, Không API Key, Timeout 1500ms)
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val urlString =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedText"

            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = GTX_TIMEOUT_MS
                readTimeout = GTX_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                    val rootArray = JSONArray(responseBody)
                    val sentencesArray = rootArray.getJSONArray(0)
                    val builder = StringBuilder()

                    for (i in 0 until sentencesArray.length()) {
                        val sentenceItem = sentencesArray.optJSONArray(i)
                        if (sentenceItem != null && !sentenceItem.isNull(0)) {
                            builder.append(sentenceItem.getString(0))
                        }
                    }

                    val gtxResult = builder.toString().trim()
                    if (gtxResult.isNotEmpty()) {
                        return@withContext gtxResult
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            // BẮT BUỘC ném lại CancellationException để dừng ngay khi người dùng đóng bảng dịch
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cổng GTX quá hạn hoặc mất mạng, tự động chuyển sang ML Kit Offline.")
        }

        // LỚP 2: Auto-Fallback tức thì về ML Kit Offline
        return@withContext try {
            mlKitTranslator.translate(text).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit Offline fallback lỗi", e)
            text
        }
    }

    fun clearCache() {
        translationCache.evictAll()
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
        try {
            mlKitTranslator.close()
        } catch (_: Exception) {
        }
    }
}
