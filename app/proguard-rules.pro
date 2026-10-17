# JNI symbols and native entry points must retain their names in optimized builds.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.caneretabito.motionpin.ai.Native* { *; }
# MediaPipe JNI looks up Java classes, constructors, callback methods and fields
# by name. Keeping only declarations marked `native` does not protect the Java
# entry points invoked *from* native code (Packet.create, exception constructors,
# SerializedMessage fields, etc.). Its AAR currently provides no consumer rules.
# Preserve the concrete JNI boundary, not unused graph-template/profiling APIs
# whose optional generated protos are absent from the published 1.1.0 AAR.
-keep class com.google.mediapipe.framework.Packet { *; }
-keep interface com.google.mediapipe.framework.PacketListCallback { *; }
-keep class com.google.mediapipe.framework.MediaPipeException { *; }
-keep class com.google.mediapipe.framework.ProtoUtil$SerializedMessage { *; }
-keep class com.google.mediapipe.framework.PacketCreator { *; }
-keep interface com.google.mediapipe.framework.TextureReleaseCallback { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# MediaPipe's protobuf-javalite messages resolve these fields by their original
# names from the generated schema. The 1.1.0 AARs do not ship this consumer rule.
# In alpha1 R8 removed SystemInfo.platform_ (and the other instance fields), so
# detector construction failed before inference for every video. Keep fields,
# including their names, while allowing unused message classes/methods to shrink.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
