import Java from 'frida-java-bridge';

let PORT = 27861;
let stopReceiver = null;
let stopClient = null;
let managed = false;
function trace(event, details = {}) {
  const message = {event, atMs: Date.now(), ...details};
  if (managed) send(['frida:stdout', 'ICCOA_RELAY ' + JSON.stringify(message) + '\n']);
  else send(message);
}
function loadDex(encoded, name) {
  const blob = Java.use('android.util.Base64').decode(encoded, 0);
  const parent = Java.use('android.app.ActivityThread').currentApplication().getClassLoader();
  const loader = Java.use('dalvik.system.InMemoryDexClassLoader').$new(
    Java.use('java.nio.ByteBuffer').wrap(blob), parent
  );
  return Java.ClassFactory.get(loader).use(name);
}
function bytes(value) { return Array.from(value ?? [], n => n & 255); }
function byteArray(value) { return Java.array('byte', value.map(n => n > 127 ? n - 256 : n)); }
function dispatch(fn) {
  Java.scheduleOnMainThread(() => {
    try { fn(); } catch (error) { trace('callbackError', {error: String(error), stack: error.stack}); }
  });
}
function loopbackAddress() {
  return Java.use('java.net.InetSocketAddress').$new('127.0.0.1', PORT);
}

function dial() {
  const socket = Java.retain(Java.use('java.nio.channels.SocketChannel').open());
  socket.configureBlocking(false);
  socket.connect(loopbackAddress());
  return socket;
}

function channel(socket, receive, closed) {
  const Buffer = Java.use('java.nio.ByteBuffer');
  const input = Java.retain(Buffer.allocate(4096));
  const output = [];
  let pending = '';
  let timer;
  let stopped = false;
  function stop() {
    if (stopped) return;
    stopped = true;
    clearInterval(timer);
    socket.close();
    closed();
  }
  const post = value => {
    const line = JSON.stringify(value) + '\n';
    output.push(Java.retain(Buffer.wrap(byteArray(Array.from(line, c => c.charCodeAt(0))))));
  };
  timer = setInterval(() => Java.performNow(() => {
    try {
      if (!socket.isConnected() && !socket.finishConnect()) return;
      while (output.length) {
        const front = output[0];
        socket.write(front);
        if (front.hasRemaining()) break;
        output.shift().$dispose();
      }
      input.clear();
      const length = socket.read(input);
      if (length < 0) { stop(); return; }
      if (!length) return;
      input.flip();
      const received = Java.array('byte', new Array(length).fill(0));
      input.get(received);
      pending += String.fromCharCode(...bytes(received));
      if (pending.length > 65536) throw new Error('Oversized GATT message');
      let end;
      while ((end = pending.indexOf('\n')) !== -1) {
        const msg = JSON.parse(pending.slice(0, end));
        pending = pending.slice(end + 1);
        receive(msg, post, stop);
      }
    } catch (error) {
      trace('socketIoError', {error: String(error), stack: error.stack});
      stop();
    }
  }), 20);
  post.close = stop;
  return post;
}

