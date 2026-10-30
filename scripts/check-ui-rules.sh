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
# the path relative to the root) where its pattern is the mechanism itself, or,
# written "only:INCLUDE [except EXCLUDE]", the only paths it applies to.
set -eu

mode=""
all=0
root="$(cd "$(dirname "$0")/.." && pwd)"
self="$root"
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
"empty-run @@ 1 @@ run = \{\} @@ - @@ a row that does nothing is a value on another row, gone, or a NoticeItem"
"warning-text @@ 1 @@ TextBlockItem\([^)]*\"WARNING|\(high risk\) @@ - @@ a warning is a notice, never a paragraph or a title suffix"
"hex-colour @@ 1 @@ Color\(0x[0-9A-Fa-f]{6,8} @@ (DesignTokens|GamingTheme|ChromeColors|SystemThemeColors|NoticeColors|GamingThemeMapping)\.kt$|/theme/ @@ screens read colour roles, never a literal"
"deleted-names @@ 0 @@ \bMenuHint\(|\bSafeModeBanner\(|\bReinitPill\(|MenuTokens\.Danger(Plate)?\b|\bOwnFieldKeyboard\( @@ - @@ these were replaced by the notice and text-entry components"
"banned-words @@ 1 @@ \bNotice\([^)\"]*\"[^\"]*(${BANNED}) @@ - @@ notice titles, bodies and labels say what happens, not the machinery (details may)"
"text-fields @@ 0 @@ \b(BasicTextField|OutlinedTextField|TextField)\( @@ /TextEntry\.kt$ @@ every text field is the one TextEntry"
"formatters @@ 1 @@ %\.[0-9]f [KMGT]B|Formatter\.format(Short)?FileSize|getRelativeTimeSpanString @@ /ValueFormat\.kt$|^stores/|/runtime/EngineCli\.kt$ @@ sizes, durations and times go through ValueFormat, the one formatter (the ported store code keeps its own, and the docker and podman commands print in their tools' own format)"
"permission-titles @@ 1 @@ PluginPermission\(\"[^\"]+\", [A-Za-z.]+, \"[^\"]*(${BANNED}) @@ - @@ a permission's title is plain words; its raw id is on Y Info"
"hint-tip @@ 1 @@ \bHintTip\b @@ - @@ explanations live on the focus line and in Info (Y), never in a bubble"
"catalog-literals @@ 1 @@ \b(title|subtitle) = \"[A-Z] @@ ^(app|display|host-bridge|input-keyboard|input-mapping|input-seat|library-core|net-core|plugin-host|plugin-host-testing|runtime-linux-noroot|runtime-linux-root|runtime-windows|shell-default|shell-desktop|shell-gamepad|stores|ui-shared)/|/GamingSettingsCatalog\.kt$ @@ a row's or screen's title and line are string resources read through Words (Droidtop/tracker#177); a module or file leaves this allowlist in the commit that clears it"
"raw-dialog @@ 0 @@ \bDialog\(onDismissRequest|\bAlertDialog\(|AlertDialog\.Builder\(|\bDropdownMenu\( @@ /ActionSheet\.kt$|/SidePanel\.kt$ @@ a menu of choices is the one ActionSheet (Droidtop/tracker#610); switched on when the last raw dialog is moved"
"bare-click @@ 1 @@ \.(clickable|combinedClickable)( *\(| *\{) @@ ^(app|display|host-bridge|input-keyboard|input-mapping|input-seat|library-core|net-core|plugin-host|plugin-host-testing|runtime-common|runtime-linux-noroot|runtime-linux-root|runtime-windows|shell-default|shell-desktop|stores|ui-shared)/|/TouchActions\.kt$ @@ a Gaming tap target goes through padRow, which keeps the click from being a D-pad stop (Droidtop/tracker#679, #670)"
"raw-corners @@ 1 @@ RoundedCornerShape\( @@ ^(app|display|host-bridge|input-keyboard|input-mapping|input-seat|library-core|net-core|plugin-host|plugin-host-testing|runtime-common|runtime-linux-noroot|runtime-linux-root|runtime-windows|shell-default|shell-desktop|stores|ui-shared)/|/theme/|/DesignTokens\.kt$ @@ a Gaming shape is one of Corners (Crisp, Tile, Panel, Plate, Pill, Icon); a theme's own radius is the theme's data (Droidtop/tracker#670)"
"bare-dialog @@ 1 @@ (^|[^A-Za-z0-9_.])Dialog\(|window\.Dialog\( @@ ^(app|display|host-bridge|input-keyboard|input-mapping|input-seat|library-core|net-core|plugin-host|plugin-host-testing|runtime-common|runtime-linux-noroot|runtime-linux-root|runtime-windows|shell-default|shell-desktop|stores|ui-shared)/|/ShellDialog\.kt$ @@ the shell's modal windows are ShellDialog, so they also open inside an in-game overlay (Droidtop/tracker#740)"
"press-target @@ 1 @@ \.minTouchTarget\b @@ /ShellWindow\.kt$ @@ a press target's size is ShellWindow.pressTarget: a finger's where touch comes first, the shell's 48dp where the pad drives (Droidtop/tracker#740: read directly, the finger size made the Quick Menu sparse)"
"companion-words @@ 1 @@ \"[^\"]*\b(helper|Helper|Shizuku|provider|Provider|Risky|root|Root|adb|ADB|IME|kernel)\b[^\"]*\" @@ only:^app/src/main/kotlin/dev/droidtop/app/Companion[^/]*\.kt$ except /CompanionPins\.kt$ @@ machinery words (helper, Shizuku, provider, Risky, root, adb, IME, kernel) never appear in what the companion says (Droidtop/tracker#786 rule 10); the helper's own page in CompanionPins.kt is excused until it moves to droidtop > Advanced, and leaves this allowlist in that commit"
"literal-text @@ 1 @@ \bText\(\"[A-Z] @@ ^(display|host-bridge|input-keyboard|input-seat|library-core|net-core|plugin-host|plugin-host-testing|runtime-common|runtime-linux-noroot|runtime-linux-root|runtime-windows|shell-default|stores)/ @@ a word on screen is a string resource (Droidtop/tracker#177); a module leaves this allowlist in the commit that clears it"
)

