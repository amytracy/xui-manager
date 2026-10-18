# Code shrinking only: names are kept readable, the app ships no obfuscated logic.
-dontobfuscate

# BouncyCastle registers its algorithms by class name and loads them reflectively.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# sshj looks up key exchange, cipher and signature implementations through its own factories,
# some of them by reflection.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }

# Optional dependencies referenced by sshj and BouncyCastle that are not on Android.
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn com.jcraft.jzlib.**
-dontwarn javax.security.auth.login.**
