# sshj (SSH protocol + crypto)
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
# sshj zlib compression (com.jcraft.jzlib) — losing these kills the
# post-KEX compressed stream with NoClassDefFoundError on the reader thread
-keep class com.jcraft.jzlib.** { *; }
-dontwarn com.jcraft.jschagentproxy.**
-dontwarn org.ietf.jgss.**

# BouncyCastle — reflection-heavy provider registration
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-dontwarn org.bouncycastle.**

# sshj pulls optional deps we don't ship
-dontwarn org.slf4j.impl.**
-dontwarn ch.qos.logback.**
-dontwarn org.apache.sshd.**
# GSS-API / Kerberos login path unused on Android
-dontwarn javax.security.auth.login.**
# eddsa references JDK-internal X509Key (never used at runtime on Android)
-dontwarn sun.security.x509.**

# SLF4J android backend
-keep class org.slf4j.android.** { *; }

# Sentry — R8 strips listeners the SDK registers reflectively
-keep class io.sentry.** { *; }
-keep interface io.sentry.** { *; }
-dontwarn io.sentry.**
