package io.github.shaonialife.carptocar.carplus;

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Root helper for ColorOS's external BLE discovery provider. Runs outside the receiver UID. */
public final class ColorOsDiscovery {
    static final String RECEIVER_NAME = "CarPToCar+ICCOA";
    private static final String NATIVE_PROCESS = "com.oplus.ocar:connect";
    private static final String GUIDE = "com.oplus.ocar/.settings.connect.WirelessConnectGuideActivity";
    private static final String PROVIDER = "com.oplus.ocarlinkmanager.core.CarWebBleProvider";
    private static final int SEQUENCE_OFFSET = 13;
    private static final int RECEIVER_ID_OFFSET = SEQUENCE_OFFSET + 1;
    private static final int PACKET_LENGTH = 47;
    private static final Pattern LOG_LINE = Pattern.compile(
        "^\\s*(\\d+)\\.(\\d{3})\\s+(\\d+)\\s+\\d+\\s+[A-Z]\\s+([^:]+):\\s*(.*)$"
    );

    private final int receiverPid;
    private final long startedAt;
    private final Path packetPath;
    private final ColorOsRelay relay;
    private final ScheduledExecutorService requests = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean advertising;
    private volatile long advertisementEpoch;
    private volatile boolean closing;
    private long lastScanAt = -10_000;
    private int sequence = (int) SystemClock.elapsedRealtimeNanos() & 255;

    private ColorOsDiscovery(int receiverPid, long startedAt, Path packetPath, ColorOsRelay relay) {
        this.receiverPid = receiverPid;
        this.startedAt = startedAt;
        this.packetPath = packetPath;
        this.relay = relay;
    }

    public static void main(String[] args) {
        int result = 1;
        try {
            if (android.os.Process.myUid() != 0 || args.length != 4) return;
            Looper.prepareMainLooper();
            try (ColorOsRelay relay = new ColorOsRelay(Path.of(args[3]))) {
                new ColorOsDiscovery(Integer.parseInt(args[0]), Long.parseLong(args[1]), Path.of(args[2]), relay).watch();
            }
            result = 0;
        } catch (Exception error) {
            System.out.println("ColorOS discovery stopped: " + error.getClass().getSimpleName());
        } finally {
            System.exit(result);
        }
    }

