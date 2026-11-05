# Configuration guide

ADMIN keeps its configuration in `config/admin/`, split one file per concern. This guide explains
the layout, how to change a value safely, and how to move a whole set of settings between servers.

- [1. Where the files live](#1-where-the-files-live)
- [2. Editing a file by hand](#2-editing-a-file-by-hand)
- [3. The unified settings screen](#3-the-unified-settings-screen)
- [4. Profiles](#4-profiles)
- [5. Import and export](#5-import-and-export)
- [6. Backups](#6-backups)
- [7. Automation and notifications](#7-automation-and-notifications)
- [8. Diagnostics](#8-diagnostics)
- [9. Secret handling](#9-secret-handling)

---

## 1. Where the files live

| File | Sections it owns |
| --- | --- |
| `general.json` | `configVersion`, `profile`, `general`, `announcements`, `maintenance`, `logging` |
| `moderation.json` | `moderation`, `permissions` |
| `anticheat.json` | `anticheat` |
| `antidupe.json` | `antidupe` |
| `chatbridge.json` | `chatBridge` |
| `remoteadmin.json` | `remoteAdmin` |
| `performance.json` | `performance`, `alerts` |
| `protection.json` | `protection` |
| `audit.json` | `audit` |
| `storage.json` | `storage` |
| `backups.json` | `backups` |
| `automation.json` | `automation` |
| `notifications.json` | `notifications` |
| `localchat.json` | proximity chat (written by the proximity service) |
| `profiles.json` | saved profiles (written by the settings service) |

The split is deliberate: a single edit does not mean scrolling past the whole schema, and a file
that fails to parse cannot take the others down with it.

The directory is created owner-only where the filesystem supports it, because `chatbridge.json`
and `remoteadmin.json` can hold credentials.

## 2. Editing a file by hand

Edit the file, then run `/adminmod reload` in game or restart the server. The loader:

- reads each file independently;
- moves a file that cannot be parsed aside as `<name>.broken` and keeps the previous defaults in
  memory, so one bad edit never stops the server;
- clamps an out-of-range number to the nearest valid value and reports the correction back to the
  sender of the reload command;
- leaves a blank secret as "not configured" rather than failing — the affected integration is
  simply disabled.

An older single-file `admin-mod.json` is migrated once on first start: it is read, split into the
module files and kept beside them as `admin-mod.json.migrated`.

## 3. The unified settings screen

Every setting above is also editable from the client's **Config** tab, which is generated from a
central catalogue instead of a hand-written form. The catalogue describes each setting once:

| Field | Meaning |
| --- | --- |
| Category | The group the setting appears under in the sidebar. |
| Type | `BOOLEAN`, `STRING`, `INTEGER`, `LONG`, `DECIMAL`, `ENUM`, `COLOR`, `STRING_LIST`. Picks the control: a toggle, a text box, a spinner, a slider, a dropdown, a colour swatch or a multi-line list. |
| Range / step | Bounds the value and drives the slider. An out-of-range value cannot be entered. |
| Options | The accepted values of an `ENUM`. |
| Unit | Shown beside the value (`ms`, `s`, `min`). |
| Description | The explanation shown under the label and in the tooltip. |
| Advanced | Hidden unless the "show advanced" toggle is on, so the common screen stays short. |
| Dangerous | Requires an explicit confirmation before it may be changed. |
| Restart required | Applying the value warns that it takes effect after a restart. |
| Secret | The value is never sent to the client — only whether it is set. |
| Depends on | Another setting that must be on for this one to matter. |

The same description drives the editor, the search box, the tooltips and the diagnostics page, so
there is one source of truth and no drift.

A setting that is missing a catalogue entry still works: the registry adds a conservative
placeholder (advanced, restart-required) and records a note so the gap is visible rather than
silent. A test asserts the catalogue is complete, so a new field with no entry fails the build
rather than shipping an undocumented control.

### Applying a change

Changes are staged in the tab and applied as a batch. The batch is validated in full, written into
a scratch copy of the configuration, and committed only when every value is accepted. If one value
is rejected the whole batch is refused and nothing is written, so the configuration is never left
half-updated. **Apply** commits the staged changes, **Cancel** discards them, and **Reset** on a
category restores every setting in it to its documented default.

Each accepted change is recorded with the actor's name and the previous value. **Rollback** restores
the last change.

## 4. Profiles

A profile is a named set of settings. **Profiles** in the dashboard can:

- **capture** the current settings under a new name;
- **preview** what applying a profile would change, as a before/after list;
- **apply** a profile;
- **export** it to JSON, or **import** one;
- **rename** or **delete** a custom profile.

Built-in presets (`performance`, `balanced`, `maximum`, plus the themed presets) are always listed
and cannot be deleted or renamed. Custom profiles are stored in `config/admin/profiles.json`.

A profile that names a setting the server does not have is not an error: the unknown path is
reported as a warning on import and skipped, so a profile from a newer build still applies what it
can.

## 5. Import and export

Export writes a versioned JSON document. A secret setting is omitted entirely, never written even
as a placeholder, so an exported profile cannot carry a credential:

```json
{
  "formatVersion": 1,
  "kind": "adminmod-profile",
  "name": "hardened",
  "description": "tighter limits for a public server",
  "values": {
    "chatBridge.queueCapacity": "1024",
    "chatBridge.publicChatOnly": "true"
  }
}
```

Because secrets are left out, a credential always has to be filled in by hand on the target server.
That is what makes a profile safe to paste into a ticket or commit to a private repo.

On import:

1. the document is parsed; its `kind` and `formatVersion` are checked, and a file from a newer
   build is refused rather than partially read;
2. every path is resolved against the catalogue and every value is validated;
3. an unknown path, a secret path or an out-of-range value is dropped with a warning rather than
   failing the whole import, because a profile from a different build may legitimately mention a
   setting this one no longer has;
4. only a structurally broken document is refused outright;
5. a pre-import backup is taken first when `backups.backupBeforeImport` is on;
6. the profile is stored, or applied immediately, depending on the request.

## 6. Backups

`backups.json` controls snapshots of the configuration directory:

| Field | Meaning |
| --- | --- |
| `enabled` | Master switch for automatic configuration backups. |
| `intervalHours` | Hours between scheduled backups. Zero disables the schedule. |
| `maxBackups` | How many to keep; the oldest is removed first. |
| `backupBeforeMigration` | Take one before the one-time split of an old `admin-mod.json`. |
| `backupBeforeImport` | Take one before applying an imported profile. |

Snapshots are written under the configuration directory's backup folder, named with the reason and
a timestamp. The **Config** tab's **Backup now** writes a manual snapshot and reports its name.

## 7. Automation and notifications

`automation.json` controls scheduled maintenance:

| Field | Meaning |
| --- | --- |
| `enabled` | Allow scheduled tasks to run. |
| `autoCleanupEnabled` | Sweep expired data on the cleanup interval. |
| `autoCleanupIntervalMinutes` | How often the sweep runs. |
| `autoBackupOnStart` | Take a configuration backup when the server starts. |
| `autoCompressEnabled` | Compress the audit archive on the schedule. |
| `restartRequiredNotice` | Warn in the dashboard when a change needs a restart. |

`notifications.json` controls ADMIN's own alerts:

| Field | Meaning |
| --- | --- |
| `enabled` | Send alerts at all. |
| `onProtectionTrigger`, `onModerationAction`, `onAuthFailure`, `onPerformanceAlert` | Which events raise an alert. |
| `minSeverity` | The lowest severity that is sent. |
| `toChat` | Show the alert in Minecraft chat. |
| `toBridge` | Send the alert to the chat bridge (requires the bridge to be enabled). |
| `quietSeconds` | The minimum gap between two alerts of the same kind. |

## 8. Diagnostics

The **Diagnostics** tab analyses the live configuration and reports findings with a severity, a
category and the path of the setting to fix. The same analysis runs at start-up and its result is
logged. Typical findings:

- a value outside its documented range;
- a required companion setting left off (the bridge enabled with no provider configured);
- a secret that is referenced but blank;
- a setting whose dependency is off, so it currently has no effect;
- a migration note from the loader (a value that was corrected).

A healthy default configuration produces no errors.

## 9. Secret handling

- A credential (webhook URL, bot token, Matrix access token, auth token, password) is marked
  `secret` in the catalogue.
- A secret is never sent to a client: the dashboard reports only whether it is set.
- A secret is never written into an export.
- A secret is never written to the log or the audit record.
- Changing a secret is a deliberate action, and clearing one through the dashboard is refused —
  edit the file and reload, or supply a new value.

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) if a value will not apply.
