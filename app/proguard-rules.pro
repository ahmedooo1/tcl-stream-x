# kotlinx.serialization keeps generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class fr.ahmad.tclstreamx.** { kotlinx.serialization.KSerializer serializer(...); }
