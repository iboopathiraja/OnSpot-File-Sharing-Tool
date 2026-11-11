# OnSpot wire protocol

Every OnSpot app, Android or desktop, speaks this protocol. It is deliberately small, so writing a new client takes an afternoon.

## 1. Discovery

A device can be found in two ways. Clients should support both and merge the results by device name.

### mDNS / DNS‑SD

| Field        | Value                                         |
|--------------|-----------------------------------------------|
| Service type | `_onspot._tcp`                                |
| Service name | `OnSpot_<device name>`                        |
| Port         | the TCP port the device accepts transfers on  |
| TXT record   | `avatarIndex=<1..11>` (the profile picture)   |

When the user changes their picture, the TXT record is updated in place. Clients should listen for TXT updates and not only for new services.

### UDP fallback (port 45454)

Many routers and hotspots drop multicast, so devices also exchange small JSON datagrams on UDP port **45454**:

```json
{ "app": "onspot", "v": 1, "t": "probe", "id": "…", "name": "Pixel 8", "avatar": 5, "port": 50123 }
```

| `t`        | Meaning                                                                 |
|------------|-------------------------------------------------------------------------|
| `probe`    | "Who's there?" Sent to the subnet broadcast address and swept across the hosts of the subnet. |
| `announce` | The unicast reply to a probe, carrying the sender's name, avatar and TCP port. |
| `bye`      | The sender is going away. Remove it now instead of waiting for it to expire. |

Packets are at most 1 KB. Ignore anything whose `app` isn't `"onspot"` or whose `v` you don't understand. A device that hasn't been heard from for about 26 seconds is treated as offline.

## 2. Transfers

Each item is sent on **its own TCP connection** to the receiver's port. The sender writes the message and closes the connection.

All integers are big‑endian. Strings use Java's `DataOutputStream.writeUTF` format: a `uint16` byte length followed by *modified* UTF‑8 (NUL is written as `C0 80`, and characters outside the BMP as two 3‑byte surrogates).

```
int32   type
...     payload (depends on type)
```

| type | Name                 | Payload                                          |
|-----:|----------------------|--------------------------------------------------|
| 0    | `FILE`               | `UTF name`, `int64 size`, then `size` raw bytes  |
| 1    | `TEXT`               | `UTF text`                                       |
| 2    | `CLIPBOARD`          | `UTF text` (only accepted while sync is on)      |
| 3    | `CLIPBOARD_REQUEST`  | `UTF "request"`                                  |
| 4    | `CLIPBOARD_RESPONSE` | `UTF "true"` or `UTF "false"`                    |

Because of `writeUTF`, text and clipboard payloads are limited to **65,535 bytes** once encoded.

### Clipboard sync handshake

```
A ──CLIPBOARD_REQUEST──▶ B        B asks its user
A ◀─CLIPBOARD_RESPONSE── B        "true" turns sync on for both sides
A ◀──────CLIPBOARD──────▶ B       each side sends its clipboard when it changes
```

Either side can turn sync off at any time. After that it simply stops sending and ignores incoming `CLIPBOARD` messages.

## 3. Receiving files safely

These are not part of the wire format, but every OnSpot client follows them:

- Strip any path from the file name, and rename reserved Windows names such as `CON` or `NUL`.
- Never overwrite: if `photo.jpg` already exists, save as `photo (1).jpg`.
- Check free disk space against `size` before writing anything.
