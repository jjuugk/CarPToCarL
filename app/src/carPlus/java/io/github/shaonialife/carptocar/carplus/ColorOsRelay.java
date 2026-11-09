package io.github.shaonialife.carptocar.carplus;

import android.util.Base64;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Owns the two local injector sessions. Neither process accepts remote debugger connections. */
final class ColorOsRelay implements AutoCloseable {
    private static final String PREFIX = "ICCOA_RELAY ";
    private static final String ASSETS = "assets/iccoa-relay/";
    private static final String LOCAL_CLIENT = "02:00:00:00:00:01";
    private static final Set<String> MILESTONES = Set.of(
        "serverListening", "clientHooksReady", "localTransportReady", "nativeGattServices",
        "receiverLocalAuthBound", "nativeLocalAuthStart", "sdkGattResponse", "nativeGattNotification",
        "setupError", "serverSetupError", "callbackError", "socketIoError", "nativeGattCaptureError"
    );

    private Path directory;
    private Path injector;
    private Path script;
    private String clientDex;
    private String serverDex;
    private Child receiver;
    private Child nativeCar;
    private int nativePid;
    private long epoch;

    ColorOsRelay(Path apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            if (zip.getEntry(ASSETS + "injector") == null) return;
            directory = Files.createTempDirectory(Path.of("/data/local/tmp"), "carptocar-iccoa-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try {
                injector = copy(zip, "injector");
                if (!injector.toFile().setExecutable(true, true)) throw new IOException("Injector is not executable");
                script = copy(zip, "relay.js");
                clientDex = dex(zip, "client.dex");
                serverDex = dex(zip, "server.dex");
            } catch (IOException error) {
                close();
                throw error;
            }
        }
    }

    private Path copy(ZipFile zip, String name) throws IOException {
        Path path = directory.resolve(name);
        try (InputStream source = asset(zip, name)) {
            Files.copy(source, path);
        }
        return path;
    }

    private static String dex(ZipFile zip, String name) throws IOException {
        try (InputStream source = asset(zip, name)) {
            return Base64.encodeToString(source.readAllBytes(), Base64.NO_WRAP);
        }
    }

    private static InputStream asset(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(ASSETS + name);
        if (entry == null) throw new IOException("Missing local relay asset");
        return zip.getInputStream(entry);
    }

    synchronized boolean prepare(int receiverPid, int carPid, long advertisedAt, String address) throws Exception {
        if (injector == null) return true;
        if (nativePid == carPid && epoch == advertisedAt && receiver != null && nativeCar != null
                && receiver.alive() && nativeCar.alive()) return true;
        stop();
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        String token = Base64.encodeToString(secret, Base64.NO_WRAP);
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        try {
            receiver = start(receiverPid, config("receiver", token, port)
                .put("phoneAddress", LOCAL_CLIENT.equals(address) ? "02:00:00:00:00:02" : LOCAL_CLIENT)
                .put("serverDex", serverDex).put("p2pOnly", true), "serverListening");
            if (!receiver.awaitReady()) return false;
            nativeCar = start(carPid, config("native", token, port)
                .put("address", address).put("clientDex", clientDex).put("localAuth", true), "localTransportReady");
            if (!nativeCar.awaitReady()) return false;
            nativePid = carPid;
            epoch = advertisedAt;
            return true;
        } finally {
            if (nativePid != carPid || epoch != advertisedAt) stop();
        }
    }

    private static JSONObject config(String role, String token, int port) throws Exception {
        return new JSONObject().put("managed", true).put("role", role).put("token", token)
            .put("port", port).put("localLoopback", true);
    }

    private Child start(int pid, JSONObject config, String readyEvent) throws IOException {
        Process process = new ProcessBuilder(injector.toString(), "-p", Integer.toString(pid),
            "-i", "-s", script.toString(), "-P", config.toString()).redirectErrorStream(true).start();
        return new Child(process, readyEvent, config.optString("role"));
    }

    private void stop() {
        if (nativeCar != null) nativeCar.stop();
        if (receiver != null) receiver.stop();
        nativeCar = null;
        receiver = null;
        nativePid = 0;
    }

    @Override public synchronized void close() {
        stop();
        if (directory == null) return;
        try {
            Files.deleteIfExists(directory.resolve("injector"));
            Files.deleteIfExists(directory.resolve("relay.js"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // The private directory may already have been removed by Android.
        }
    }

    private static final class Child {
        private final Process process;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch reset = new CountDownLatch(1);
        private volatile boolean started;

        Child(Process process, String readyEvent, String role) {
            this.process = process;
            Thread reader = new Thread(() -> readEvents(readyEvent, role), "iccoa-" + role);
            reader.setDaemon(true);
            reader.start();
        }

        private void readEvents(String readyEvent, String role) {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (!line.startsWith(PREFIX)) continue;
                    JSONObject message = new JSONObject(line.substring(PREFIX.length()));
                    String event = message.optString("event");
                    if (MILESTONES.contains(event)) {
                        System.out.println("ICCOA " + role + " " + event);
                        if (event.endsWith("Error")) {
                            System.out.println("ICCOA " + role + " " + message.optString("error").split("\\n", 2)[0]);
                        }
                    }
                    if (readyEvent.equals(event)) {
                        started = true;
                        ready.countDown();
                    }
                    if ("relayStopped".equals(event)) reset.countDown();
                    if ("setupError".equals(event) || "serverSetupError".equals(event)) ready.countDown();
                }
            } catch (Exception ignored) {
                // Stream closure releases any pending waiter below.
            } finally {
                if (!started) System.out.println("ICCOA " + role + " injector stopped before readiness");
                ready.countDown();
                reset.countDown();
            }
        }

        boolean awaitReady() throws InterruptedException {
            return ready.await(5, TimeUnit.SECONDS) && started && alive();
        }

        boolean alive() {
            return process.isAlive();
        }

        void stop() {
            if (!alive()) return;
            try {
                process.getOutputStream().write("reset\n".getBytes(StandardCharsets.US_ASCII));
                process.getOutputStream().flush();
                reset.await(650, TimeUnit.MILLISECONDS);
            } catch (IOException ignored) {
                // The injected process may have exited already.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                process.destroy();
            }
        }
    }
}
