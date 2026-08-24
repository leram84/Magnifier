package com.example.pocketmagnifier;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the live OCR → language-ID → translation pipeline.
 *
 * Usage:
 *  1. Construct with a {@link ResultCallback}.
 *  2. Call {@link #setTargetLanguage} then {@link #start}.
 *  3. Feed frames via {@link #processFrame}.
 *  4. Call {@link #stop} to halt processing and release resources.
 */
public class LiveTranslateController {

    /** Cadence between OCR+translate runs (milliseconds). */
    private static final long THROTTLE_MS = 500;

    /** Minimum text-change threshold before we re-translate (avoids pointless work). */
    private static final int MIN_CHANGE_LENGTH = 3;

    public interface ResultCallback {
        /** Called on the main thread with translation results. */
        void onTranslation(String translatedText);
        /** Called on the main thread when no text is in view. */
        void onNoText();
        /** Called on the main thread for status messages (downloading model, errors). */
        void onStatus(String status);
    }

    private final ResultCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final LanguageIdentifier langId = LanguageIdentification.getClient();
    private final TranslatorManager translatorManager = new TranslatorManager();

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean translating = new AtomicBoolean(false);

    private long lastRunMs = 0;
    private String lastRawText = "";
    private String targetLanguage = TranslateLanguage.ENGLISH;

    public LiveTranslateController(ResultCallback callback) {
        this.callback = callback;
    }

    /** Set the target language (ML Kit language tag, e.g. "fr", "es", "zh"). */
    public void setTargetLanguage(String languageTag) {
        targetLanguage = languageTag;
        // Invalidate cached text so we re-translate with new target
        lastRawText = "";
    }

    /**
     * Start translation mode. Prepares the translator (downloads model if needed).
     */
    public void start() {
        active.set(true);
        lastRawText = "";
        prepareTranslator();
    }

    private void prepareTranslator() {
        mainHandler.post(() -> callback.onStatus("Preparing translator…"));
        translatorManager.prepare(targetLanguage, new TranslatorManager.PrepareCallback() {
            @Override public void onReady() {
                mainHandler.post(() -> callback.onStatus(null)); // clear status
            }
            @Override public void onDownloading() {
                mainHandler.post(() -> callback.onStatus("Downloading language model…"));
            }
            @Override public void onError(String message) {
                mainHandler.post(() -> callback.onStatus("Model error: " + message));
            }
        });
    }

    /**
     * Submit a camera frame for processing. Frames arriving faster than {@link #THROTTLE_MS}
     * are silently dropped. Safe to call from any thread.
     */
    public void processFrame(Bitmap bitmap) {
        if (!active.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastRunMs < THROTTLE_MS) return;
        if (translating.getAndSet(true)) return; // another run in progress
        lastRunMs = now;

        // Capture a safe copy in case the original bitmap is recycled by the camera pipeline
        final Bitmap frame = bitmap.copy(Bitmap.Config.ARGB_8888, false);

        executor.submit(() -> {
            try {
                runOcrAndTranslate(frame);
            } finally {
                frame.recycle();
                translating.set(false);
            }
        });
    }

    private void runOcrAndTranslate(Bitmap frame) {
        InputImage image = InputImage.fromBitmap(frame, 0);
        // OCR is async; results come back on a background thread from ML Kit
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String raw = visionText.getText().trim();
                    if (raw.isEmpty()) {
                        mainHandler.post(callback::onNoText);
                        return;
                    }
                    // Skip translation if text hasn't changed meaningfully
                    if (Math.abs(raw.length() - lastRawText.length()) < MIN_CHANGE_LENGTH
                            && raw.equals(lastRawText)) {
                        return;
                    }
                    lastRawText = raw;
                    identifyAndTranslate(raw);
                })
                .addOnFailureListener(e ->
                        mainHandler.post(() -> callback.onStatus("OCR error: " + e.getMessage())));
    }

    private void identifyAndTranslate(String text) {
        langId.identifyLanguage(text)
                .addOnSuccessListener(lang -> {
                    // "und" means undetermined; fall back to English as assumed source
                    String source = (lang == null || lang.equals("und"))
                            ? TranslateLanguage.ENGLISH : lang;
                    // If source == target, show raw text
                    if (source.equals(targetLanguage)) {
                        mainHandler.post(() -> callback.onTranslation(text));
                        return;
                    }
                    translatorManager.translateFrom(source, text,
                            new TranslatorManager.TranslateCallback() {
                                @Override public void onResult(String translated) {
                                    mainHandler.post(() -> callback.onTranslation(translated));
                                }
                                @Override public void onError(String message) {
                                    mainHandler.post(() -> callback.onStatus("Translation: " + message));
                                }
                            });
                })
                .addOnFailureListener(e -> {
                    // Fall back to translate without language ID
                    translatorManager.translate(text, new TranslatorManager.TranslateCallback() {
                        @Override public void onResult(String translated) {
                            mainHandler.post(() -> callback.onTranslation(translated));
                        }
                        @Override public void onError(String message) {
                            mainHandler.post(() -> callback.onStatus("Translation: " + message));
                        }
                    });
                });
    }

    /** Stop translation mode and release all resources. */
    public void stop() {
        active.set(false);
        lastRawText = "";
        translatorManager.close();
    }

    /** Release all ML Kit resources permanently (call from onDestroy). */
    public void destroy() {
        stop();
        executor.shutdown();
        recognizer.close();
        langId.close();
    }
}
