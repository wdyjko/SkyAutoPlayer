#!/usr/bin/env python3
"""W26: rebuild the bundled sheet assets from the *device* library.

The device library (``files/songs/<id>.json`` + ``index.json``) is now the single
source of truth; the old ``/sdcard/skyMusicAuto`` pipeline is retired. See
``tools/legacy_build_bundled_sheets_skymusicaudit.py`` for the history.

Two things matter here:

1. **Asset names are reused** whenever an entry matches the old manifest by
   ``(title, durationMs)``. Those names are the tombstone keys in
   ``shared_prefs/deleted_bundled.xml`` and the idempotency key of
   ``BundledSheetSeeder.alreadyPresent``, so renaming them would resurrect
   deleted songs and re-import everything.
2. **durationUs must survive the round trip exactly.** The app dedupes the
   promoted library by ``(title, durationUs)`` parsed back out of these files,
   so the generated SkyStudio document has to reproduce the original duration
   to the microsecond.

Idempotent: re-running produces byte-identical output.

    python tools/bundle_from_device_library.py \
        --index  C:/temp/library_dump/index.json \
        --songs  C:/temp/library_dump/songs \
        --old-manifest tools/bundled_sheets_manifest.json \
        --assets app/src/main/assets/bundled_sheets \
        --manifest tools/bundled_sheets_manifest.json
"""

import argparse
import json
import os
import re
import shutil
import sys

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

EXPECTED_COUNT = 707
MAX_STEM = 60
ILLEGAL = re.compile(r'[\\/:*?"<>|\x00-\x1f]')


def sanitize(title, fallback="sheet"):
    """Windows-safe file stem, mirroring the legacy script's rules."""
    stem = ILLEGAL.sub("_", title).strip().rstrip(".")
    stem = stem[:MAX_STEM].rstrip()
    return stem or fallback


def log(message):
    print(message)


def load_old_names(path):
    """(title, durationMs) -> assetName plus the highest NNNN prefix seen.

    Reads either manifest format: the legacy one stores ``durationMs``, this
    script's own output stores ``durationUs``. Being able to read both keeps the
    file usable as a migration table after it has been rewritten once.
    """
    if not os.path.isfile(path):
        log(f"old manifest not found ({path}) - every asset gets a new name")
        return {}, 0
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    names = {}
    highest = 0
    for item in data.get("included", []):
        if "durationMs" in item:
            duration_ms = item["durationMs"]
        else:
            duration_ms = int(round(int(item.get("durationUs", 0)) / 1000.0))
        names[(item.get("title"), duration_ms)] = item.get("assetName")
        match = re.match(r"^(\d{4})_", item.get("assetName") or "")
        if match:
            highest = max(highest, int(match.group(1)))
    return names, highest


