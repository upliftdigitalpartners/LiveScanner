# kotlinx.serialization — keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.fahim.livescanner.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.fahim.livescanner.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
