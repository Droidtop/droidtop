"""Fail the build on framework types that droidtop's minSdk cannot load.

The bug this exists for (build 497, Android 9, droidtop set as HOME):

    java.lang.NoClassDefFoundError: android.window.OnBackAnimationCallback
        at android.app.AppComponentFactory.instantiateActivity

com.android.launcher3.Launcher had a method whose RETURN TYPE was an API-34
interface. A type in a class's own shape -- superclass, implemented
interface, field type, method parameter or return type -- is resolved by the
runtime when that class is LOADED. No "if (Build.VERSION.SDK_INT >= ...)"
can guard it, and no amount of @RequiresApi changes it: the class simply
cannot be loaded on an older device, so the activity could not start at all.

Lint's NewApi does not catch this. NewApi checks CALLS, which really are
guardable and which really are guarded all over this fork; the class-load
positions are exactly the ones it stays quiet about. Hence a separate check,
and hence one that looks ONLY at those positions -- it says nothing about
guarded calls, so it has no reason to produce the noise that would get it
switched off.

What it does: reads the dex of the built APK (what the device actually
loads, so nothing gets past it by being generated, desugared or shaded),
takes every class in a package this repo has source for, and looks up each
framework type in one of those five positions against the SDK's own
api-versions.xml. Anything newer than minSdk fails, unless the class is
named in class_load_api_allowlist.txt because its own load is behind a
version check (see com.android.launcher3.util.PredictiveBackAdapter for the
shape that takes).

Usage: check_class_load_api.py [apk] [--min-sdk N]
"""

import os
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-universal-debug.apk")
ALLOWLIST = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "class_load_api_allowlist.txt")
DEFAULT_MIN_SDK = 26

# The positions where an unloadable type is FATAL, and why only these three.
#
# ART resolves a class's superclass and its interfaces when the class is
# loaded: a missing one is always a NoClassDefFoundError, no exceptions.
# A return type is fatal in practice for a different reason -- the verifier
# has to prove that what a method returns is assignable to its declared
# return type, and it cannot prove anything about a type it could not
# resolve, so it rejects the class. That is precisely what killed
# com.android.launcher3.Launcher: a getter returning an API-34 interface.
#
# Parameter and field types are deliberately NOT fatal here. An override of
# a newer framework method (Drawable.setTintBlendMode, API 29) or a field
# only ever written under a version check is loadable and correct, and is
# how several classes in this fork are legitimately written; failing on
# those would make this check noise and get it switched off. Pass
# --all-positions to list them anyway when auditing by hand.
FATAL_POSITIONS = ("superclass", "interface", "return type")
ALL_POSITIONS = ("superclass", "interface", "return type", "parameter", "field")

# Source trees whose classes are NOT droidtop's to fix: reference-only copies
# that are never compiled into the APK anyway.
SKIP_SOURCE_DIRS = ("upstream-unused-reference", "/build/", "/.git/")


# --------------------------------------------------------------------------
# Which packages are ours
# --------------------------------------------------------------------------
def owned_packages(repo):
    packages = set()
    for base, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle",
                                                "upstream-unused-reference")]
        for name in files:
            if not (name.endswith(".java") or name.endswith(".kt")):
                continue
            path = os.path.join(base, name)
            if any(part in path for part in SKIP_SOURCE_DIRS):
                continue
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as handle:
                    for line in handle:
                        line = line.strip()
                        if line.startswith("package "):
                            packages.add(line[len("package "):].strip().strip(";").strip())
                            break
                        if line.startswith("import ") or line.startswith("class "):
                            break
            except OSError:
                pass
    packages.discard("")
    return packages


# --------------------------------------------------------------------------
# api-versions.xml
# --------------------------------------------------------------------------
def find_api_versions():
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"),
             "/opt/android-sdk", os.path.expanduser("~/Android/Sdk")]
    candidates = []
    for root in roots:
        if not root:
            continue
        platforms = os.path.join(root, "platforms")
        if not os.path.isdir(platforms):
            continue
        for entry in os.listdir(platforms):
            path = os.path.join(platforms, entry, "data", "api-versions.xml")
            if os.path.isfile(path):
                level = 0
                if entry.startswith("android-") and entry[len("android-"):].isdigit():
                    level = int(entry[len("android-"):])
                candidates.append((level, path))
    if not candidates:
        return None
    return sorted(candidates)[-1][1]


def load_api_levels(path):
    levels = {}
    root = ET.parse(path).getroot()
    minimum = int(root.get("min", "1"))
    for element in root.findall("class"):
        name = element.get("name")
        levels[name] = int(element.get("since", minimum))
    return levels


# --------------------------------------------------------------------------
# dex
# --------------------------------------------------------------------------
def uleb128(data, offset):
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7


def proto_parts(descriptor):
    """(return descriptor, [parameter descriptors]) of a proto descriptor."""
    types = []
    index = 0
    while index < len(descriptor):
        start = index
        while descriptor[index] == "[":
            index += 1
        if descriptor[index] == "L":
            index = descriptor.index(";", index) + 1
        else:
            index += 1
        types.append(descriptor[start:index])
    return types[0], types[1:]


def descriptor_types(descriptor):
    """Every class descriptor inside a type or proto descriptor."""
    found = []
    index = 0
    while index < len(descriptor):
        char = descriptor[index]
        if char == "L":
            end = descriptor.index(";", index)
            found.append(descriptor[index + 1:end])
            index = end + 1
        else:
            index += 1
    return found


