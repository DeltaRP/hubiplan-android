# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class de.hubiplan.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class de.hubiplan.app.data.**$$serializer { *; }