function serve(cfg) {
  const Device = Java.use('android.bluetooth.BluetoothDevice');
  const UUID = Java.use('java.util.UUID');
  let peripheral = null;
  let client = null;
  let acceptMessage = null;
  let sdkCallback = null;
  let services = null;
  let shareLink = null;
  let localAuthPending = false;

  function sameClient(device) {
    return client && device && String(device.getAddress()) === cfg.phoneAddress;
  }
  function recordServices(list) {
    const result = [];
    for (let i = 0; i < list.size(); ++i) {
      const service = Java.cast(list.get(i), Java.use('android.bluetooth.BluetoothGattService'));
      const chars = service.getCharacteristics();
      const metadata = [];
      for (let j = 0; j < chars.size(); ++j) {
        const c = Java.cast(chars.get(j), Java.use('android.bluetooth.BluetoothGattCharacteristic'));
        metadata.push({id: String(c.getUuid()), handle: c.getInstanceId(), properties: c.getProperties(), permissions: c.getPermissions()});
      }
      result.push({id: String(service.getUuid()), type: service.getType(), chars: metadata});
    }
    return result;
  }
  function receive(msg, post) {
    if (msg.type === 'ping' && msg.token === cfg.token) {
      post({type: 'pong', seq: msg.seq});
      trace('sdkLoopbackPing', {seq: msg.seq});
      return;
    }
    if (msg.type === 'hello') {
      if (acceptMessage || msg.token !== cfg.token) return;
      trace('sdkGattHello', {elapsedMs: Date.now() - msg.started});
      if (!peripheral || !sdkCallback || !services) throw new Error('Receiver GATT service unavailable');
      const hasType = Device.$init.overloads.some(o =>
        o.argumentTypes.length === 2 && o.argumentTypes[0].className === 'java.lang.String');
      client = Java.retain(hasType ? Device.$new(cfg.phoneAddress, 0) : Device.$new(cfg.phoneAddress));
      acceptMessage = post;
      sdkCallback.onConnectionStateChange(client, 0, 2);
      trace('sdkGattConnected', {services: services.length});
      post({type: 'ready', started: msg.started});
      return;
    }
    if (!sameClient(client) || post !== acceptMessage) return;
    if (msg.type === 'mtu') {
      sdkCallback.onMtuChanged(client, msg.value);
      post({type: 'mtuAck', value: msg.value});
    } else if (msg.type === 'discover') {
      trace('sdkGattDiscover', {services: services.length});
      post({type: 'services', services});
    } else if (msg.type === 'write') {
      let characteristic = null;
      for (const s of services) {
        if (s.chars.some(c => c.id === msg.uuid)) {
          characteristic = peripheral.getService(UUID.fromString(s.id)).getCharacteristic(UUID.fromString(msg.uuid));
          break;
        }
      }
      if (!characteristic) throw new Error('Unknown GATT characteristic');
      trace('sdkGattWrite', {length: msg.value.length});
      if (cfg.localLoopback && msg.uuid === '2abcc850-9935-4f8a-ba84-123456789101') {
        const wifi = Java.retain(shareLink.mWifiP2p.value);
        shareLink.mWifiP2p.value = null;
        try {
          sdkCallback.onCharacteristicWriteRequest(client, msg.id, characteristic, false, true, 0, byteArray(msg.value));
        } finally {
          shareLink.mWifiP2p.value = wifi;
        }
        shareLink.startAuthService('127.0.0.1');
        localAuthPending = true;
      } else {
        sdkCallback.onCharacteristicWriteRequest(client, msg.id, characteristic, false, true, 0, byteArray(msg.value));
      }
      trace('sdkGattWriteHandled');
    } else if (msg.type === 'close') {
      sdkCallback.onConnectionStateChange(client, 0, 0);
      client = null;
      acceptMessage = null;
      post.close();
    }
  }

  const receiver = Java.use('com.share.connect.ble.BluetoothLeService');
  Java.choose(receiver.$className, {
    onMatch: instance => {
      const server = instance.mBluetoothGattServer.value;
      const callback = instance.mGattServerCallback.value;
      if (server && callback) {
        peripheral = Java.retain(server);
        sdkCallback = Java.retain(callback);
        services = recordServices(server.getServices());
        if (services.length) return 'stop';
      }
    },
    onComplete: () => {
      if (!services?.length) { trace('serverSetupError', {error: 'No active receiver GATT server'}); return; }
      try {
        let oldConnectType = null;
        if (cfg.p2pOnly) {
          Java.choose('com.share.connect.ShareLinkService', {
            onMatch(instance) {
              shareLink = Java.retain(instance);
              return 'stop';
            },
            onComplete() {},
          });
          if (!shareLink) throw new Error('Receiver ShareLink service unavailable');
          oldConnectType = shareLink.mSupportConnectType.value;
          shareLink.mSupportConnectType.value = 1001;
          trace('receiverP2pOnly', {oldConnectType});
        }
        const GattApi = Java.use('android.bluetooth.IBluetoothGatt');
        const original = Java.retain(peripheral.mService.value);
        const ServerTransport = loadDex(cfg.serverDex, 'local.iccoa.probe.ServerTransport');
        const bridge = Java.retain(ServerTransport.$new(
          original, GattApi.class, cfg.phoneAddress
        ));
        peripheral.mService.value = Java.cast(bridge.proxy.value, GattApi);
        const listener = Java.retain(Java.use('java.nio.channels.ServerSocketChannel').open());
        listener.configureBlocking(false);
        listener.bind(loopbackAddress());
        const connections = new Set();
        const timer = setInterval(() => Java.performNow(() => {
          if (localAuthPending) {
            const auth = shareLink.mAuthService.value;
            if (auth && auth.mServerBound.value) {
              localAuthPending = false;
              shareLink.onWirelessConnected();
              shareLink.mBluetoothLe.value.notifyServerInfo(
                'CarPToCar-local', 'local-transport', '02:00:00:00:00:00', 2412, 1001
              );
              trace('receiverLocalAuthBound', {transport: 'loopback'});
            }
          }
          let event;
          while ((event = bridge.poll()) !== null) {
            if (!acceptMessage) continue;
            const type = String(event.kind.value);
            if (type === 'response') {
              const status = event.status.value;
              acceptMessage({type, id: event.number.value, status});
              trace('sdkGattResponse', {status});
            } else if (type === 'notification') {
              const spec = services.flatMap(s => s.chars).find(c => c.handle === event.number.value);
              if (!spec) continue;
              const value = bytes(event.value.value);
              acceptMessage({type, uuid: spec.id, value});
              trace('sdkGattNotification', {length: value.length});
              if (client) sdkCallback.onNotificationSent(client, 0);
            } else {
              acceptMessage({type});
            }
          }
          const accepted = listener.accept();
          if (!accepted) return;
          accepted.configureBlocking(false);
          const socket = Java.retain(accepted);
          let post;
          post = channel(socket, receive, () => {
            connections.delete(post);
            if (post === acceptMessage && client) {
              sdkCallback.onConnectionStateChange(client, 0, 0);
              client = null;
              acceptMessage = null;
            }
          });
          connections.add(post);
          trace('sdkLoopbackAccepted');
        }), 20);
        stopReceiver = () => {
          clearInterval(timer);
          peripheral.mService.value = original;
          if (shareLink) shareLink.mSupportConnectType.value = oldConnectType;
          listener.close();
          for (const post of connections) post.close();
          stopReceiver = null;
        };
        trace('serverListening', {serviceCount: services.length, port: PORT});
      } catch (error) { trace('serverSetupError', {error: String(error), stack: error.stack}); }

    }
  });
}