def parse_dex(data):
    """Yield (class_name, {referenced class names}) for class-load positions."""
    (string_ids_size, string_ids_off, type_ids_size, type_ids_off,
     proto_ids_size, proto_ids_off, field_ids_size, field_ids_off,
     method_ids_size, method_ids_off, class_defs_size,
     class_defs_off) = struct.unpack_from("<12I", data, 56)

    string_offsets = struct.unpack_from("<%dI" % string_ids_size, data, string_ids_off)

    def string_at(index):
        offset = string_offsets[index]
        _, offset = uleb128(data, offset)
        end = data.index(b"\x00", offset)
        return data[offset:end].decode("utf-8", "replace")

    type_descriptors = [string_at(index) for index in
                        struct.unpack_from("<%dI" % type_ids_size, data, type_ids_off)]

    def proto_descriptor(index):
        return_type_idx, parameters_off = struct.unpack_from(
            "<2I", data, proto_ids_off + index * 12 + 4)
        descriptors = [type_descriptors[return_type_idx]]
        if parameters_off:
            count = struct.unpack_from("<I", data, parameters_off)[0]
            for position in range(count):
                descriptors.append(type_descriptors[struct.unpack_from(
                    "<H", data, parameters_off + 4 + position * 2)[0]])
        return "".join(descriptors)

    def field_type(index):
        type_idx = struct.unpack_from("<H", data, field_ids_off + index * 8 + 2)[0]
        return type_descriptors[type_idx]

    def method_proto(index):
        proto_idx = struct.unpack_from("<H", data, method_ids_off + index * 8 + 2)[0]
        return proto_descriptor(proto_idx)

    for index in range(class_defs_size):
        (class_idx, _access, superclass_idx, interfaces_off, _source,
         _annotations, class_data_off, _statics) = struct.unpack_from(
            "<8I", data, class_defs_off + index * 32)
        name = type_descriptors[class_idx]
        if not name.startswith("L"):
            continue
        refs = set()

        def add(position, descriptor):
            for found in descriptor_types(descriptor):
                refs.add((position, found))

        if superclass_idx != 0xFFFFFFFF:
            add("superclass", type_descriptors[superclass_idx])
        if interfaces_off:
            count = struct.unpack_from("<I", data, interfaces_off)[0]
            for position in range(count):
                type_idx = struct.unpack_from("<H", data, interfaces_off + 4 + position * 2)[0]
                add("interface", type_descriptors[type_idx])
        if class_data_off:
            offset = class_data_off
            counts = []
            for _ in range(4):
                value, offset = uleb128(data, offset)
                counts.append(value)
            for which, count in enumerate(counts):
                item_idx = 0
                for _ in range(count):
                    diff, offset = uleb128(data, offset)
                    item_idx += diff
                    _flags, offset = uleb128(data, offset)
                    if which < 2:
                        add("field", field_type(item_idx))
                    else:
                        _code_off, offset = uleb128(data, offset)
                        returns, parameters = proto_parts(method_proto(item_idx))
                        add("return type", returns)
                        for parameter in parameters:
                            add("parameter", parameter)
        yield name[1:-1], refs


# --------------------------------------------------------------------------
def load_allowlist(path):
    allowed = set()
    if not os.path.isfile(path):
        return allowed
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.split("#", 1)[0].strip()
            if line:
                allowed.add(line)
    return allowed


def main(argv):
    apk = DEFAULT_APK
    min_sdk = DEFAULT_MIN_SDK
    fatal = FATAL_POSITIONS
    rest = list(argv)
    while rest:
        item = rest.pop(0)
        if item == "--min-sdk":
            min_sdk = int(rest.pop(0))
        elif item == "--all-positions":
            fatal = ALL_POSITIONS
        else:
            apk = item

    if not os.path.isfile(apk):
        print("check_class_load_api: no APK at %s -- nothing to check." % apk)
        return 0

    api_versions = find_api_versions()
    if not api_versions:
        print("check_class_load_api: no api-versions.xml under the Android SDK; "
              "set ANDROID_SDK_ROOT. This check cannot run.")
        return 1
    levels = load_api_levels(api_versions)

    packages = owned_packages(REPO)
    allowed = load_allowlist(ALLOWLIST)

    failures = []
    checked = 0
    with zipfile.ZipFile(apk) as archive:
        names = [n for n in archive.namelist()
                 if n.startswith("classes") and n.endswith(".dex")]
        for name in sorted(names):
            for class_name, refs in parse_dex(archive.read(name)):
                package = class_name.rsplit("/", 1)[0].replace("/", ".")
                if package not in packages:
                    continue
                checked += 1
                dotted = class_name.replace("/", ".")
                outer = dotted.split("$", 1)[0]
                if dotted in allowed or outer in allowed:
                    continue
                for position, ref in sorted(refs):
                    if position not in fatal:
                        continue
                    since = levels.get(ref)
                    if since is not None and since > min_sdk:
                        failures.append((dotted, position, ref.replace("/", "."), since))

    print("check_class_load_api: %s" % os.path.basename(apk))
    print("  api-versions.xml : %s" % api_versions)
    print("  minSdk           : %d" % min_sdk)
    print("  owned packages   : %d" % len(packages))
    print("  classes checked  : %d" % checked)

    if failures:
        print("")
        print("Framework types too new to load on API %d, in class-load positions" % min_sdk)
        print("(superclass / interface / field type / method signature):")
        for dotted, position, ref, since in failures:
            print("  %s" % dotted)
            print("      %s: %s (API %d)" % (position, ref, since))
        print("")
        print("Move the type behind a class whose own load is version-checked, or")
        print("add that class to build-scripts/class_load_api_allowlist.txt with a")
        print("reason. See com.android.launcher3.util.PredictiveBackAdapter.")
        return 1

    print("  result           : no class-load references newer than API %d" % min_sdk)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
