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
| `route.png` | 1080×1080 route picture: the speed-coloured line on a dark map, or on Keppo ink `#0A0A0B` until the map can be fetched (then replaced). The journal entry's cover |
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
2. Keppo Moto answers only if the caller is com.keppo.journal. It asks "Share your rides with Keppo Journal?" (Allow / Not now). On Allow it turns on Profile › Keppo Journal › "Share rides with Keppo Journal", then finishes with RESULT_OK and a result intent whose data is the root tree URI content://com.keppo.moto.rides/tree/root (DocumentsContract.buildTreeDocumentUri("com.keppo.moto.rides", "root")), with flags `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION | FLAG_GRANT_PREFIX_URI_PERMISSION`. The prefix flag is required: without it the grant covers only the root URI itself, and listing or reading anything inside the folder fails with a permission denial (the system picker grants the same three flags). On Not now it finishes with `RESULT_CANCELED`.
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

For deletions, this is superseded by "Reading rides in place" below.

## Reading rides in place (keppo.ride v1 additions)

Keppo Journal shows ride files straight from Keppo Moto's provider through its persisted tree grant; it does not copy them.

- Document ids are opaque. Take them only from listings: `root`, `ride:<rideId>`, `file:<rideId>/<file name>`, and `deleted.json`. They stay the same after an app update and after a restore from Google Drive. A reinstall without a restore removes the rides.
- Files are never moved or renamed after they're saved. Moments can be added to a ride later.
- `ride.json` and `route.png` are generated: their ids stay the same but their contents can change (a renamed ride, a better route picture). Re-read them when `COLUMN_LAST_MODIFIED` changes.
- Photo and clip files support thumbnails (`FLAG_SUPPORTS_THUMBNAIL`, `openDocumentThumbnail`); use them for lists.
- Keppo Moto never deletes files on its own. Low storage only stops new recordings. Files are removed only after 30 days in Keppo Moto's Recently deleted, or when the rider chooses "Delete now" there.

### Renames

Renaming a ride in Keppo Moto rewrites its `ride.json` (`name`) and sends `RIDE_SAVED` with that `rideId`. On Drive, the backup re-uploads `ride.json`. The ride's id and document ids don't change.

The journal updates the entry's title to the new `name`, unless the rider has renamed the entry in the journal; once they have, the journal keeps its own title. The journal only reads names: it never renames rides in Keppo Moto.

### Open a ride

| | |
|---|---|
| package | `com.keppo.moto` |
| action | `com.keppo.action.OPEN_RIDE` |
| extra | `rideId` (String) |

Any caller may use it. Opens that ride's page. A ride in Recently deleted opens Recently deleted. An unknown or permanently deleted ride opens the rides list with a short message.

### Delete a ride from the journal

| | |
|---|---|
| package | `com.keppo.moto` |
| action | `com.keppo.action.DELETE_RIDE` (start for a result) |
| extra | `rideId` (String) |

Keppo Moto answers only if the caller is `com.keppo.journal`. It shows its own confirmation ("Delete this ride? It moves to Recently deleted in Keppo Moto, with its N videos and M photos, for 30 days. After that it's gone for good."). It finishes with `RESULT_OK` if the ride moved to Recently deleted, otherwise `RESULT_CANCELED` (cancelled, unknown or already deleted ride, or any other caller). The journal treats `RESULT_OK` as done and hides the entry.

The journal can also remove a ride's entry from the journal only, without calling `DELETE_RIDE`. That ride stays in Keppo Moto and is never imported into the journal again, whatever happens to it in Keppo Moto later (including a restore).

### Deletions

Recently deleted belongs to Keppo Moto. The journal mirrors it and never restores a Moto ride itself.

When a ride is deleted in Keppo Moto (by the rider or through `DELETE_RIDE`), Moto sends an explicit broadcast:

| | |
|---|---|
| package | `com.keppo.journal` |
| action | `com.keppo.action.RIDE_DELETED` |
| extra | `rideId` (String) |

Because broadcasts can be missed, the provider root also holds `deleted.json` (document id `deleted.json`):

    { "format": "keppo.deleted", "v": 1,
      "rides":   { "<rideId>":   { "deletedAt": <millis>, "restoredAt": <millis or null>, "purgedAt": <millis or null> } },
      "moments": { "<momentId>": { "rideId": "<rideId>", "deletedAt": <millis>, "restoredAt": <millis or null>, "purgedAt": <millis or null> } } }

