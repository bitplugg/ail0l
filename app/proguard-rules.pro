# llama.cpp binding (JNI/native)
-keep class com.arm.aichat.** { *; }
-keepclasseswithmembernames class com.arm.aichat.** { native <methods>; }

# Worker (WorkManager instantiates via reflection in AGP < 8.x; keep constructor)
-keep class com.ail0l.app.sync.SyncWorker { *; }

# Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.ail0l.app.data.sync.** { *; }