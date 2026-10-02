# Keppo ride format (keppo.ride v1)

This is how **Keppo Moto** hands rides to **Keppo Journal**. The same files arrive two ways:
- on the **same phone**, through a read-only folder in Android's file picker
- **across devices**, through the rider's Google Drive backup

Keep this file identical in both repos. Change it only by **adding** fields: v1 readers ignore what they don't know.

## A ride

Each finished, real ride (never in-progress) is a folder. Demo rides are left out, except while Keppo Moto's testing switch (Profile › Developer › "Share demo rides with Keppo Journal") is on: then they appear too, with `"source": "DEMO"` in `ride.json`. The journal imports them like any ride and labels the entry "Demo". Drive backup never includes demo rides.

| File | What |
|---|---|
| `ride.json` | The ride: metadata, stats, events and the list of moments (below) |
| `route.png` | 1080×1080 route picture (speed-coloured line on Keppo ink `#0A0A0B`), for the journal entry's cover |
| moment files | Clips (`.mp4`), photos and thumbnails (`.jpg`), named exactly as in `ride.json` |
| `samples.jsonl.gz` | **Drive only.** Full telemetry for Moto's own restore; the journal can ignore it |

On Drive, moment files sit in a `moments/` subfolder of the ride folder. On the phone they sit directly in the ride folder.

### `ride.json`

```json
{
 "format": "keppo.ride",
 "v": 1,
 "id": "9b1f…",                         // stable ride id: the key for de-duplication
 "name": "Sunday at Nahan",
 "bikeId": "…", "bikeName": "KTM Duke 390",
 "status": "COMPLETED", "source": "PHONE",
 "startTimeMillis": 1790000000000, "endTimeMillis": 1790007200000, "lastUpdateMillis": 1790007200000,
 "stats": {
  "distanceM": 86400.5, "movingMillis": 7000000, "stoppedMillis": 200000,
  "maxSpeedMps": 31.2, "maxAccelG": 0.4, "maxBrakeG": -0.6, "peakG": 0.9,
  "maxLeftLeanDeg": 38.0, "maxRightLeanDeg": 31.0, "avgLeanDeg": 12.1,
  "stopCount": 2, "leftTurns": 5, "rightTurns": 4, "brakeEvents": 1, "accelEvents": 0, "leanEvents": 3
 },
 "events": [
  { "type": "HARD_BRAKE", "timeMillis": 1790000100000, "latitude": 28.61, "longitude": 77.21, "speedMps": 12.0, "value": -0.6 }
 ],
 "moments": [
  {
   "id": "…", "kind": "CLIP",              // CLIP or PHOTO
   "types": "HARD_BRAKE",                   // comma-separated event types; "" for photos
   "source": null,                          // null = cut around an event; "MANUAL" or "GPS_LOST" = filmed on purpose
   "timeMillis": 1790000100000, "clipStartMillis": 1790000090000, "durationMillis": 20000,
   "latitude": 28.61, "longitude": 77.21, "speedMps": 12.0, "peakValue": -0.6,
   "file": "a1b2.mp4", "thumbFile": "a1b2.jpg", "starred": false
  }
 ]
}
```

- **Units:** times are epoch milliseconds, distances metres, speeds metres per second, lean in degrees (negative = left), g in g.
- **Nulls:** unknown values are `null`, never `0`.
- **Missing files:** a moment's file can be missing for a while, for example a video still uploading to Drive. Show it as "on its way" and fetch it later.

## Same phone: the shared folder

- **What it is:** a `DocumentsProvider` in Keppo Moto, authority `com.keppo.moto.rides`. Its root is **"Keppo Moto"**, which appears in the system file picker only while Profile › Keppo Journal › "Share rides with Keppo Journal" is on.
- **Layout:** root → one folder per ride, named `yyyy-MM-dd <ride name>`. Use the id in `ride.json` as the key, never the folder name.
- **Read-only:** opening anything for writing fails.
- **Connecting:** the journal asks once with the system folder picker (Storage Access Framework `ACTION_OPEN_DOCUMENT_TREE`), with the initial location `content://com.keppo.moto.rides/document/root`, and keeps the persisted permission.

### Connecting in one tap

The journal connects without the file picker. Keppo Moto hands over the permission itself:

1. The journal starts an activity for a result: action com.keppo.action.SHARE_RIDES, package com.keppo.moto.
2. Keppo Moto answers only if the caller is com.keppo.journal. It asks "Share your rides with Keppo Journal?" (Allow / Not now). On Allow it turns on Profile › Keppo Journal › "Share rides with Keppo Journal", then finishes with RESULT_OK and a result intent whose data is the root tree URI content://com.keppo.moto.rides/tree/root (DocumentsContract.buildTreeDocumentUri("com.keppo.moto.rides", "root")), with flags FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION. On Not now it finishes with RESULT_CANCELED.
3. The journal keeps the persisted permission, exactly as if the picker had granted it.

For this, the provider declares android:grantUriPermissions="true", and its root supports tree access (Root.FLAG_SUPPORTS_IS_CHILD and isChildDocument). A Keppo Moto without this activity still works: the journal falls back to the picker above.

### "Ride saved" signal

When a ride is saved and sharing is on, Keppo Moto sends an explicit broadcast:

| | |
|---|---|
| package | `com.keppo.journal` |
| action | `com.keppo.action.RIDE_SAVED` |
| extra | `rideId` (String) |

The broadcast carries only the id. The journal reads the ride through the folder permission it already holds, so an app without that permission gets nothing.

## Across devices: Google Drive

- **Project and scope:** both apps use the "Keppo" Google Cloud project and the `drive.file` scope, so each can see what the other created.
- **Finding the folder:** look for the folder whose `appProperties` has `keppo = moto`. Never search by name; riders may rename or move it. Its parent is tagged `keppo = root`.
- **Layout:**
  ```
  Keppo/Moto/
    manifest.json          rides in the backup; deleted ones have "deletedAt"
    settings.json, garage/ Moto's own restore data (journal: ignore)
    rides/<rideId>/ride.json, route.png, samples.jsonl.gz, moments/<files>
  ```
- **`manifest.json`:** `rides.<id>.deletedAt` set means the rider deleted that ride in Moto. The journal **keeps** entries it already imported, and doesn't import new ones that are marked deleted.
- **Noticing changes:** use the Drive Changes API (`changes.getStartPageToken`, then `changes.list`) rather than re-listing everything.

## De-duplication

A ride is identified only by `ride.json` → `id`. Whichever path delivers it first creates the journal entry; the other path then only fills in missing moment files. Deleting in Moto never deletes from the journal, and a re-sync never overwrites what the rider wrote in the journal.
