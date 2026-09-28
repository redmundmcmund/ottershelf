# kotlinx.serialization: keep generated serializers for our @Serializable models (API models,
# ProgressStore records, download metadata and the Navigation 3 routes, which are saved as JSON).
-keepclassmembers @kotlinx.serialization.Serializable class io.github.ottershelf.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.ottershelf.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Objects (data object routes) are looked up through their INSTANCE field by the serializer.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.ottershelf.** {
    public static ** INSTANCE;
}

# The reader page calls these through `window.Android` (feature.reader.ReaderViewModel.Bridge).
-keepclassmembers class io.github.ottershelf.feature.reader.ReaderViewModel$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Exception names stay readable, as in the debug build: an error with no message shows its class
# name (`e.message ?: e.javaClass.simpleName` in the screens and DownloadWorker), and logcat traces
# name the real exceptions. Names only: an exception nothing uses is still removed.
-keepnames class * extends java.lang.Throwable

# Tesseract4Android (feature.quotes.PageReader) ships no consumer rules, and its native code reaches
# back into its Java classes by name (TessBaseAPI.onProgressValues, called only from native code,
# and the classes behind its JNI functions): keep its two packages whole (about 50 KB).
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
