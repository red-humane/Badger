package com.humnspace.integral.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.ArrayDeque
import java.util.Locale

/** One-shot on-device translation of accessible text from the current foreground window. */
object ScreenTranslator {
    fun translate(root: AccessibilityNodeInfo?, onResult: (String, String) -> Unit) {
        val sourceText = collectText(root)
        if (sourceText.isBlank()) {
            onResult("No readable text was found on this screen.", "")
            return
        }
        val target = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH
        val identifier = LanguageIdentification.getClient()
        identifier.identifyLanguage(sourceText.take(4000)).addOnSuccessListener { source ->
            identifier.close()
            val sourceLanguage = TranslateLanguage.fromLanguageTag(source)
            if (sourceLanguage == null || source == "und") {
                onResult("Badger couldn’t identify the screen language. Try a screen with more text.", "")
                return@addOnSuccessListener
            }
            if (sourceLanguage == target) {
                onResult("This screen already appears to be in ${Locale.getDefault().displayLanguage}.", "")
                return@addOnSuccessListener
            }
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(sourceLanguage).setTargetLanguage(target).build()
            )
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(sourceText.take(4000))
                        .addOnSuccessListener { translated -> onResult(translated, "${Locale.forLanguageTag(source).displayLanguage} → ${Locale.getDefault().displayLanguage}"); translator.close() }
                        .addOnFailureListener { onResult("Translation failed. Check your connection and try again.", ""); translator.close() }
                }
                .addOnFailureListener { onResult("The translation model couldn’t be downloaded. Connect to the internet and try again.", ""); translator.close() }
        }.addOnFailureListener {
            identifier.close()
            onResult("Badger couldn’t identify the screen language.", "")
        }
    }

    /**
     * Translates arbitrary plain text or tapped word/phrase on-device.
     */
    fun translateText(sourceText: String, onResult: (String, String) -> Unit) {
        val trimmed = sourceText.trim()
        if (trimmed.isBlank()) {
            onResult("No text to translate", "")
            return
        }
        val target = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH
        val identifier = LanguageIdentification.getClient()
        identifier.identifyLanguage(trimmed.take(4000)).addOnSuccessListener { source ->
            identifier.close()
            val sourceLanguage = TranslateLanguage.fromLanguageTag(source)
            if (sourceLanguage == null || source == "und") {
                onResult("Language could not be identified", "")
                return@addOnSuccessListener
            }
            if (sourceLanguage == target) {
                onResult(trimmed, "Already in ${Locale.getDefault().displayLanguage}")
                return@addOnSuccessListener
            }
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(sourceLanguage).setTargetLanguage(target).build()
            )
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(trimmed.take(4000))
                        .addOnSuccessListener { translated ->
                            onResult(translated, "${Locale.forLanguageTag(source).displayLanguage} → ${Locale.getDefault().displayLanguage}")
                            translator.close()
                        }
                        .addOnFailureListener {
                            onResult("Translation failed", "")
                            translator.close()
                        }
                }
                .addOnFailureListener {
                    onResult("Model download failed", "")
                    translator.close()
                }
        }.addOnFailureListener {
            identifier.close()
            onResult("Language identification failed", "")
        }
    }

    private fun collectText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val values = LinkedHashSet<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 600 && values.sumOf(String::length) < 4000) {
            val node = pending.removeLast()
            try {
                if (node.isVisibleToUser && !node.isPassword) {
                    node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
                    node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
                    for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
                }
            } catch (_: RuntimeException) {
                // Nodes can disappear while a foreground app changes its view tree.
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        return values.joinToString("\n").take(4000)
    }
}