def to_sky_studio(entry, timeline, warnings):
    """Internal timeline -> SkyStudio document the app's importer already reads."""
    title = timeline.get("title") or entry.get("title") or ""
    target_us = int(entry.get("durationUs", 0))
    notes = []
    # time -> the original atUs that owns that millisecond slot. Two notes may
    # share a slot only when they come from the same atUs (a real chord);
    # otherwise the importer would fold them into one chord and change the song.
    owner = {}
    shifted = 0
    for event in timeline.get("events", []):
        at_us = int(event.get("atUs", 0))
        hold_us = int(event.get("holdUs", 0))
        time_ms = int(round(at_us / 1000.0))
        duration_ms = max(1, int(round(hold_us / 1000.0)))
        keys = list(event.get("keys", []))
        for key in keys:
            slot = time_ms
            while slot in owner and owner[slot] != at_us:
                slot += 1
            if slot != time_ms:
                shifted += 1
                warnings.append({
                    "title": title,
                    "atUs": at_us,
                    "shiftedToMs": slot,
                    "reason": "ms collision with a different event",
                })
            owner[slot] = at_us
            notes.append({"time": slot, "key": f"1Key{int(key)}", "duration": duration_ms})

    # The app compares (title, durationUs) to decide whether a library row can be
    # promoted instead of re-imported, so the round trip has to be exact.
    exact = True
    if notes:
        def end_us(note):
            return note["time"] * 1000 + note["duration"] * 1000

        longest = max(notes, key=end_us)
        current = end_us(longest)
        if current != target_us:
            exact = False
            delta_ms = int(round((target_us - current) / 1000.0))
            longest["duration"] = max(1, longest["duration"] + delta_ms)
            exact = end_us(longest) == target_us
            if not exact:
                warnings.append({
                    "title": title,
                    "durationUs": target_us,
                    "producedUs": end_us(longest),
                    "reason": "duration is not reproducible from whole milliseconds",
                })

    return {"name": title, "author": "", "songNotes": notes}, exact


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", required=True)
    parser.add_argument("--songs", required=True)
    parser.add_argument("--old-manifest", default=os.path.join(PROJECT_ROOT, "tools", "bundled_sheets_manifest.json"))
    parser.add_argument("--tombstones", default=None,
                        help="optional file with one deleted asset name per line; assert none is regenerated")
    parser.add_argument("--assets", default=os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "bundled_sheets"))
    parser.add_argument("--manifest", default=os.path.join(PROJECT_ROOT, "tools", "bundled_sheets_manifest.json"))
    args = parser.parse_args()

    old_names, highest_index = load_old_names(args.old_manifest)
    old_asset_names = set(old_names.values())

    with open(args.index, encoding="utf-8") as handle:
        index = json.load(handle)
    entries = index.get("entries", [])
    log(f"device library: {len(entries)} entries")

    # Fresh names continue after the highest NNNN prefix already in use. The
    # bundled rows carry their own asset name in sourceName, so the numbering
    # stays stable even when no previous manifest is available.
    for entry in entries:
        match = re.match(r"^(\d{4})_", entry.get("sourceName") or "")
        if match:
            highest_index = max(highest_index, int(match.group(1)))

    if os.path.isdir(args.assets):
        shutil.rmtree(args.assets)
    os.makedirs(args.assets, exist_ok=True)

    included = []
    warnings = []
    reused = 0
    renamed = 0
    skipped_empty = 0
    duration_fixed = 0
    next_index = highest_index + 1
    used_names = set()

    for entry in entries:
        song_id = entry.get("id")
        path = os.path.join(args.songs, f"{song_id}.json")
        if not os.path.isfile(path):
            log(f"  !! missing song file for {song_id} ({entry.get('title')})")
            return 2
        with open(path, encoding="utf-8") as handle:
            timeline = json.load(handle)
        if not timeline.get("events"):
            skipped_empty += 1
            continue

        document, exact = to_sky_studio(entry, timeline, warnings)
        if not exact:
            duration_fixed += 1

        # Naming, in order of confidence:
        #  1. a bundled row's own sourceName IS the asset file it was imported
        #     from - the exact key `alreadyPresent` and the tombstones use, and
        #     unambiguous even when two bundled songs share title + duration
        #     (5 such pairs exist today);
        #  2. otherwise the previous manifest's (title, durationMs) lookup;
        #  3. otherwise a fresh name after the highest NNNN prefix ever seen.
        own = entry.get("sourceName") or ""
        key = (entry.get("title"), int(round(int(entry.get("durationUs", 0)) / 1000.0)))
        asset_name = None
        if entry.get("origin") == "BUNDLED" and own.endswith(".txt") \
                and not ILLEGAL.search(own[:-4]) and own not in used_names:
            asset_name = own
        if asset_name is None:
            candidate = old_names.get(key)
            if candidate and candidate not in used_names:
                asset_name = candidate
        is_reused = asset_name is not None
        if is_reused:
            reused += 1
        else:
            while True:
                asset_name = f"{next_index:04d}_{sanitize(entry.get('title') or 'sheet')}.txt"
                next_index += 1
                if asset_name not in used_names:
                    break
            renamed += 1
        used_names.add(asset_name)

        text = json.dumps(document, ensure_ascii=False, separators=(",", ":"))
        with open(os.path.join(args.assets, asset_name), "w", encoding="utf-8-sig", newline="\n") as handle:
            handle.write(text)

        included.append({
            "assetName": asset_name,
            "title": document["name"],
            "durationUs": int(entry.get("durationUs", 0)),
            "eventCount": len(document["songNotes"]),
            "sourceId": song_id,
            "renamedFromOldManifest": is_reused,
        })

    # ── self checks (non-zero exit on failure) ────────────────────────────
    problems = []
    tombstones = set()
    if args.tombstones and os.path.isfile(args.tombstones):
        with open(args.tombstones, encoding="utf-8") as handle:
            tombstones = {line.strip() for line in handle if line.strip()}
    if len(included) != EXPECTED_COUNT:
        problems.append(f"includedCount={len(included)}, expected {EXPECTED_COUNT}")
    names = [item["assetName"] for item in included]
    if len(set(names)) != len(names):
        problems.append("duplicate asset names")
    revived = sorted(tombstones & set(names))
    if revived:
        problems.append(f"{len(revived)} deleted (tombstoned) assets would be regenerated: {revived[:3]}")
    for name in names:
        stem = name[:-4] if name.endswith(".txt") else name
        if ILLEGAL.search(stem) or stem != stem.strip().rstrip("."):
            problems.append(f"asset name is not Windows-safe: {name}")
    by_key = {(e.get("title"), int(e.get("durationUs", 0))) for e in entries}
    unmatched = [item for item in included if (item["title"], item["durationUs"]) not in by_key]
    if unmatched:
        problems.append(f"{len(unmatched)} assets whose (title, durationUs) is not in index.json")

    # Round-trip check: parse each asset exactly the way SkyJsonImporter does
    # (title from "name", duration = max(time*1000 + duration*1000)) and require
    # the result to equal the library row. This is the offline proof that the
    # app's (title, durationUs) promotion match will fire.
    for item in included:
        with open(os.path.join(args.assets, item["assetName"]), encoding="utf-8-sig") as handle:
            document = json.load(handle)
        produced = 0
        for note in document.get("songNotes", []):
            produced = max(produced, int(note["time"]) * 1000 + int(note["duration"]) * 1000)
        if document.get("name") != item["title"] or produced != item["durationUs"]:
            problems.append(
                f"round trip mismatch for {item['assetName']}: "
                f"{document.get('name')!r}/{produced} vs {item['title']!r}/{item['durationUs']}"
            )

    with open(args.manifest, "w", encoding="utf-8", newline="\n") as handle:
        json.dump({
            "generatedFrom": f"device library ({len(entries)} songs)",
            "includedCount": len(included),
            "reusedOldNames": reused,
            "newNames": renamed,
            "warnings": warnings,
            "included": included,
        }, handle, ensure_ascii=False, indent=1)

    total_bytes = sum(os.path.getsize(os.path.join(args.assets, n)) for n in names)
    log(f"included      : {len(included)}")
    log(f"reused names  : {reused}")
    log(f"new names     : {renamed} (next index was {highest_index + 1})")
    log(f"empty events  : {skipped_empty}")
    log(f"duration fixed: {duration_fixed}")
    log(f"ms collisions : {len([w for w in warnings if w.get('shiftedToMs')])}")
    log(f"tombstones    : {len(tombstones)} checked, {len(tombstones & set(names))} regenerated")
    log(f"assets bytes  : {total_bytes}")
    if problems:
        for problem in problems:
            log(f"SELF-CHECK FAILED: {problem}")
        return 1
    log("self-check    : OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
