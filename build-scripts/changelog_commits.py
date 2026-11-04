"""A build's changelog entry made from its commits (docs/SPEC.md 10b, "Every build carries its changelog").

The owner, 2026-11-04 (relayed): "That fragment should be automatically pulled from the github change log, and
ENHANCED by the fragment." So the base of every build's entry is the commits since the previous published build,
one line each, grouped Added / Changed / Fixed; a changelog.d fragment, where its change added one, replaces that
change's line with better words; commits nobody will notice fold into one "Internal changes" line.

The commit convention the generator reads (changelog.d/README.md says it for people):

    Changelog: Added: <plain words for the people using droidtop>
    Changelog: Changed: ...
    Changelog: Fixed: ...
    Changelog: none            (internal: CI, tests, docs; nobody notices)
    Onboarding: <what a person needs to do or know after updating>

as git trailers, in the last paragraph of the message. Each may appear more than once. A commit with no Changelog
trailer gets a line from its subject (Fixed when the subject says it fixes something, Added when it adds, else
Changed), or counts as internal when its subject is about tests, CI or docs.

Pure functions only; release_channel.py does the GitHub calls. Run as a script:

    changelog_commits.py check MESSAGE_FILE...   each message's trailers are well formed (commit-hygiene)
"""

import re
import sys

SECTIONS = ("Added", "Changed", "Fixed")

TRAILER_RE = re.compile(r"^(Changelog|Onboarding)\s*:\s*(.*)$", re.IGNORECASE)
CHANGELOG_VALUE_RE = re.compile(r"^(Added|Changed|Fixed)\s*:\s*(\S.*)$", re.IGNORECASE)
NONE_VALUES = ("none", "internal")

# Subjects of changes nobody using droidtop notices. A Changelog trailer always wins over these.
INTERNAL_RE = re.compile(
    r"^(merge\b|revert \"merge|ci\b|ci:|tests?\b|tests?:|lint\b|docs?\b|docs?:|spec\b|spec:|changelog fragment\b)"
    r"|\b(\w+test|tests?)\s*:"
    r"|\bchangelog fragment\b",
    re.IGNORECASE,
)
FIXED_RE = re.compile(r"\b(fix|fixes|fixed|no longer|crash\w*|broken|regression|again works|works again)\b", re.IGNORECASE)
ADDED_RE = re.compile(r"^(add|adds|added|new)\b|\b(gets|now has|now offers)\b", re.IGNORECASE)

INTERNAL_LINE = "Internal changes ({count}); nothing looks different"


class ConventionError(ValueError):
    pass


def trailers(message):
    """The Changelog and Onboarding trailers of [message]: (key, value) in order, key as written in the convention.

    Trailers are the matching lines of the message's last paragraph, as git reads them; the subject never is one,
    even when it starts with "Changelog:" (this convention's own first commit did). Pure.
    """
    paragraphs = [p for p in re.split(r"\n\s*\n", message.strip()) if p.strip()]
    if len(paragraphs) < 2:
        return []
    found = []
    for line in paragraphs[-1].splitlines():
        match = TRAILER_RE.match(line.strip())
        if match:
            key = "Changelog" if match.group(1).lower() == "changelog" else "Onboarding"
            found.append((key, match.group(2).strip()))
    return found


def check(message):
    """Raises ConventionError naming the first malformed trailer in [message]; a message without any passes."""
    for key, value in trailers(message):
        if key == "Changelog":
            if value.lower() in NONE_VALUES:
                continue
            if not CHANGELOG_VALUE_RE.match(value):
                raise ConventionError(f"'Changelog: {value}' should be 'Changelog: Added|Changed|Fixed: <plain words>' or 'Changelog: none'")
        elif not value:
            raise ConventionError("'Onboarding:' needs the words a person reads after updating")


def subject_of(message):
    return message.strip().splitlines()[0].strip() if message.strip() else ""


def section_for(subject):
    """Added, Changed or Fixed for a subject with no trailer; None when it is internal. Pure."""
    if INTERNAL_RE.search(subject):
        return None
    if FIXED_RE.search(subject):
        return "Fixed"
    if ADDED_RE.search(subject):
        return "Added"
    return "Changed"


def plain(subject):
    """A subject as a changelog line: a bracketed tracker reference left out, a capital first letter, no stop."""
    text = re.sub(r"\s*\((?:[\w.-]+/)?[\w.-]*#\d+[^)]*\)", "", subject)
    text = re.sub(r"\s{2,}", " ", text).strip().rstrip(".;:,")
    return text[:1].upper() + text[1:]