- Entries are never removed. Deleting again updates `deletedAt`; restoring sets `restoredAt`; the permanent deletion after 30 days (or "Delete now") sets `purgedAt`.
- A ride or moment is deleted while `deletedAt` is later than `restoredAt` (or `restoredAt` is null): the journal hides it. When restored, the journal shows it again with the rider's notes. When `purgedAt` is set, the journal deletes the entry (or the moment) for good; it never appears in the journal's Recently deleted.
- Restoring a ride from Keppo Moto's Recently deleted also sends `RIDE_SAVED` with that `rideId`.

### Restores from Google Drive

Rides restored from Drive appear in the folder like any other ride. After a restore Keppo Moto sends one `RIDE_SAVED` **without** a `rideId` extra, meaning "several rides changed, check everything". A restore brings back every ride in the rider's backup that isn't on this phone, but only when the rider chooses Restore.

### Without Keppo Moto

If Keppo Moto isn't installed, the journal can offer "Sync from Google Drive": it reads Keppo Moto's backup folder (the folder whose `appProperties` has `keppo = moto`, its `manifest.json` and `rides/<rideId>/…`) and copies the media into the journal. Rides marked `deletedAt` in the manifest are skipped.

## Breaks and pauses (keppo.ride v1 additions)

- `stats.breakMillis`: time on breaks, when the rider got off the bike during a long stop. It is not part of `movingMillis` or `stoppedMillis`. Riding time = `endTimeMillis − startTimeMillis − breakMillis`. Older rides don't have the field; read it as `0`.
- New `events[].type` values:
  - `BREAK_START`: `timeMillis` is when the stop began (a break is back-dated to it).
  - `BREAK_END`: the rider rode on.
  - `MANUAL_PAUSE` and `MANUAL_RESUME`: the rider paused or resumed by hand.

  As always, ignore event types you don't know.

## Moment types (keppo.ride v1 additions)

- `moments[].types` can include `VOICE`: a video filmed because the rider was speaking ("Start filming when I speak"). It can be combined with event types, e.g. `"HARD_BRAKE,VOICE"`.
- Event moments chained into one longer video keep `"source": null`. Their `durationMillis` can be longer than before (minutes, not seconds). `timeMillis` is the first event; `clipStartMillis` is the first frame.
- `moments[].topSpeedMps`: for clips, the fastest the bike went while the clip was filmed (m/s). `null` until Keppo Moto has worked it out (older clips get it the next time the ride is read).

## Transcripts (keppo.ride v1 additions)

- `moments[].transcript`: what the rider said in a talking clip (`types` includes `VOICE`), written in English letters (Hinglish as it's texted, e.g. "bhai ye road mast hai"). Made after the ride with the rider's own Gemini key, if they turned it on.
  - `""` means no clear speech.
  - `null` (or missing) means not transcribed (yet).
- When transcripts arrive, Keppo Moto sends `RIDE_SAVED` with the `rideId` (and `ride.json` changes), so the journal can add them.

## Reels (keppo.ride v1 additions)

Keppo Moto's Studio makes Reels from a ride's clips. The rider chooses which ones go to the journal (Send to Journal on a Reel, or Send all for a ride). Only those appear, on the phone only (not on Drive).

| File | What |
|---|---|
| `reel-<id>.mp4` | The Reel: 1080×1920 H.264 with AAC sound |
| `reel-<id>.jpg` | Its cover, 1080×1920; any text sits inside the middle 3:4 |

`ride.json` gains:

```json
 "reels": [
  { "file": "reel-3f9a1c2b-77d0.mp4", "cover": "reel-3f9a1c2b-77d0.jpg", "title": "Sunday at Nahan",
    "durationMs": 30500, "createdAt": 1790010000000, "postCaption": "Ep 3 · …" }
 ],
 "cover": "reel-3f9a1c2b-77d0.jpg"
```

- `reels` is newest first, and is left out when no Reel was sent.
- `cover`, when present, names the picture to use as the journal entry's cover instead of `route.png`. It is the cover of the Reel sent most recently (or the one the rider picked with "Use as Journal cover"). When that Reel is removed, `cover` moves to the newest other sent Reel, or disappears (back to `route.png`).
- A Reel that is changed and made again keeps its file names; re-read them when `COLUMN_LAST_MODIFIED` changes. Removed or deleted Reels simply stop being listed.