    private void watch() throws Exception {
        Process logcat = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-v", "threadtime",
            "-v", "epoch", "-T", "350", "-s", "CarConnect:I", "CarPlusBle:I",
            "UCar_BLEService:I", "easylog_BLEService:I").redirectErrorStream(true).start();
        Thread shutdown = new Thread(() -> {
            try (BufferedReader control = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
                String command;
                while ((command = control.readLine()) != null) {
                    if ("reconnect".equals(command)) requests.execute(() -> reconnectInBackground(0));
                }
            } catch (Exception ignored) {
                // The app closed the control pipe.
            } finally {
                closing = true;
                logcat.destroy();
            }
        }, "ICCOA-discovery-lifetime");
        shutdown.setDaemon(true);
        shutdown.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream()))) {
            System.out.println("ColorOS discovery watcher ready");
            String line;
            while ((line = reader.readLine()) != null) {
                handleLog(line);
            }
        } catch (IOException error) {
            if (!closing) throw error;
        } finally {
            requests.shutdownNow();
            logcat.destroy();
        }
    }

    private void handleLog(String line) {
        Matcher entry = LOG_LINE.matcher(line);
        if (!entry.matches()) return;
        long timestamp = Long.parseLong(entry.group(1)) * 1000 + Integer.parseInt(entry.group(2));
        if (timestamp < startedAt) return;
        int pid = Integer.parseInt(entry.group(3));
        String tag = entry.group(4).trim();
        String message = entry.group(5);
        if (pid == receiverPid) {
            if ("CarPlusBle".equals(tag) && (message.startsWith("ICCOA BLE request ")
                    || message.equals("ICCOA BLE idle"))) {
                advertising = false;
                if (message.startsWith("ICCOA BLE request ")) advertisementEpoch = timestamp;
            } else if (("UCar_BLEService".equals(tag) || "easylog_BLEService".equals(tag))
                    && message.startsWith("onAdvertisingSetStarted status ")) {
                advertising = message.startsWith("onAdvertisingSetStarted status 0 ");
            }
            return;
        }
        if (!"CarConnect".equals(tag) || !message.contains("[UCarConnectionFlow]")
                || !message.contains("act=com.oplus.ocar.connect.START_WIRELESS_SCAN")
                || timestamp - lastScanAt < 2_500 || System.currentTimeMillis() - timestamp > 7_000
                || !NATIVE_PROCESS.equals(processName(pid))) return;
        lastScanAt = timestamp;
        int scanSequence = ++sequence & 255;
        requests.schedule(() -> forwardIfVisible(pid, scanSequence), 250, TimeUnit.MILLISECONDS);
        requests.schedule(() -> forwardIfVisible(pid, scanSequence), 2_400, TimeUnit.MILLISECONDS);
    }

    private void forwardIfVisible(int nativePid, int scanSequence) {
        try {
            long epoch = activeEpoch(true);
            if (epoch == 0 || !guideIsVisible()) return;
            byte[] data = Files.readAllBytes(packetPath);
            if (data.length != PACKET_LENGTH) return;
            if (!NATIVE_PROCESS.equals(processName(nativePid))) return;
            if (!relay.prepare(receiverPid, nativePid, epoch, receiverAddress(data))) return;
            if (activeEpoch(true) != epoch || !guideIsVisible()) return;
            data[SEQUENCE_OFFSET] = (byte) scanSequence;
            System.out.println("ColorOS discovery provider returned " + forward(data, 0));
        } catch (Exception error) {
            System.out.println("ColorOS discovery request failed: " + error.getClass().getSimpleName());
        } catch (LinkageError error) {
            System.out.println("ColorOS discovery runtime unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void reconnectInBackground(long retryEpoch) {
        try {
            long epoch = activeEpoch(false);
            if (closing || epoch == 0 || (retryEpoch != 0 && epoch != retryEpoch)) return;
            byte[] data = Files.readAllBytes(packetPath);
            if (data.length != PACKET_LENGTH) return;
            data[SEQUENCE_OFFSET] = (byte) (++sequence & 255);
            int result = forward(data, epoch);
            System.out.println("ColorOS background reconnect provider returned " + result);
            // The SDK can have queued advertising while its GATT server is still opening.
            if (result == -1 && retryEpoch == 0 && !closing && activeEpoch(false) == epoch) {
                requests.schedule(() -> reconnectInBackground(epoch), 500, TimeUnit.MILLISECONDS);
            }
        } catch (Exception | LinkageError error) {
            System.out.println("ColorOS background reconnect failed: " + error.getClass().getSimpleName());
        }
    }

    private long activeEpoch(boolean allowLogFallback) {
        try {
            String[] state = new String(Files.readAllBytes(packetPath.resolveSibling("iccoa-discovery.ready")),
                StandardCharsets.US_ASCII).trim().split(" ");
            if (state.length == 2 && Integer.parseInt(state[0]) == receiverPid) {
                long readyAt = Long.parseLong(state[1]);
                if (readyAt >= startedAt) return readyAt;
            }
        } catch (Exception ignored) {
            // The receiver may have stopped advertising.
        }
        return allowLogFallback && advertising ? advertisementEpoch : 0;
    }

    private static boolean guideIsVisible() throws Exception {
        Process task = new ProcessBuilder("/system/bin/dumpsys", "-t", "2", "activity", "activities")
            .redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(task.getInputStream()))) {
            return reader.lines().anyMatch(line -> line.contains("topResumedActivity=") && line.contains(GUIDE));
        } finally {
            task.destroy();
        }
    }

    private static String processName(int pid) {
        try {
            byte[] name = Files.readAllBytes(Path.of("/proc", Integer.toString(pid), "cmdline"));
            int end = 0;
            while (end < name.length && name[end] != 0) end++;
            return new String(name, 0, end, StandardCharsets.US_ASCII);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String receiverAddress(byte[] data) {
        // The local receiver ID is the logical address for discovery. It is stable across scans.
        StringBuilder address = new StringBuilder();
        for (int index = 0; index < 6; index++) {
            if (index != 0) address.append(':');
            address.append(String.format(Locale.ROOT, "%02X", data[RECEIVER_ID_OFFSET + index]));
        }
        return address.toString();
    }

    private int forward(byte[] data, long backgroundEpoch) throws Exception {
        BluetoothDevice device = device(receiverAddress(data));
        ScanRecord record = (ScanRecord) ScanRecord.class.getMethod("parseFromBytes", byte[].class)
            .invoke(null, (Object) data);
        ScanResult scan = ScanResult.class.getConstructor(BluetoothDevice.class, ScanRecord.class, int.class, long.class)
            .newInstance(device, record, -45, SystemClock.elapsedRealtimeNanos());

        Class<?> managerType = Class.forName("android.app.IActivityManager");
        Object manager = ActivityManager.class.getMethod("getService").invoke(null);
        IBinder token = new Binder();
        Object holder = managerType.getMethod("getContentProviderExternal",
            String.class, int.class, IBinder.class, String.class)
            .invoke(manager, PROVIDER, 0, token, "carptocar-iccoa-discovery");
        if (holder == null) return -1;
        try {
            if (backgroundEpoch != 0) {
                // Acquiring the provider starts the native :connect process without opening an Activity.
                int nativePid = nativeProcessId();
                if (closing || nativePid == 0 || activeEpoch(false) != backgroundEpoch ||
                    !relay.prepare(receiverPid, nativePid, backgroundEpoch, receiverAddress(data)) ||
                    closing || activeEpoch(false) != backgroundEpoch) return -1;
            }
            Object provider = holder.getClass().getField("provider").get(holder);
            Bundle extras = new Bundle();
            extras.putParcelable("ocar_plugin_key_scan_result", scan);
            AttributionSource root = new AttributionSource.Builder(0).setPackageName("root").build();
            Method call = Class.forName("android.content.IContentProvider").getMethod("call",
                AttributionSource.class, String.class, String.class, String.class, Bundle.class);
            Bundle response = (Bundle) call.invoke(provider, root, PROVIDER, "notifyCarFound", null, extras);
            return response == null ? -1 : response.getInt("ocar_found_result", -1);
        } finally {
            managerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                .invoke(manager, PROVIDER, token, 0);
        }
    }

    private static int nativeProcessId() throws Exception {
        Process process = new ProcessBuilder("/system/bin/pidof", NATIVE_PROCESS).start();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return 0;
            String ids = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (ids.isEmpty()) return 0;
            for (String id : ids.split("\\s+")) {
                int pid = Integer.parseInt(id);
                if (NATIVE_PROCESS.equals(processName(pid))) return pid;
            }
            return 0;
        } finally {
            process.destroy();
        }
    }

    private static BluetoothDevice device(String address) throws Exception {
        try {
            Constructor<BluetoothDevice> constructor =
                BluetoothDevice.class.getDeclaredConstructor(String.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(address, 0);
        } catch (NoSuchMethodException ignored) {
            Constructor<BluetoothDevice> constructor = BluetoothDevice.class.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(address);
        }
    }

    static byte[] packet(String protocol, String carId, String modelId, String vendorData, int threshold) {
        if (protocol == null || !protocol.matches("\\d{1,3}\\.\\d{1,3}") || threshold < -128 || threshold > 0) {
            throw new IllegalArgumentException("Invalid ICCOA advertising parameters");
        }
        String[] version = protocol.split("\\.");
        byte[] data = new byte[PACKET_LENGTH];
        byte[] header = {2, 1, 6, 3, 3, (byte) 0xfb, (byte) 0xfc, 19, 0x16, 1, 0};
        System.arraycopy(header, 0, data, 0, header.length);
        data[11] = versionByte(version[0]);
        data[12] = versionByte(version[1]);
        copyHex(carId, data, RECEIVER_ID_OFFSET, 6);
        copyHex(modelId, data, 20, 4);
        copyHex(vendorData, data, 24, 2);
        data[26] = (byte) threshold;
        data[27] = 19;
        data[28] = 0x16;
        data[29] = 2;
        byte[] name = RECEIVER_NAME.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, data, 31, name.length);
        return data;
    }

    private static byte versionByte(String value) {
        int number = Integer.parseInt(value);
        if (number > 255) throw new IllegalArgumentException("Invalid ICCOA version");
        return (byte) number;
    }

    private static void copyHex(String value, byte[] packet, int start, int size) {
        if (value == null || value.length() != size * 2 || !value.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Invalid ICCOA device identity");
        }
        for (int index = 0; index < size; index++) {
            packet[start + index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
    }
}
