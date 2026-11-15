-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn java.awt.**
# Netty's optional desktop logging backends are absent; Sprig uses its JDK logger.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
# These classes load package-relative tables/version files, so preserve their resource paths.
-keepnames class tech.kwik.qpack.impl.StaticTable
-keepnames class tech.kwik.qpack.impl.Huffman
-keepnames interface tech.kwik.core.QuicConnection
-keepnames class tech.kwik.agent15.Version
