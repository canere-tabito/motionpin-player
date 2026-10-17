import com.google.mediapipe.proto.MediaPipeLoggingEnumsProto.Platform;
import com.google.mediapipe.proto.MediaPipeLoggingProto.SystemInfo;

/** Exercises the real schema reflection reached during MediaPipe detector creation. */
public final class MediaPipeProtoProbe {
    public static void main(String[] args) throws Exception {
        SystemInfo message = SystemInfo.newBuilder()
                .setPlatform(Platform.PLATFORM_ANDROID)
                .setAppId("com.caneretabito.motionpin")
                .setAppVersion("r8-regression")
                .build();
        byte[] bytes = message.toByteArray();
        SystemInfo parsed = SystemInfo.parseFrom(bytes);
        if (!parsed.equals(message) || bytes.length == 0) {
            throw new AssertionError("Real protobuf roundtrip failed");
        }
        System.out.println("PASS real SystemInfo build/serialize/parse; bytes=" + bytes.length);
    }
}
