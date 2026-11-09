package local.iccoa.probe;

import android.bluetooth.BluetoothDevice;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentLinkedQueue;

/** The framework's GATT requests for this local peer are queued without patching their Java call stacks. */
public final class ClientTransport implements InvocationHandler {
    public static final class Event {
        public final String kind;
        public final int number;
        public final byte[] value;

        Event(String kind, int number, byte[] value) {
            this.kind = kind;
            this.number = number;
            this.value = value == null ? null : value.clone();
        }
    }

    private final Object original;
    private final String peer;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    public final Object proxy;

    public ClientTransport(Object original, Class<?> api, String peer) {
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
                case "configureMTU":
                    events.add(new Event("mtu", (Integer) args[2], null));
                    return null;
                case "discoverServices":
                case "discoverServiceByUuid":
                    events.add(new Event("discover", 0, null));
                    return null;
                case "writeCharacteristic":
                    events.add(new Event("write", (Integer) args[2], (byte[]) args[5]));
                    return 0;
                case "refreshDevice":
                case "registerForNotification":
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