def commit_lines(message):
    """[message]'s changelog lines as (section, text), its onboarding lines, and whether it is internal.

    A Changelog trailer is the change's own words; several make several lines; "none" makes it internal. Without one,
    the subject is the line, in the section it reads as. Pure.
    """
    lines, onboarding, internal = [], [], False
    for key, value in trailers(message):
        if key == "Onboarding":
            if value:
                onboarding.append(value)
        elif value.lower() in NONE_VALUES:
            internal = True
        else:
            match = CHANGELOG_VALUE_RE.match(value)
            if match:
                lines.append((match.group(1).capitalize(), match.group(2).strip()))
    if lines or internal:
        return lines, onboarding, internal and not lines
    subject = subject_of(message)
    section = section_for(subject)
    if section is None:
        return [], onboarding, True
    return [(section, plain(subject))], onboarding, False


def entry(commits, fragments, private_names=()):
    """A build's sections and onboarding lines from its [commits] and the [fragments] new in it.

    [commits] are (message, fragment names the commit added), oldest first. [fragments] maps a fragment's name to its
    sections. A commit that added a fragment is told by the fragment instead of its own line, which is the
    "enhanced by the fragment" rule; a fragment no commit here added (its commit came in an earlier, unpublished
    build) is added as it is. A line naming a private plugin is left out rather than published. Pure.
    """
    sections = {name: [] for name in SECTIONS}
    onboarding = []
    internal = 0
    used = set()
    # A fragment named for its tracker issue ("513-plugins-search") also tells a commit that cites that issue and
    # has no words of its own (no Changelog trailer): a change and its fragment often land as separate commits.
    numbered = {}
    for name in fragments:
        number = re.match(r"^(\d+)-", name)
        if number:
            numbered.setdefault(number.group(1), []).append(name)
    for message, added in commits:
        own = [name for name in added if name in fragments]
        lines, steps, is_internal = commit_lines(message)
        onboarding.extend(steps)
        if not own and not any(key == "Changelog" for key, _ in trailers(message)):
            cited = set(re.findall(r"#(\d+)\b", message))
            if any(number in numbered for number in cited):
                continue
        if own:
            for name in own:
                if name not in used:
                    used.add(name)
                    for section in SECTIONS:
                        sections[section].extend(fragments[name].get(section, []))
            continue
        if is_internal:
            internal += 1
            continue
        for section, text in lines:
            sections[section].append(text)
    for name, parts in fragments.items():
        if name not in used:
            for section in SECTIONS:
                sections[section].extend(parts.get(section, []))
    if internal:
        sections["Changed"].append(INTERNAL_LINE.format(count=internal))
    lowered = [p.lower() for p in private_names]

    def public(text):
        return not any(p in text.lower() for p in lowered)

    out = {}
    for name in SECTIONS:
        seen = []
        for text in sections[name]:
            if text and public(text) and text not in seen:
                seen.append(text)
        if seen:
            out[name] = seen
    steps = []
    for text in onboarding:
        if public(text) and text not in steps:
            steps.append(text)
    return out, steps


def merged(builds, after, through):
    """Every build's entry newer than [after] and not newer than [through], merged: the sections in order, each line
    once, the onboarding lines once, and the builds' names, newest first. What `release_channel.py range` prints and
    what What's new shows for an update that skipped builds. Pure."""
    chosen = [b for b in builds if after < b.get("versionCode", 0) <= through]
    sections = {name: [] for name in SECTIONS}
    onboarding = []
    for build in sorted(chosen, key=lambda b: b.get("versionCode", 0)):
        for name in SECTIONS:
            for text in build.get("sections", {}).get(name, []):
                if text not in sections[name]:
                    sections[name].append(text)
        for text in build.get("onboarding", []):
            if text not in onboarding:
                onboarding.append(text)
    return {
        "builds": [b.get("versionName") for b in sorted(chosen, key=lambda b: -b.get("versionCode", 0))],
        "sections": {k: v for k, v in sections.items() if v},
        "onboarding": onboarding,
    }


def main(argv):
    if len(argv) >= 2 and argv[0] == "check":
        bad = 0
        for path in argv[1:]:
            with open(path) as f:
                message = f.read()
            try:
                check(message)
            except ConventionError as e:
                bad += 1
                print(f"{path}: {subject_of(message)}: {e}")
        sys.exit(1 if bad else 0)
    sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
