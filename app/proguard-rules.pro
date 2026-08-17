# Conservative keeps: the app has almost no reflection surface.
# okhttp/okio ship their own consumer rules; androidx likewise.
# org.json is a platform class - untouched by R8 anyway.

# Keep BuildConfig fields read via the Cfg object (paranoia; direct field reads survive R8,
# but the cost of keeping one class is zero).
-keep class com.vladiko.voicebridge.BuildConfig { *; }

# TTS engine binds by reflection to the listener in some OEM builds.
-keep class * extends android.speech.tts.UtteranceProgressListener { *; }