failed=0
sources() {
    find . -path ./vendor -prune -o -path ./.git -prune -o -path '*/build' -prune -o -path '*/src/test' -prune \
        -o -path ./scripts/ui-rules-fixtures -prune -o -name '*.kt' -print 2>/dev/null | sed 's|^\./||'
}
files="$(sources)"

# The paths a rule looks at: all but its allowlist, or with "only:INCLUDE [except EXCLUDE]" just the paths it names.
scope() { # allow
    case "$1" in
        -) cat ;;
        only:*)
            local spec="${1#only:}" include exclude=""
            include="${spec%% except *}"
            [ "$include" != "$spec" ] && exclude="${spec#* except }"
            if [ -n "$exclude" ]; then grep -E -- "$include" | grep -Ev -- "$exclude" || true; else grep -E -- "$include" || true; fi
            ;;
        *) grep -Ev -- "$1" || true ;;
    esac
}

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
        sites="$(printf '%s\n' "$files" | scope "$allow" \
            | xargs -d '\n' -r grep -EnH -- "$pattern" 2>/dev/null || true)"
    fi
    count=0
    [ -n "$sites" ] && count="$(printf '%s\n' "$sites" | wc -l | tr -d ' ')"
    report "$name" "$on" "$count" "$sites" "$what"
done

# Copy budgets (Droidtop/tracker#485 F2c): a row title longer than the title budget, or a subtitle longer than the
# body budget, written as a literal. The budgets are the limits file's copyWarnings, so no number is restated here.
# Today's copy is over in places, so this is a ratchet, not a rule: COPY_CEILING is the count at the last copy pass
# and only comes down. --check fails when the count rises above it; the commit that shortens copy lowers it to the
# new count. --all (the fixture test) allows none.
COPY_CEILING=0
budget() { sed -n '/"copyWarnings"/,/}/p' "$self/docs/view-limits/plugin-view-limits.json" | sed -n "s/.*\"$1\": *\([0-9]*\).*/\1/p"; }
title_max="$(budget title)"
body_max="$(budget body)"
copy_pattern="\btitle = \"[^\"\$]{$((title_max + 1)),}\"|\bsubtitle = \"[^\"\$]{$((body_max + 1)),}\""
copy_sites=""
[ -n "$files" ] && copy_sites="$(printf '%s\n' "$files" | xargs -d '\n' -r grep -EnH -- "$copy_pattern" 2>/dev/null || true)"
copy_count=0
[ -n "$copy_sites" ] && copy_count="$(printf '%s\n' "$copy_sites" | wc -l | tr -d ' ')"
ceiling="$COPY_CEILING"
[ "$all" = 1 ] && ceiling=0
copy_what="titles at most $title_max characters, subtitles at most $body_max (ceiling $ceiling: shorten copy, never raise it)"
if [ "$mode" = "--inventory" ]; then
    printf 'copy-budget [ratchet] %s site(s): %s\n' "$copy_count" "$copy_what"
    [ -n "$copy_sites" ] && printf '%s\n' "$copy_sites" | sed 's/^/    /'
elif [ "$copy_count" -gt "$ceiling" ]; then
    printf 'UI rule copy-budget: %s site(s), over %s\n' "$copy_count" "$copy_what"
    printf '%s\n' "$copy_sites" | sed 's/^/    /'
    failed=1
fi

exit "$failed"
