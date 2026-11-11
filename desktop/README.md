# OnSpot for Windows

The desktop half of [OnSpot](../README.md), built with Electron. It talks to the Android app over the shared [wire protocol](../docs/PROTOCOL.md).

```bash
npm install
npm start        # run the app
npm test         # unit tests for protocol, discovery and transfers
npm run dist     # build an NSIS installer into dist/
```

## Structure

| Path                 | What it does                                                        |
|----------------------|---------------------------------------------------------------------|
| `src/main.js`        | App window, tray, notifications, IPC to the UI                      |
| `src/core/protocol.js` | Message framing (`int32` type + Java `writeUTF` strings)          |
| `src/core/discovery.js` | Merges mDNS and UDP discovery into one device list               |
| `src/core/lan.js`    | UDP probe/announce on port 45454 for networks that block multicast  |
| `src/core/transfer.js` | TCP server and client, with progress and cancel                   |
| `src/core/engine.js` | Ties it together: queue, history, clipboard sync                    |
| `src/core/files.js`  | Safe file names, unique paths, free-space checks                    |
| `src/renderer/`      | The UI, in vanilla JS and CSS                                       |

`src/core` has no Electron dependency, so it runs and is tested in plain Node.

## Firewall

On first run, allow OnSpot on **Private** networks. Without that, other devices can't find this PC or send files to it.
