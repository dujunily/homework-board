# kotlinx.serialization：保留 @Serializable 类的生成序列化器，否则 R8 会裁掉导致运行时找不到 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclasseswithmembers class cn.yiweiqiu.homework.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cn.yiweiqiu.homework.core.**$$serializer { *; }
-keepclassmembers class cn.yiweiqiu.homework.core.** {
    *** Companion;
}
-keepclasseswithmembers class cn.yiweiqiu.homework.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
