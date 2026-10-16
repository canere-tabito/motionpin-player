# JNI symbols and native entry points must retain their names in optimized builds.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.caneretabito.motionpin.ai.Native* { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
