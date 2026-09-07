
# JNI entry points are called by name from C++, so R8 cannot see the references and would
# otherwise rename or remove them - which fails at runtime with UnsatisfiedLinkError rather than
# at build time.
-keepclasseswithmembernames class com.animeshahilya.neutts.tts.NeuTTSBackbone { native <methods>; }
-keepclasseswithmembernames class com.animeshahilya.neutts.tts.Phonemizer { native <methods>; }

# Catalog and voice models are constructed by kotlinx.serialization from JSON in the assets, so
# their generated serializers and field names have to survive.
-keep,includedescriptorclasses class com.animeshahilya.neutts.data.**$$serializer { *; }
-keep,includedescriptorclasses class com.animeshahilya.neutts.tts.**$$serializer { *; }
-keepclassmembers class com.animeshahilya.neutts.data.** { *** Companion; }
-keepclassmembers class com.animeshahilya.neutts.tts.** { *** Companion; }

# ONNX Runtime reaches into its own classes reflectively.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
