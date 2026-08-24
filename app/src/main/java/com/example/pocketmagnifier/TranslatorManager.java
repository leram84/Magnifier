package com.example.pocketmagnifier;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/**
 * Manages the ML Kit on-device translator lifecycle.
 * Call {@link #prepare} before translating; it will download the model if needed.
 * Always call {@link #close} when translation is no longer needed to free resources.
 */
public class TranslatorManager {

    /** Callback for {@link #translate} results. */
    public interface TranslateCallback {
        void onResult(String translated);
        void onError(String message);
    }

    /** Callback for {@link #prepare} results. */
    public interface PrepareCallback {
        void onReady();
        void onDownloading();
        void onError(String message);
    }

    private Translator translator;
    private String currentTarget = TranslateLanguage.ENGLISH;
    private boolean ready = false;

    /** Returns the currently selected target language tag. */
    public String getTargetLanguage() {
        return currentTarget;
    }

    /**
     * Prepares the translator for the given target language.
     * Downloads the language model if it is not already available.
     */
    public void prepare(String targetLanguage, PrepareCallback callback) {
        if (!targetLanguage.equals(currentTarget) || translator == null) {
            close();
            currentTarget = targetLanguage;
        }

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(currentTarget)
                .build();

        translator = Translation.getClient(options);
        ready = false;

        callback.onDownloading();

        DownloadConditions conditions = new DownloadConditions.Builder()
                .requireWifi()
                .build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    ready = true;
                    callback.onReady();
                })
                .addOnFailureListener(e -> {
                    ready = false;
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
                });
    }

    /**
     * Translates text from a specific source language to the current target language.
     * Downloads models if needed. Creates a short-lived translator per call.
     */
    public void translateFrom(String sourceLanguage, String text, TranslateCallback callback) {
        if (text == null || text.isEmpty()) {
            callback.onResult("");
            return;
        }
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(currentTarget)
                .build();
        Translator tx = Translation.getClient(options);
        tx.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> tx.translate(text)
                        .addOnSuccessListener(result -> {
                            callback.onResult(result);
                            tx.close();
                        })
                        .addOnFailureListener(e -> {
                            callback.onError(e.getMessage() != null ? e.getMessage() : "Translation error");
                            tx.close();
                        }))
                .addOnFailureListener(e -> {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Model unavailable");
                    tx.close();
                });
    }

    /** Translate text using the prepared translator. */
    public void translate(String text, TranslateCallback callback) {
        if (!ready || translator == null) {
            callback.onError("Translator not ready");
            return;
        }
        if (text == null || text.isEmpty()) {
            callback.onResult("");
            return;
        }
        translator.translate(text)
                .addOnSuccessListener(callback::onResult)
                .addOnFailureListener(e ->
                        callback.onError(e.getMessage() != null ? e.getMessage() : "Translation failed"));
    }

    /** Releases translator resources. Must be called when translation is no longer needed. */
    public void close() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
        ready = false;
    }
}