function resetRelay() {
  Java.performNow(() => {
    if (stopReceiver) stopReceiver();
    if (stopClient) stopClient();
  });
  trace('relayStopped');
}

rpc.exports = {
  init(stage, cfg) { configure(cfg); },
  reset: resetRelay,
  onFridaStdin(line) { if (String(line).trim() === 'reset') resetRelay(); },
};

function connect(cfg) {
  const Gatt = Java.use('android.bluetooth.BluetoothGatt');
  const Service = Java.use('android.bluetooth.BluetoothGattService');
  const Characteristic = Java.use('android.bluetooth.BluetoothGattCharacteristic');
  const List = Java.use('java.util.ArrayList');
  const UUID = Java.use('java.util.UUID');
  const System = Java.use('java.lang.System');
  const GattApi = Java.use('android.bluetooth.IBluetoothGatt');
  const ClientTransport = loadDex(cfg.clientDex, 'local.iccoa.probe.ClientTransport');
  let gatt = null;
  let id = null;
  let binderCallback = null;
  let peer = null;
  let outgoing = null;
  let services = null;
  let mtuRequested = false;
  let discoveryRequested = false;
  const chars = new Map();
  const handles = new Map();
  const pending = new Map();
  let next = 0;
  let localAuthStarted = false;
  function deliverLocalServerInfo(msg, characteristic) {
    const info = JSON.parse(String.fromCharCode(...msg.value));
    if (localAuthStarted || info.type !== 1001) return false;
    let address = '127.0.0.1';
    if (!cfg.localLoopback) {
      const NetworkInterface = Java.use('java.net.NetworkInterface');
      const IPv4 = Java.use('java.net.Inet4Address');
      const interfaces = NetworkInterface.getNetworkInterfaces();
      address = null;
      while (interfaces.hasMoreElements() && !address) {
        const iface = Java.cast(interfaces.nextElement(), NetworkInterface);
        if (!String(iface.getName()).startsWith('p2p')) continue;
        const addresses = iface.getInetAddresses();
        while (addresses.hasMoreElements()) {
          const candidate = addresses.nextElement();
          if (IPv4.class.isInstance(candidate)) {
            address = String(Java.cast(candidate, IPv4).getHostAddress());
            break;
          }
        }
      }
    }
    if (!address) throw new Error('No IPv4 address on the receiver-created P2P group');
    const ShareLink = Java.use('com.oplus.share.connect.ShareLinkService');
    let active = null;
    Java.choose(ShareLink.$className, {
      onMatch(instance) {
        if (!instance.B()) return;
        active = Java.retain(instance);
        return 'stop';
      },
      onComplete() {},
    });
    if (!active) throw new Error('Active native ICCOA ShareLink service unavailable');
    const field = ShareLink.class.getDeclaredField('w');
    const observerField = ShareLink.class.getDeclaredField('e0');
    field.setAccessible(true);
    observerField.setAccessible(true);
    const wifi = Java.retain(field.get(active));
    const observer = Java.retain(Java.cast(observerField.get(active), Java.use('com.oplus.share.connect.ShareLinkService$f')));
    localAuthStarted = true;
    // Let the genuine ServerInfo reach Car+, while preventing its physical
    // Wi-Fi client step from deleting the receiver's group on this same phone.
    field.set(active, null);
    try {
      binderCallback.onNotify(peer, characteristic.getInstanceId(), byteArray(msg.value));
    } finally {
      setTimeout(() => dispatch(() => {
        field.set(active, wifi);
        trace('nativeLocalAuthStart', {transport: cfg.localLoopback ? 'loopback' : 'receiver-owned local IPv4'});
        observer.L4(address, address, String(active.B()), false);
      }), 160);
    }
    return true;
  }
  function isLoopback(instance) { return id !== null && System.identityHashCode(instance) === id; }
  function requestMtu(value) {
    if (mtuRequested) return;
    mtuRequested = true;
    outgoing({type: 'mtu', value});
  }
  function requestServices() {
    if (discoveryRequested) return;
    discoveryRequested = true;
    outgoing({type: 'discover'});
  }
  function readMessage(msg) {
    if (msg.type === 'ready') {
      trace('nativeGattConnected', {elapsedMs: Date.now() - msg.started});
      binderCallback.onClientConnectionState(0, true, peer);
      // ColorOS requests MTU synchronously inside this callback. Relay it if the
      // framework call runs on the same injected stack without entering the hook.
      requestMtu(512);
    } else if (msg.type === 'mtuAck') {
      trace('nativeGattMtuAck', {mtu: msg.value});
      binderCallback.onConfigureMTU(peer, msg.value, 0);
      requestServices();
    } else if (msg.type === 'services') {
      services = Java.retain(List.$new());
      for (const s of msg.services) {
        const service = Service.$new(UUID.fromString(s.id), s.type);
        for (const spec of s.chars) {
          const characteristic = Characteristic.$new(UUID.fromString(spec.id), spec.properties, spec.permissions);
          if (characteristic.setInstanceId) characteristic.setInstanceId(spec.handle);
          else characteristic.mInstance.value = spec.handle;
          service.addCharacteristic(characteristic);
          const saved = Java.retain(characteristic);
          chars.set(spec.id, saved);
          handles.set(spec.handle, saved);
        }
        services.add(service);
      }
      trace('nativeGattServices', {services: msg.services.length, characteristics: handles.size});
      binderCallback.onSearchComplete(peer, services, 0);
    } else if (msg.type === 'response') {
      const write = pending.get(msg.id);
      pending.delete(msg.id);
      if (write) binderCallback.onCharacteristicWrite(peer, msg.status, write.handle, byteArray(write.value));
    } else if (msg.type === 'notification') {
      const characteristic = chars.get(msg.uuid);
      if (!characteristic) throw new Error('Receiver notified an unknown characteristic');
      trace('nativeGattNotification', {length: msg.value.length});
      if (cfg.localAuth && deliverLocalServerInfo(msg, characteristic)) return;
      binderCallback.onNotify(peer, characteristic.getInstanceId(), byteArray(msg.value));
    } else if (msg.type === 'remoteClose') {
      binderCallback.onClientConnectionState(0, false, peer);
    }
  }
  function initiate(instance, cb) {
    if (stopClient) stopClient();
    mtuRequested = false;
    discoveryRequested = false;
    localAuthStarted = false;
    chars.clear();
    handles.clear();
    pending.clear();
    gatt = Java.retain(instance);
    id = System.identityHashCode(instance);
    binderCallback = Java.retain(Java.cast(cb, Java.use('android.bluetooth.IBluetoothGattCallback')));
    peer = Java.retain(instance.getDevice());
    const original = Java.retain(gatt.mService.value);
    const bridge = Java.retain(ClientTransport.$new(original, GattApi.class, cfg.address));
    gatt.mService.value = Java.cast(bridge.proxy.value, GattApi);
    const bridgeTimer = setInterval(() => Java.performNow(() => {
      if (!outgoing) return;
      let event;
      while ((event = bridge.poll()) !== null) {
        const kind = String(event.kind.value);
        if (kind === 'mtu') requestMtu(event.number.value);
        else if (kind === 'discover') requestServices();
        else if (kind === 'write') {
          const characteristic = handles.get(event.number.value);
          if (characteristic) queueWrite(characteristic, bytes(event.value.value));
        }
      }
    }), 15);
    stopClient = () => {
      clearInterval(bridgeTimer);
      instance.mService.value = original;
      if (outgoing) outgoing.close();
      stopClient = null;
    };
    trace('nativeGattIntercepted');
    setImmediate(() => Java.performNow(() => {
      try {
        const socket = dial();
        outgoing = channel(socket, msg => dispatch(() => readMessage(msg)), () => {});
        trace('nativeGattHello');
        outgoing({type: 'hello', token: cfg.token, started: Date.now()});
      } catch (error) {
        trace('nativeGattSocketError', {error: String(error)});
        dispatch(() => binderCallback.onClientConnectionState(257, false, peer));
      }
    }));
  }

  const Device = Java.use('android.bluetooth.BluetoothDevice');
  const Proxy = Java.use('android.bluetooth.IBluetoothGatt$Stub$Proxy');
  let candidateGatt = null;
  let candidateBinder = null;
  let isStarting = false;
  const matches = address => String(address).toUpperCase() === cfg.address.toUpperCase();
  for (const method of Device.connectGatt.overloads) {
    const index = method.argumentTypes.findIndex(a => a.className === 'android.bluetooth.BluetoothGattCallback');
    if (index === -1) continue;
    method.implementation = function (...args) {
      if (!matches(this.getAddress())) return method.call(this, ...args);
      const instance = method.call(this, ...args);
      if (instance) {
        candidateGatt = Java.retain(instance);
        trace('nativeGattObjectCaptured');
      }
      return instance;
    };
  }
  function startWhenReady(attempts = 30) {
    if (candidateGatt && candidateBinder) {
      initiate(candidateGatt, candidateBinder);
    } else if (attempts > 0) {
      setTimeout(() => dispatch(() => startWhenReady(attempts - 1)), 30);
    } else {
      trace('nativeGattCaptureError', {error: 'No GATT instance or callback after interception'});
    }
  }
  for (const method of Proxy.clientConnect.overloads) {
    method.implementation = function (...args) {
      const target = args.some((arg, index) => {
        const type = method.argumentTypes[index].className;
        if (type === 'java.lang.String') return matches(arg);
        if (type === 'android.bluetooth.BluetoothDevice') return arg && matches(arg.getAddress());
        return false;
      });
      if (!target) return method.call(this, ...args);
      if (!isStarting) {
        isStarting = true;
        candidateBinder = Java.retain(args[0]);
        trace('nativeGattBinderIntercepted', {signature: method.argumentTypes.map(a => a.className)});
        dispatch(startWhenReady);
      }
      if (method.returnType.className === 'boolean') return true;
      if (method.returnType.className === 'int') return 0;
    };
  }

  function targeted(args) { return Boolean(gatt && outgoing && args[1] && matches(args[1].getAddress())); }
  function queueWrite(characteristic, value) {
    const request = ++next;
    pending.set(request, {handle: characteristic.getInstanceId(), value});
    trace('nativeGattWrite', {length: value.length});
    outgoing({type: 'write', id: request, uuid: String(characteristic.getUuid()), value});
  }
  for (const name of ['refreshDevice','configureMTU','discoverServices','discoverServiceByUuid','registerForNotification','writeCharacteristic','writeDescriptor']) {
    for (const method of Proxy[name].overloads) {
      method.implementation = function (...args) {
        if (!targeted(args)) return method.call(this, ...args);
        trace('nativeGattBinder', {name});
        if (name === 'configureMTU') {
          const mtu = args[2];
          requestMtu(mtu);
        } else if (name === 'discoverServices' || name === 'discoverServiceByUuid') {
          requestServices();
        } else if (name === 'writeCharacteristic') {
          const characteristic = handles.get(args[2]);
          if (!characteristic) return 257;
          queueWrite(characteristic, bytes(args[5]));
          return 0;
        } else if (name === 'writeDescriptor') {
          gatt.mDeviceBusy.value = false;
          return 0;
        }
      };
    }
  }
  const discover = Gatt.discoverServices.overload();
  discover.implementation = function () {
    if (!isLoopback(this)) return discover.call(this);
    trace('nativeGattDiscover');
    requestServices();
    return true;
  };
  const list = Gatt.getServices.overload();
  list.implementation = function () { return isLoopback(this) ? services : list.call(this); };

  const mtu = Gatt.requestMtu.overload('int');
  mtu.implementation = function (value) {
    if (!isLoopback(this)) return mtu.call(this, value);
    trace('nativeGattMtu');
    requestMtu(value);
    return true;
  };
  const subscribe = Gatt.setCharacteristicNotification.overload('android.bluetooth.BluetoothGattCharacteristic', 'boolean');
  subscribe.implementation = function (characteristic, enable) {
    if (!isLoopback(this)) return subscribe.call(this, characteristic, enable);
    return true;
  };

  for (const write of Gatt.writeCharacteristic.overloads) {
    write.implementation = function (...args) {
      if (!isLoopback(this)) return write.call(this, ...args);
      const characteristic = args[0];
      const value = bytes(args.length === 1 ? characteristic.getValue() : args[1]);
      queueWrite(characteristic, value);
      return write.returnType.className === 'boolean' ? true : 0;
    };
  }
  const close = Gatt.close.overload();
  close.implementation = function () {
    if (!isLoopback(this)) return close.call(this);
    if (outgoing) outgoing({type: 'close'});
    outgoing = null;
    id = null;
    isStarting = false;
    candidateGatt = null;
    candidateBinder = null;
    mtuRequested = false;
    discoveryRequested = false;
    if (stopClient) stopClient();
    trace('nativeGattClosed');
    return close.call(this);
  };
  trace('clientHooksReady', {binderHooks: Proxy.clientConnect.overloads.length});
  let pingSeq = 0;
  function ping() {
    const seq = ++pingSeq;
    const started = Date.now();
    const test = channel(dial(), (msg, post, stop) => {
      if (msg.type !== 'pong' || msg.seq !== seq) return;
      trace('localTransportReady', {seq, elapsedMs: Date.now() - started});
      stop();
    }, () => {});
    trace('localTransportPing', {seq});
    test({type: 'ping', token: cfg.token, seq});
  }
  function waitPing() {
    recv('ping', () => {
      Java.performNow(ping);
      waitPing();
    });
  }
  ping();
  waitPing();
}

function configure(payload) {
  managed = payload.managed === true;
  Java.perform(() => {
  try {
    if (payload.port) PORT = payload.port;
    if (payload.role === 'receiver') serve(payload);
    else connect(payload);
  } catch (error) {
    trace('setupError', {error: String(error), stack: error.stack});
  }
  });
}
