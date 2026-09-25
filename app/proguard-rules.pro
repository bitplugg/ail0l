-keep class com.arm.aichat.** { *; }
-keepclasseswithmembernames class com.arm.aichat.** { native <methods>; }

-keep class com.aiia.app.sync.SyncWorker { *; }

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aiia.app.sync.** { *; }
-keep class com.aiia.app.plugins.engine.** { *; }
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**
