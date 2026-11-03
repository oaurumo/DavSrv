# Ktor / coroutines
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
# SLF4J binding is optional on Android (NOP logger is fine)
-dontwarn org.slf4j.impl.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
# Keep enum values used reflectively by ktor content type
-keepclassmembers enum io.ktor.http.** { *; }

# jmDNS (mDNS announcer)
-dontwarn javax.jmdns.**
-keep class javax.jmdns.** { *; }
