package local.iccoa.probe;

import android.bluetooth.BluetoothDevice;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Routes only the virtual peer's outgoing GATT server calls to the local relay. */
public final class ServerTransport implements InvocationHandler {
    public static final class Event {
        public final String kind;
        public final int number;
        public final int status;
        public final byte[] value;

        Event(String kind, int number, int status, byte[] value) {
            this.kind = kind;
            this.number = number;
            this.status = status;
            this.value = value == null ? null : value.clone();
        }
    }

    private final Object original;
    private final String peer;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    public final Object proxy;

    public ServerTransport(Object original, Class<?> api, String peer) {
        this.original = original;
        this.peer = peer;
        proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, this);
    }

    public Event poll() {
        return events.poll();
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null && args.length > 1 && args[1] instanceof BluetoothDevice
                && peer.equals(((BluetoothDevice) args[1]).getAddress())) {
            switch (method.getName()) {
                case "sendResponse":
                    events.add(new Event("response", (Integer) args[2], (Integer) args[3], (byte[]) args[5]));
                    return null;
                case "sendNotification":
                    events.add(new Event("notification", (Integer) args[2], 0, (byte[]) args[4]));
                    return 0;
                case "serverDisconnect":
                    events.add(new Event("remoteClose", 0, 0, null));
                    return null;
                default:
                    break;
            }
        }
        try {
            return method.invoke(original, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
