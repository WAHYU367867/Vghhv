# ALHUR TV release hardening
# Android and Media3 keep their required annotations and reflective entry points.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keep class androidx.room.** { *; }
-keep class com.alhorrtv.app.ContentDatabase_Impl { *; }
-keep class com.alhorrtv.app.**_Impl { *; }
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
