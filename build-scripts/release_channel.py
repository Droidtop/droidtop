"""The one mechanism behind droidtop's release channels (docs/SPEC.md 10b).

Three subcommands, run by CI only:

  version-code COMMIT
      Prints the versionCode for COMMIT: the number of commits reachable from
      it. Every commit on main has strictly more ancestors than the commit
      before it, so the number only ever grows while main is never rewritten,
      and it belongs to the commit rather than to a workflow: renaming or
      splitting android-build.yml (whose run_number was the versionCode until
      2026-09-24) cannot reset it, and rebuilding a commit gives the same
      number. Counted through the GitHub API because CI checks out one commit
      of history, and the full history is a 200 MB fetch per run.

  info OUTPUTS VERSION_CODE COMMIT
      Writes OUTPUTS/release-info.json (OUTPUTS is app/build/outputs) for the
      universal APKs under it. That file is the in-app updater's one probe
      (app/.../update/AppSelfUpdate.kt): its field names, and the asset names
      it points at, are a contract with every installed build.

  publish CHANNEL OUTPUTS
      Publishes the APKs and release-info.json under OUTPUTS (a build's own
      outputs, or its downloaded droidtop-apk artifact, which has the same
      layout) to the CHANNEL release. The release is never deleted: new
      assets are uploaded under a temporary name while the old ones keep
      serving, then each old asset is swapped for its new one (delete,
      rename), release-info.json last. A run cancelled at any point leaves a
      release that exists and serves either complete build or, for a
      moment, a new APK beside the old release-info.json, which the updater
      rejects by digest and retries at the next check.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO = os.environ.get("GITHUB_REPOSITORY", "Droidtop/droidtop")

# Published asset name -> path under OUTPUTS. release-info.json is last on
# purpose: it is what makes an installed build act on the others.
ASSETS = [
    ("droidtop-latest.apk", "apk/release/app-universal-release.apk"),
    ("droidtop-latest-debug.apk", "apk/debug/app-universal-debug.apk"),
    ("release-info.json", "release-info.json"),
]
STAGED_PREFIX = "next."


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
        # from the same VERSION_REVISION.
        "versionName": f"0.1.0-dev-{code}",
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


def release_or_none(channel):
    result = gh("api", f"repos/{REPO}/releases/tags/{channel}", check=False)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if "HTTP 404" in result.stderr:
        return None
    sys.exit(f"Could not read the {channel} release:\n{result.stderr}")


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
        f"the {channel} channel. Two APKs: droidtop-latest.apk is the build to use (release, not "
        "debuggable, no code shrinking yet) and droidtop-latest-debug.apk is the same code built "
        "debuggable, for inspecting droidtop with adb and several times slower to start and to "
        "navigate. Both are signed with the persistent DroidTop dev key, so either installs over "
        "an earlier build of this channel without uninstalling first; arm64-v8a + x86_64 fat "
        "APKs. Not for production use, and functionality is still early: see docs/SPEC.md for "
        "current status."
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


def main(argv):
    if len(argv) == 2 and argv[0] == "version-code":
        print(version_code(argv[1]))
    elif len(argv) == 4 and argv[0] == "info":
        write_info(argv[1], int(argv[2]), argv[3])
    elif len(argv) == 3 and argv[0] == "publish":
        publish(argv[1], argv[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
