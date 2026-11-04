"""The one mechanism behind droidtop's release channels (docs/SPEC.md 10b).

Seven subcommands, run by CI only except range and fragments (run by hand
while reviewing, and when cutting a real version):

  version-code COMMIT
      Prints the versionCode for COMMIT: the number of commits reachable from
      it. Every commit on main has strictly more ancestors than the commit
      before it, so the number only ever grows while main is never rewritten,
      and it belongs to the commit rather than to a workflow: renaming or
      splitting android-build.yml (whose run_number was the versionCode until
      2026-09-24) cannot reset it, and rebuilding a commit gives the same
      number. Counted through the GitHub API because CI checks out one commit
      of history, and the full history is a 200 MB fetch per run.

  changelog OUT VERSION_CODE [COMMIT]
      Writes OUT, this build's changelog.json (docs/SPEC.md 10b, "Every build
      carries its changelog"): every build's entry, newest first, this one's
      included. An entry is made from the commits since the previous
      published build (GitHub's compare API, since CI checks out one commit):
      each commit's Changelog trailer, else its subject, grouped Added /
      Changed / Fixed, internal commits folded into one line, and its
      Onboarding trailers (changelog_commits.py). A changelog.d fragment new
      in this build tells its own change instead, in better words. The
      history is the previous per-build release's own changelog.json, so the
      app can show every version a person skipped without the network.

  info OUTPUTS VERSION_CODE COMMIT
      Writes OUTPUTS/release-info.json (OUTPUTS is app/build/outputs) for the
      universal APKs under it. That file is the in-app updater's one probe
      (app/.../update/AppSelfUpdate.kt): its field names, and the asset names
      it points at, are a contract with every installed build.

  publish-build OUTPUTS
      Publishes the APKs, release-info.json and changelog.json under OUTPUTS
      to a brand new, permanent release tagged by this build's version
      (v0.2.0-dev.<code>, matching versionName). This is what every push to
      main does (the "Unstable" channel): one release per build, never
      rewritten, so the release list is droidtop's build history. The notes
      are this build's changelog entry, rendered whole, with its "After
      updating" lines; every build has one, since its commits make it. If a
      release for this exact tag already exists
      -- a rebuild of the same commit, or a retry after a cancelled run -- it
      is replaced (delete, recreate), never any OTHER version's release. The
      in-app updater finds the newest one through the GitHub API
      (AppSelfUpdate.fetchNewestBuild); nothing points at this tag by a
      fixed name.

  publish CHANNEL OUTPUTS
      Publishes the APKs and release-info.json under OUTPUTS (a build's own
      outputs, or its downloaded droidtop-apk artifact, which has the same
      layout) to the CHANNEL release (testing or stable). CHANNEL is a
      moving pointer: promotion is a rare, deliberate action, and the person
      promoting wants one stable download URL, not a new tag every time. The
      release is never deleted: new assets are uploaded under a temporary
      name while the old ones keep serving, then each old asset is swapped
      for its new one (delete, rename), release-info.json last. A run
      cancelled at any point leaves a release that exists and serves either
      complete build or, for a moment, a new APK beside the old
      release-info.json, which the updater rejects by digest and retries at
      the next check.

  range AFTER THROUGH [CHANGELOG_JSON]
      Prints every build's entry newer than versionCode AFTER and not newer
      than THROUGH, merged (each line once) with their After updating lines:
      what an update between the two brings. From CHANGELOG_JSON, else the
      newest build release's.

  fragments
      Prints every fragment in changelog.d grouped Added / Changed / Fixed,
      the section to paste into CHANGELOG.md when a real version is cut
      (after which those fragments are removed by name). The same parsing
      and rendering as a build's entry: one mechanism for both documents.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import changelog_commits  # noqa: E402  (beside this script; CI runs it by path)

REPO = os.environ.get("GITHUB_REPOSITORY", "Droidtop/droidtop")

# The single source for droidtop's current version base; app/build.gradle.kts
# versionName and this script's own tag/versionName scheme both read from
# here, so a version bump is one edit, not a hunt across files.
BASE_VERSION = "0.2.0"

# Published asset name -> path under OUTPUTS. release-info.json is last on
# purpose: it is what makes an installed build act on the others. Shared by
# both publish-build and publish (CHANNEL): the same OUTPUTS/release-info.json
# a build wrote can end up published as a per-build history release now and,
# later, promoted to testing or stable, so the names it references have to be
# identical everywhere it might land.
ASSETS = [
    ("droidtop.apk", "apk/release/app-universal-release.apk"),
    ("droidtop-debug.apk", "apk/debug/app-universal-debug.apk"),
    ("release-info.json", "release-info.json"),
]
STAGED_PREFIX = "next."

# Every per-build history release's tag, matching versionName
# ("<BASE_VERSION>-dev.<code>") with a "v" in front. Never "latest",
# "testing" or "stable": those are the two moving-pointer channels and are
# excluded by not matching this pattern.
BUILD_TAG_RE = re.compile(r"^v" + re.escape(BASE_VERSION) + r"-dev\.(\d+)$")

# The changelog's fragments (docs/SPEC.md 10b): one file per change, written
# in plain words for the people using droidtop, so two branches never edit
# the same lines. A fragment's name is its identity in every build's history
# and is never reused.
FRAGMENTS_DIR = "changelog.d"
FRAGMENTS_README = "README.md"
CHANGELOG_ASSET = "changelog.json"
CHANGELOG_FORMAT = 1

# A private plugin is never named in public release notes or the changelog
# (docs "romgi is private"): a fragment naming one fails the build rather
# than being published.
PRIVATE_NAMES = ("romgi",)

# Keep a Changelog's own section order; a build's notes, the app's What's
# new and a CHANGELOG.md entry all render in this order.
SECTION_ORDER = ("Added", "Changed", "Fixed")
SECTION_RE = re.compile(r"^###\s+(Added|Changed|Fixed)\s*$")


def gh(*args, check=True):
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"gh {' '.join(args)} failed:\n{result.stderr}")
    return result


def gh_json(*args):
    return json.loads(gh("api", *args).stdout)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def version_code(commit):
    # With one commit per page, the "last" page number is the commit count.
    out = gh("api", "--include", f"repos/{REPO}/commits?sha={commit}&per_page=1").stdout
    headers = out.split("\r\n\r\n" if "\r\n\r\n" in out else "\n\n", 1)[0]
    last = re.search(r'[?&]page=(\d+)>; rel="last"', headers)
    if last:
        return int(last.group(1))
    # No Link header means everything fit on the one page.
    body = json.loads(out[len(headers):].strip())
    if len(body) != 1:
        sys.exit(f"Could not count the commits reachable from {commit}")
    return 1


def write_info(outputs, code, commit):
    release_apk = os.path.join(outputs, ASSETS[0][1])
    debug_apk = os.path.join(outputs, ASSETS[1][1])
    info = {
        "formatVersion": 1,
        "versionCode": code,
        # Must equal the APK's own versionName: app/build.gradle.kts builds it
        # from the same VERSION_REVISION and the same BASE_VERSION.
        "versionName": f"{BASE_VERSION}-dev.{code}",
        "apkName": ASSETS[0][0],
        "apkSha256": sha256(release_apk),
        # Added keys, not a new formatVersion: a build from before the debug
        # channel existed reads this same document and sees exactly the
        # release APK it always did.
        "debugApkName": ASSETS[1][0],
        "debugApkSha256": sha256(debug_apk),
        "commit": commit,
    }
    with open(os.path.join(outputs, "release-info.json"), "w") as f:
        json.dump(info, f, indent=2)


def release_or_none(tag):
    result = gh("api", f"repos/{REPO}/releases/tags/{tag}", check=False)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if "HTTP 404" in result.stderr:
        return None
    sys.exit(f"Could not read the {tag} release:\n{result.stderr}")


def point_tag(channel, commit):
    # Moving a ref is one atomic write; the release follows its tag by name.
    exists = gh("api", f"repos/{REPO}/git/ref/tags/{channel}", check=False).returncode == 0
    if exists:
        gh("api", "-X", "PATCH", f"repos/{REPO}/git/refs/tags/{channel}",
           "-f", f"sha={commit}", "-F", "force=true")
    else:
        gh("api", "-X", "POST", f"repos/{REPO}/git/refs",
           "-f", f"ref=refs/tags/{channel}", "-f", f"sha={commit}")


def publish(channel, outputs):
    with open(os.path.join(outputs, "release-info.json")) as f:
        info = json.load(f)
    commit = info["commit"]
    if sha256(os.path.join(outputs, ASSETS[0][1])) != info["apkSha256"] or \
            sha256(os.path.join(outputs, ASSETS[1][1])) != info["debugApkSha256"]:
        sys.exit("The APKs do not match their release-info.json; refusing to publish")

    title = f"DroidTop {channel} channel"
    notes = (
        f"Built by CI from commit {commit} (versionCode {info['versionCode']}) and published to "
        f"the {channel} channel. Two APKs: {ASSETS[0][0]} is the build to use (release, not "
        f"debuggable, no code shrinking yet) and {ASSETS[1][0]} is the same code built "
        "debuggable, for inspecting droidtop with adb and several times slower to start and to "
        "navigate. Both are signed with the persistent DroidTop dev key, so either installs over "
        "an earlier build of this channel without uninstalling first; arm64-v8a + x86_64 fat "
        "APKs. Not for production use, and functionality is still early: see docs/SPEC.md for "
        "current status. Per-build history lives in the versioned releases "
        f"(v{BASE_VERSION}-dev.<versionCode>); this release is only a pointer to whatever was "
        f"last promoted to {channel}."
    )

    stage = tempfile.mkdtemp()
    release = release_or_none(channel)
    point_tag(channel, commit)

    if release is None:
        files = []
        for name, source in ASSETS:
            shutil.copyfile(os.path.join(outputs, source), os.path.join(stage, name))
            files.append(os.path.join(stage, name))
        gh("release", "create", channel, *files, "--verify-tag", "--title", title, "--notes", notes)
        print(f"Created the {channel} release at {commit}")
        return

    # A cancelled earlier publish can leave staged uploads behind.
    for asset in release["assets"]:
        if asset["name"].startswith(STAGED_PREFIX):
            gh("api", "-X", "DELETE", f"repos/{REPO}/releases/assets/{asset['id']}")

    staged = []
    for name, source in ASSETS:
        path = os.path.join(stage, STAGED_PREFIX + name)
        shutil.copyfile(os.path.join(outputs, source), path)
        staged.append(path)
    gh("release", "upload", channel, *staged)

    assets = {a["name"]: a["id"] for a in gh_json(f"repos/{REPO}/releases/{release['id']}/assets?per_page=100")}
    for name, _ in ASSETS:
        if name in assets:
            gh("api", "-X", "DELETE", f"repos/{REPO}/releases/assets/{assets[name]}")
        gh("api", "-X", "PATCH", f"repos/{REPO}/releases/assets/{assets[STAGED_PREFIX + name]}",
           "-f", f"name={name}")

    gh("api", "-X", "PATCH", f"repos/{REPO}/releases/{release['id']}",
       "-f", f"name={title}", "-f", f"body={notes}")
    print(f"Updated the {channel} release to {commit}")


def previous_build_release(exclude_tag):
    # Newest first (the API's default order), so the first match is the
    # newest per-build release. Testing/stable never match BUILD_TAG_RE, so
    # they cannot be picked up as a "previous build" by accident.
    for release in gh_json(f"repos/{REPO}/releases?per_page=100"):
        tag = release["tag_name"]
        if tag != exclude_tag and BUILD_TAG_RE.match(tag):
            return release
    return None


def parse_fragment(name, text):
    # A fragment is Keep a Changelog's own shape: "### Added" (or Changed,
    # Fixed) headings, each over "- " lines. A line that continues the one
    # above it is joined to it. Anything else is an error, so a malformed
    # fragment fails the build instead of vanishing from the changelog.
    sections = {}
    current = None
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.rstrip()
        if not line.strip():
            continue
        heading = SECTION_RE.match(line)
        if heading:
            current = sections.setdefault(heading.group(1), [])
        elif line.startswith("- ") and current is not None:
            current.append(line[2:].strip())
        elif line.startswith("  ") and current:
            current[-1] += " " + line.strip()
        else:
            sys.exit(f"{FRAGMENTS_DIR}/{name}:{number}: expected '### Added', '### Changed', '### Fixed' or '- ' under one")
    if not any(sections.values()):
        sys.exit(f"{FRAGMENTS_DIR}/{name} has no entries")
    lowered = text.lower()
    if any(private in lowered for private in PRIVATE_NAMES):
        sys.exit(f"{FRAGMENTS_DIR}/{name} names a private plugin")
    return sections


def read_fragments(directory=FRAGMENTS_DIR):
    # Name (without .md) -> sections, in name order.
    fragments = {}
    if not os.path.isdir(directory):
        return fragments
    for entry in sorted(os.listdir(directory)):
        if not entry.endswith(".md") or entry == FRAGMENTS_README:
            continue
        with open(os.path.join(directory, entry)) as f:
            fragments[entry[:-3]] = parse_fragment(entry, f.read())
    return fragments


def merge_sections(fragments):
    merged = {name: [] for name in SECTION_ORDER}
    for sections in fragments:
        for name in SECTION_ORDER:
            merged[name].extend(sections.get(name, []))
    return {name: items for name, items in merged.items() if items}


def render_sections(sections):
    parts = []
    for name in SECTION_ORDER:
        items = sections.get(name) or []
        if items:
            parts.append(f"### {name}\n" + "\n".join(f"- {s}" for s in items))
    return "\n\n".join(parts)


def previous_history(exclude_tag):
    # The newest per-build release's changelog.json is the history up to it, and the release is where this build's
    # commits start. A release from before builds carried one (or none at all) starts the history here.
    release = previous_build_release(exclude_tag)
    if release is None:
        return [], None
    since = release.get("target_commitish")
    if not any(a["name"] == CHANGELOG_ASSET for a in release.get("assets", [])):
        return [], since
    stage = tempfile.mkdtemp()
    gh("release", "download", release["tag_name"], "--pattern", CHANGELOG_ASSET, "--dir", stage)
    with open(os.path.join(stage, CHANGELOG_ASSET)) as f:
        builds = json.load(f).get("builds", [])
    # An entry that names its commit is exact; the release's target is the same commit for every build made here.
    return builds, (builds[0].get("commit") if builds else None) or since


def commits_between(since, commit, wanted_fragments):
    # The commits after [since] up to [commit], oldest first, each with the changelog.d fragments it added, through the
    # API (CI checks out one commit). GitHub's compare lists up to 250; a longer range keeps what it lists. A range that
    # cannot be read gives none, and the entry is the fragments alone, as before 2026-11-04.
    if not since or not commit or since == commit:
        return []
    result = gh("api", f"repos/{REPO}/compare/{since}...{commit}", check=False)
    if result.returncode != 0:
        print(f"Could not list the commits since {since[:9]}: the entry is the fragments alone")
        return []
    listed = json.loads(result.stdout).get("commits", [])
    out = []
    unmatched = set(wanted_fragments)
    for item in listed:
        message = item.get("commit", {}).get("message", "")
        added = []
        # Which fragments a commit added: asked of it only while a new fragment is not yet matched to a commit.
        if unmatched:
            detail = gh("api", f"repos/{REPO}/commits/{item['sha']}", check=False)
            if detail.returncode == 0:
                for f in json.loads(detail.stdout).get("files", []):
                    name = f.get("filename", "")
                    if name.startswith(FRAGMENTS_DIR + "/") and name.endswith(".md") and f.get("status") == "added":
                        added.append(os.path.basename(name)[:-3])
            unmatched -= set(added)
        out.append((message, added))
    return out


def write_changelog(out, code, commit=None):
    version_name = f"{BASE_VERSION}-dev.{code}"
    commit = commit or os.environ.get("GITHUB_SHA")
    # A rebuild of the same version replaces its own entry.
    previous, since = previous_history(f"v{version_name}")
    history = [b for b in previous if b.get("versionCode") != code]
    carried = {name for build in history for name in build.get("fragments", [])}
    fragments = read_fragments()
    new = [name for name in fragments if name not in carried]
    # The commits since the previous build are the entry's base; a fragment their change added tells that change
    # instead (the owner, 2026-11-04: "automatically pulled from the github change log, and ENHANCED by the fragment").
    commits = commits_between(since, commit, new)
    sections, onboarding = changelog_commits.entry(commits, {name: fragments[name] for name in new}, PRIVATE_NAMES)
    entry = {
        "versionCode": code,
        "versionName": version_name,
        "commit": commit,
        "fragments": new,
        "sections": sections,
        "onboarding": onboarding,
    }
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w") as f:
        json.dump({"formatVersion": CHANGELOG_FORMAT, "builds": [entry] + history}, f, indent=1)
    print(f"{version_name}: {len(commits)} commit(s), {len(new)} new fragment(s), {len(history)} earlier build(s)")


def print_range(after, through, path=None):
    # Every build newer than [after] and not newer than [through], merged: what an update between them brings, and
    # what a person must do after it. From [path], else the newest build release's changelog.json.
    if path is None:
        release = previous_build_release(None)
        if release is None:
            sys.exit("No build release to read the changelog from")
        stage = tempfile.mkdtemp()
        gh("release", "download", release["tag_name"], "--pattern", CHANGELOG_ASSET, "--dir", stage)
        path = os.path.join(stage, CHANGELOG_ASSET)
    with open(path) as f:
        builds = json.load(f).get("builds", [])
    merged = changelog_commits.merged(builds, after, through)
    if not merged["builds"]:
        print(f"No build between {after} and {through}")
        return
    print("Builds: " + ", ".join(merged["builds"]))
    print()
    print(render_sections(merged["sections"]) or "(no lines)")
    if merged["onboarding"]:
        print()
        print("### After updating\n" + "\n".join(f"- {s}" for s in merged["onboarding"]))


def print_fragments():
    fragments = read_fragments()
    if not fragments:
        print("(no fragments)")
        return
    print(render_sections(merge_sections(fragments.values())))


def publish_build(outputs):
    with open(os.path.join(outputs, "release-info.json")) as f:
        info = json.load(f)
    commit = info["commit"]
    if sha256(os.path.join(outputs, ASSETS[0][1])) != info["apkSha256"] or \
            sha256(os.path.join(outputs, ASSETS[1][1])) != info["debugApkSha256"]:
        sys.exit("The APKs do not match their release-info.json; refusing to publish")

    # Every build's notes are its complete changelog (owner, Droidtop/tracker#727): the entry this build's APK
    # carries, read from the same file, so the notes and the app's What's new cannot differ.
    changelog_path = os.path.join(outputs, CHANGELOG_ASSET)
    if not os.path.exists(changelog_path):
        sys.exit(f"This build has no {CHANGELOG_ASSET}; refusing to publish")
    with open(changelog_path) as f:
        builds = json.load(f).get("builds", [])
    entry = builds[0] if builds else None
    if entry is None or entry.get("versionCode") != info["versionCode"]:
        sys.exit(f"v{info['versionName']} has no changelog entry of its own in {CHANGELOG_ASSET}; refusing to publish")
    # A build always publishes (2026-11-04): its commits make its entry, so one with no fragment has one too. An entry
    # with nothing at all (a rebuild with no new commit) says so.
    notes = render_sections(entry.get("sections") or {}) or "No changes since the previous build"
    if entry.get("onboarding"):
        notes += "\n\n### After updating\n" + "\n".join(f"- {s}" for s in entry["onboarding"])

    tag = f"v{info['versionName']}"
    # A rebuild of the same commit (or a retry after this run was cancelled
    # partway) reuses this exact tag: replace it rather than leaving a
    # half-uploaded release or failing outright. Any OTHER version's release
    # is never touched.
    if release_or_none(tag) is not None:
        gh("release", "delete", tag, "--yes", "--cleanup-tag")

    stage = tempfile.mkdtemp()
    files = []
    for name, source in ASSETS:
        shutil.copyfile(os.path.join(outputs, source), os.path.join(stage, name))
        files.append(os.path.join(stage, name))
    # The next build's history starts from this file (write_changelog).
    files.append(changelog_path)

    # Marked prerelease while every version is 0.x-dev; the promotion
    # command is what marks a real 1.0 stable release non-pre.
    gh("release", "create", tag, *files, "--target", commit, "--prerelease",
       "--title", f"droidtop {tag}", "--notes", notes)
    print(f"Published {tag} at {commit}")


def main(argv):
    if len(argv) == 2 and argv[0] == "version-code":
        print(version_code(argv[1]))
    elif len(argv) == 4 and argv[0] == "info":
        write_info(argv[1], int(argv[2]), argv[3])
    elif len(argv) == 2 and argv[0] == "publish-build":
        publish_build(argv[1])
    elif len(argv) == 3 and argv[0] == "publish":
        publish(argv[1], argv[2])
    elif len(argv) in (3, 4) and argv[0] == "changelog":
        write_changelog(argv[1], int(argv[2]), argv[3] if len(argv) == 4 else None)
    elif len(argv) in (3, 4) and argv[0] == "range":
        print_range(int(argv[1]), int(argv[2]), argv[3] if len(argv) == 4 else None)
    elif len(argv) == 1 and argv[0] == "fragments":
        print_fragments()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
