#!/usr/bin/env python3
"""Validate launcher mappings and both committed icon resource sets."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
base = ROOT / "app/src/main/res"
manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
appfilter = ET.parse(base / "xml/appfilter.xml").getroot()
arrays = ET.parse(base / "values/arrays.xml").getroot()

names_node = arrays.find("string-array[@name='icon_names']")
labels_node = arrays.find("string-array[@name='icon_labels']")
assert names_node is not None and labels_node is not None
names = [node.text for node in names_node.findall("item")]
labels = [node.text for node in labels_node.findall("item")]
assert len(names) == len(labels) >= 40
assert len(names) == len(set(names)), "icon resource names must be unique"

mapping_drawables = {item.attrib["drawable"] for item in appfilter.findall("item")}
mapping_components = [item.attrib["component"] for item in appfilter.findall("item")]
assert mapping_drawables <= set(names), "appfilter contains unknown drawable names"
assert len(appfilter.findall("item")) >= 40, "appfilter mapping catalog is unexpectedly small"
assert len(mapping_components) == len(set(mapping_components)), "each Android component must map to only one icon"

for flavor in ("light", "dark"):
    drawables = ROOT / f"app/src/{flavor}/res/drawable-nodpi"
    present = {p.stem for p in drawables.glob("*.png")}
    missing = set(names) - present
    assert not missing, f"{flavor} flavor lacks mapped PNGs: {sorted(missing)}"
    assert "pack_launcher" in present
    assert {"iconmask", "iconupon", "iconback_one", "iconback_two", "iconback_three"} <= present

activity = manifest.find("application/activity")
assert activity is not None and activity.attrib.get("{http://schemas.android.com/apk/res/android}exported") == "true"
assert not any(item.tag.endswith("uses-permission") and item.attrib.get("{http://schemas.android.com/apk/res/android}name") == "android.permission.QUERY_ALL_PACKAGES" for item in manifest)
print(f"OK: {len(names)} named icons, {len(appfilter.findall('item'))} app mappings, both flavors complete.")
