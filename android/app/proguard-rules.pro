# NanoTune R8 rules.
#
# Apple's ShazamKit AAR performs its own networking (Retrofit + Gson). We cannot inspect whether
# it ships complete consumer rules, so keep it intact rather than risk breaking reflection.
-keep class com.shazam.shazamkit.** { *; }
-dontwarn com.shazam.**

# kotlinx.serialization ships its own rules; keep our DTO companions for safety on older R8s.
-keepclassmembers @kotlinx.serialization.Serializable class app.nanotune.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Backend/MusicBrainz DTOs and navigation routes are (de)serialised by kotlinx.serialization through
# Retrofit's converter, which resolves serializers from generic signatures at runtime.
-keep @kotlinx.serialization.Serializable class app.nanotune.** { *; }
-keep,allowobfuscation interface app.nanotune.data.remote.BackendApi
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
