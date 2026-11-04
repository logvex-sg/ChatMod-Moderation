# Installation guide

This guide installs both distributions. The server JAR and the client JAR are independent: a
server can run without any client mod, and a client can run without the server mod installed
(the dashboard simply reports that the server is not running the mod).

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.10 or newer |
| Fabric API | 0.116.17+1.21.1 |
| Java | 21 (runtime) |

Both the server and the client need Fabric API. The server also needs the server JAR; the client
also needs the client JAR. Installing the wrong side's JAR does nothing useful, because each
declares its environment in `fabric.mod.json` (`"environment": "server"` / `"client"`).

## Server installation

1. Install Fabric Loader for 1.21.1 on the server (the standard installer or your host's
   one-click Fabric option).
2. Place `fabric-api-0.116.17+1.21.1.jar` in the server's `mods/` directory.
3. Place `ADMIN-Server.jar` in the same `mods/` directory.
4. Start the server once. On the first start the mod:
   - writes `config/admin-mod.json`,
   - creates the credential folder (see below),
   - creates `config/adminmod-history.json` on the first moderation action.
5. Stop the server, edit `config/admin-mod.json`, then start it again — or use
   `/adminmod reload` while it is running.

The server must never load client classes. The build enforces this: the `check` task fails if any
class in the server distribution references `net.minecraft.client`.

## Client installation

1. Install Fabric Loader for 1.21.1 in your launcher.
2. Place `fabric-api-0.116.17+1.21.1.jar` and `ADMIN-Client.jar` in `.minecraft/mods/`.
3. Launch the game.

The dashboard opens with the **`]`** key. Rebind it under **Options → Controls → Key Binds →
ADMIN Mod**.

## The credential folder

On first start the server creates:

```
<server run dir>/H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme/
├── credential.dat
└── password.txt
```

- `credential.dat` is a PBKDF2 verifier. It cannot be turned back into the password.
- `password.txt` holds the generated password, because the client's authentication panel must be
  able to display it. The folder and both files are written with owner-only permissions; that
  directory is the security boundary, exactly as it is for an SSH private key.
- The folder name is stored in `remoteAdmin.vaultDirectory` and stays the same across restarts.

Read the password as the server owner:

```bash
cat <server run dir>/H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme/password.txt
```

## Connecting the client

1. Join the server with the client mod installed.
2. Open the dashboard with the configured key.
3. Enter the vault password in the authentication panel.
4. Once authenticated, the tabs populate.

The session ends when you disconnect, and you authenticate again on the next join. This is by
design: a session is bound to the server's nonce, so a stale session cannot be reused.

## Updating

Update the server and the client together. Both sides check the protocol version during the
handshake and refuse to communicate when they differ. Replacing one side only will leave the
dashboard unable to connect until the other is updated.

## Uninstalling

Remove the JAR from `mods/`. To also remove stored data:

- `config/admin-mod.json` — configuration
- `config/adminmod-history.json` — moderation history
- the credential folder — the vault and password

## Verifying the build

```bash
./gradlew clean build
```

This runs the test suite, verifies the server distribution references no client classes, and
writes both JARs to `build/libs/`.
