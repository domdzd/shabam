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
