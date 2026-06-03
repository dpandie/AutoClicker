package com.example.autoclicker

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * OCR 文字识别与匹配引擎
 * 负责截屏文字识别、目标文字匹配、匹配区域中心坐标计算
 */
object OcrClickEngine {

    private const val TAG = "OcrClickEngine"

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    // 诊断：最近一次识别的 block 数
    @Volatile
    var lastBlockCount: Int = 0
        private set

    // 诊断：最近一次 OCR 错误
    @Volatile
    var lastError: String = ""
        private set

    /** 匹配结果 */
    data class MatchResult(
        val matched: Boolean,
        val targetText: String,
        val centerX: Int = 0,
        val centerY: Int = 0,
        val boundingRect: Rect = Rect(),
        val confidence: Float = 0f,
        val allText: String = ""
    )

    /** OCR 回调接口（fun interface 支持 SAM 转换） */
    fun interface OcrCallback {
        fun onResult(result: MatchResult)
    }

    /**
     * 对 Bitmap 执行 OCR 识别，查找目标文字
     */
    fun recognizeAndMatch(
        bitmap: Bitmap,
        targetText: String,
        exactMatch: Boolean,
        callback: OcrCallback
    ) {
        if (targetText.isBlank()) {
            callback.onResult(MatchResult(matched = false, targetText = targetText))
            return
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                lastBlockCount = visionText.textBlocks.size
                val blockCount = visionText.textBlocks.size
                Log.d(TAG, "OCR成功: blocks=$blockCount, textLen=${visionText.text.length}")

                val result = findMatchInText(visionText.text, visionText, targetText, exactMatch)
                callback.onResult(result)
            }
            .addOnFailureListener { e ->
                lastError = e.message ?: "unknown"
                lastBlockCount = 0
                Log.e(TAG, "OCR失败: ${e.message}", e)
                callback.onResult(MatchResult(matched = false, targetText = targetText))
            }
    }

    /**
     * 在识别结果中查找匹配文字并计算中心坐标
     */
    private fun findMatchInText(
        fullText: String,
        visionText: com.google.mlkit.vision.text.Text,
        targetText: String,
        exactMatch: Boolean
    ): MatchResult {
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text
                val isMatch = if (exactMatch) {
                    lineText.trim() == targetText.trim()
                } else {
                    lineText.contains(targetText)
                }

                if (isMatch) {
                    val boundingBox = line.boundingBox ?: continue
                    val centerX = boundingBox.centerX()
                    val centerY = boundingBox.centerY()
                    return MatchResult(
                        matched = true,
                        targetText = targetText,
                        centerX = centerX,
                        centerY = centerY,
                        boundingRect = boundingBox,
                        confidence = line.confidence ?: 1.0f,
                        allText = fullText
                    )
                }

                for (element in line.elements) {
                    val elementText = element.text
                    val isElementMatch = if (exactMatch) {
                        elementText.trim() == targetText.trim()
                    } else {
                        elementText.contains(targetText)
                    }

                    if (isElementMatch) {
                        val boundingBox = element.boundingBox ?: continue
                        val centerX = boundingBox.centerX()
                        val centerY = boundingBox.centerY()
                        return MatchResult(
                            matched = true,
                            targetText = targetText,
                            centerX = centerX,
                            centerY = centerY,
                            boundingRect = boundingBox,
                            confidence = element.confidence ?: 1.0f,
                            allText = fullText
                        )
                    }
                }
            }
        }

        return MatchResult(matched = false, targetText = targetText, allText = fullText)
    }

    /**
     * 查找所有匹配的位置（用于多次匹配场景）
     */
    fun findAllMatches(
        bitmap: Bitmap,
        targetText: String,
        exactMatch: Boolean,
        callback: (List<MatchResult>) -> Unit
    ) {
        if (targetText.isBlank()) {
            callback(emptyList())
            return
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                lastBlockCount = visionText.textBlocks.size
                val results = mutableListOf<MatchResult>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val elementText = element.text
                            val isMatch = if (exactMatch) {
                                elementText.trim() == targetText.trim()
                            } else {
                                elementText.contains(targetText)
                            }
                            if (isMatch) {
                                val boundingBox = element.boundingBox ?: continue
                                results.add(MatchResult(
                                    matched = true,
                                    targetText = targetText,
                                    centerX = boundingBox.centerX(),
                                    centerY = boundingBox.centerY(),
                                    boundingRect = boundingBox,
                                    confidence = element.confidence ?: 1.0f,
                                    allText = visionText.text
                                ))
                            }
                        }
                        val lineText = line.text
                        val isLineMatch = if (exactMatch) {
                            lineText.trim() == targetText.trim()
                        } else {
                            lineText.contains(targetText) && line.elements.size <= 1
                        }
                        if (isLineMatch && results.none { it.boundingRect == line.boundingBox }) {
                            val boundingBox = line.boundingBox ?: continue
                            results.add(MatchResult(
                                matched = true,
                                targetText = targetText,
                                centerX = boundingBox.centerX(),
                                centerY = boundingBox.centerY(),
                                boundingRect = boundingBox,
                                confidence = line.confidence ?: 1.0f,
                                allText = visionText.text
                            ))
                        }
                    }
                }
                callback(results)
            }
            .addOnFailureListener {
                lastBlockCount = 0
                callback(emptyList())
            }
    }
}
