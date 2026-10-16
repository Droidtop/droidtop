#!/usr/bin/env bash
# The UI rules (docs/SPEC.md 7k, "Notices" and "Enforcement"; Droidtop/tracker#485),
# as one script with two modes:
#
#   scripts/check-ui-rules.sh --check      fail when a switched-on rule has a site
#   scripts/check-ui-rules.sh --inventory  list every rule's remaining sites, on or off
#
# A rule is switched on (ON below) in the commit that clears its last site, so
# --check never fails on work not yet done and never lets a cleared rule come
# back. --all treats every rule as on (the fixture test uses it); --root DIR
# scans another tree. Each rule has an allowlist of paths (extended regex on
# the path relative to the root) where its pattern is the mechanism itself.
set -eu

mode=""
all=0
root="$(cd "$(dirname "$0")/.." && pwd)"
while [ $# -gt 0 ]; do
    case "$1" in
        --check|--inventory) mode="$1" ;;
        --all) all=1 ;;
        --root) shift; root="$1" ;;
        *) echo "usage: $0 --check|--inventory [--all] [--root DIR]" >&2; exit 2 ;;
    esac
    shift
done
[ -n "$mode" ] || { echo "usage: $0 --check|--inventory [--all] [--root DIR]" >&2; exit 2; }
cd "$root"

# The words no title, body, recommendation or label may use (details may).
BANNED='sandbox|\broot\b|Shizuku|signing|fingerprint|helper|\bhost\b|\bindex\b|catalog|digest|\bIME\b|provider|\btier\b|seccomp|\badb\b'

# Each rule: name, on (1) or off (0), pattern, allowlist ('-' for none), what it means;
# fields are separated by " @@ " because patterns and allowlists use "|".
RULES=(
"toast @@ 1 @@ Toast\.makeText @@ /Notices\.kt$ @@ an acknowledgement is Notices.flash, a failure a row notice or a dialog"
"error-colour @@ 1 @@ colorScheme\.error @@ ^ui-shared/ @@ Material's error roles are set from the notice palette in :ui-shared only"
"empty-run @@ 0 @@ run = \{\} @@ - @@ a row that does nothing is a value on another row, gone, or a NoticeItem"
"warning-text @@ 1 @@ TextBlockItem\([^)]*\"WARNING|\(high risk\) @@ - @@ a warning is a notice, never a paragraph or a title suffix"
"hex-colour @@ 1 @@ Color\(0x[0-9A-Fa-f]{6,8} @@ (DesignTokens|GamingTheme|ChromeColors|SystemThemeColors|NoticeColors|GamingThemeMapping)\.kt$|/theme/ @@ screens read colour roles, never a literal"
"deleted-names @@ 0 @@ \bMenuHint\(|\bSafeModeBanner\(|\bReinitPill\(|MenuTokens\.Danger(Plate)?\b|\bOwnFieldKeyboard\( @@ - @@ these were replaced by the notice and text-entry components"
"banned-words @@ 1 @@ \bNotice\([^)\"]*\"[^\"]*(${BANNED}) @@ - @@ notice titles, bodies and labels say what happens, not the machinery (details may)"
"text-fields @@ 0 @@ \b(BasicTextField|OutlinedTextField|TextField)\( @@ /TextEntry\.kt$ @@ every text field is the one TextEntry"
"formatters @@ 1 @@ %\.[0-9]f [KMGT]B|Formatter\.format(Short)?FileSize|getRelativeTimeSpanString @@ /ValueFormat\.kt$|^stores/|/runtime/EngineCli\.kt$ @@ sizes, durations and times go through ValueFormat, the one formatter (the ported store code keeps its own, and the docker and podman commands print in their tools' own format)"
"permission-titles @@ 1 @@ PluginPermission\(\"[^\"]+\", [A-Za-z.]+, \"[^\"]*(${BANNED}) @@ - @@ a permission's title is plain words; its raw id is on Y Info"
"hint-tip @@ 1 @@ \bHintTip\b @@ - @@ explanations live on the focus line and in Info (Y), never in a bubble"
)

failed=0
sources() {
    find . -path ./vendor -prune -o -path ./.git -prune -o -path '*/build' -prune -o -path '*/src/test' -prune \
        -o -path ./scripts/ui-rules-fixtures -prune -o -name '*.kt' -print 2>/dev/null | sed 's|^\./||'
}
files="$(sources)"

report() { # name on count sites what
    local name="$1" on="$2" count="$3" sites="$4" what="$5"
    if [ "$mode" = "--inventory" ]; then
        printf '%s [%s] %s site(s): %s\n' "$name" "$([ "$on" = 1 ] && echo on || echo off)" "$count" "$what"
        [ -n "$sites" ] && printf '%s\n' "$sites" | sed 's/^/    /'
    elif [ "$count" -gt 0 ] && { [ "$on" = 1 ] || [ "$all" = 1 ]; }; then
        printf 'UI rule %s: %s\n' "$name" "$what"
        printf '%s\n' "$sites" | sed 's/^/    /'
        failed=1
    fi
    return 0
}

for rule in "${RULES[@]}"; do
    name="${rule%% @@ *}"; rest="${rule#* @@ }"
    on="${rest%% @@ *}"; rest="${rest#* @@ }"
    pattern="${rest%% @@ *}"; rest="${rest#* @@ }"
    allow="${rest%% @@ *}"; what="${rest#* @@ }"
    sites=""
    if [ -n "$files" ]; then
        sites="$(printf '%s\n' "$files" | { if [ "$allow" = "-" ]; then cat; else grep -Ev -- "$allow" || true; fi; } \
            | xargs -d '\n' -r grep -EnH -- "$pattern" 2>/dev/null || true)"
    fi
    count=0
    [ -n "$sites" ] && count="$(printf '%s\n' "$sites" | wc -l | tr -d ' ')"
    report "$name" "$on" "$count" "$sites" "$what"
done

exit "$failed"
