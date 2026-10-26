import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Emulator-only integration probe; loads the production adapter from the installed APK. */
public final class DisplayTouchProbe {
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) throws Exception {
        String emulator = (String) Class.forName("android.os.SystemProperties")
            .getMethod("get", String.class).invoke(null, "ro.kernel.qemu");
        if (Process.myUid() != 2000 || !"1".equals(emulator) || args.length != 4)
            throw new IllegalArgumentException("Requires emulator shell UID 2000 and display/uid/x/y");
        int display = Integer.parseInt(args[0]), uid = Integer.parseInt(args[1]);
        float x = Float.parseFloat(args[2]), y = Float.parseFloat(args[3]);
        if (display <= 0 || uid < 10000 || !Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0)
            throw new IllegalArgumentException("Invalid target");
        JSONObject report = new JSONObject().put("scope", "EMULATOR_PRODUCTION_ADAPTER_OUTSIDE_BROKER")
            .put("shellUid", Process.myUid()).put("displayId", display).put("targetUid", uid);
        Object adapter = Class.forName("com.octopus.agent.bridge.DisplayTouchInjector").getConstructor().newInstance();
        Class phase = Class.forName("com.octopus.core.TouchPhase");
        Class pointType = Class.forName("com.octopus.core.TouchPoint");
        Class packetType = Class.forName("com.octopus.core.TouchPacket");
        Object point = pointType.getConstructor(float.class, float.class).newInstance(x, y);
        Constructor packet = packetType.getConstructor(String.class, long.class, phase, pointType, long.class);
        Method inject = adapter.getClass().getMethod("inject", int.class, int.class, packetType, long.class);
        String gesture = UUID.randomUUID().toString();
        long downTime = SystemClock.uptimeMillis();
        boolean down = false, up = false, attempted = false;
        try {
            attempted = true;
            down = (Boolean) inject.invoke(adapter, display, uid,
                packet.newInstance(gesture, 0L, Enum.valueOf(phase, "DOWN"), point, downTime), downTime);
            if (down) {
                Thread.sleep(80);
                up = (Boolean) inject.invoke(adapter, display, uid,
                    packet.newInstance(gesture, 1L, Enum.valueOf(phase, "UP"), point, SystemClock.uptimeMillis()), downTime);
            }
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
            report.put("failure", cause.getClass().getSimpleName());
        } finally {
            if (attempted && !up) {
                // The system verifier may see DOWN even when target-UID admission rejects it.
                try { report.put("cancelAccepted", inject.invoke(adapter, display, uid,
                    packet.newInstance(gesture, 2L, Enum.valueOf(phase, "CANCEL"), point, SystemClock.uptimeMillis()), downTime));
                } catch (Exception ignored) { report.put("cancelUnknown", true); }
            }
        }
        System.out.println(report.put("downAccepted", down).put("upAccepted", up).toString());
    }
}
