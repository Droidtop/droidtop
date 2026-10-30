#!/usr/bin/env bash
# Fixture test for scripts/check-ui-rules.sh (docs/SPEC.md 7k, "Notices";
# Droidtop/tracker#485, final design test 7): each rule fails on a file that
# breaks it, an allowlisted path does not, and a clean tree passes.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
check="$here/check-ui-rules.sh"
tmp="$(mktemp -d)"
trap 'rm -rf -- "${tmp:?}"' EXIT

fixture() { # rule file content
    local dir="$tmp/$1"
    mkdir -p "$dir/$(dirname "$2")"
    printf '%s\n' "$3" > "$dir/$2"
}

fixture toast app/src/main/kotlin/A.kt 'Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()'
fixture error-colour shell-gamepad/src/main/kotlin/A.kt 'val c = MaterialTheme.colorScheme.error'
fixture empty-run app/src/main/kotlin/A.kt 'ActionItem(id = "x", title = "x", run = {})'
fixture warning-text app/src/main/kotlin/A.kt 'TextBlockItem("w", "WARNING: this is risky")'
fixture hex-colour app/src/main/kotlin/A.kt 'val c = Color(0xFF123456)'
fixture deleted-names shell-gamepad/src/main/kotlin/A.kt 'MenuHint("Working")'
fixture banned-words app/src/main/kotlin/A.kt 'Notice(NoticeTone.Caution, "The sandbox did not start")'
fixture text-fields app/src/main/kotlin/A.kt 'BasicTextField(value = v, onValueChange = {})'
fixture formatters app/src/main/kotlin/A.kt 'val s = String.format("%.1f GB", b / 1e9)'
fixture hint-tip shell-gamepad/src/main/kotlin/A.kt 'HintTip("Why") { Text("Row") }'
fixture permission-titles plugin-host/src/main/kotlin/A.kt 'PluginPermission("x", PermissionTier.DANGEROUS, "Run adb commands")'
fixture raw-dialog shell-gamepad/src/main/kotlin/A.kt 'Dialog(onDismissRequest = onDismiss) { Text("Pick") }'
fixture copy-budget app/src/main/kotlin/A.kt 'ActionItem(id = "x", title = "A title that runs well past the forty character budget")'
fixture literal-text shell-gamepad/src/main/kotlin/A.kt 'Text("Loading")'
fixture catalog-literals runtime-common/src/main/kotlin/A.kt 'ActionItem(id = "x", title = "Reset")'
fixture bare-click shell-gamepad/src/main/kotlin/A.kt 'Modifier.clickable { open() }'
fixture raw-corners shell-gamepad/src/main/kotlin/A.kt 'Modifier.clip(RoundedCornerShape(8.dp))'
fixture press-target shell-gamepad/src/main/kotlin/A.kt 'Modifier.heightIn(min = window.minTouchTarget)'
fixture companion-words app/src/main/kotlin/dev/droidtop/app/CompanionA.kt 'CompanionNote("Needs the helper app")'

status=0
for dir in "$tmp"/*/; do
    rule="$(basename "$dir")"
    if out="$("$check" --check --all --root "$dir")"; then
        echo "FAIL: rule $rule passed a file that breaks it"; status=1
    elif ! printf '%s\n' "$out" | grep -q "UI rule $rule:"; then
        echo "FAIL: rule $rule did not name itself"; printf '%s\n' "$out"; status=1
    fi
done

# Allowlisted: the notice palette may set error roles in :ui-shared, and TextEntry is the one text field.
fixture allowed ui-shared/src/main/kotlin/A.kt 'val c = MaterialTheme.colorScheme.error'
fixture allowed ui-shared/src/main/kotlin/TextEntry.kt 'BasicTextField(value = v, onValueChange = {})'
fixture allowed runtime-common/src/main/kotlin/ValueFormat.kt 'val s = String.format("%.1f GB", b / 1e9)'
fixture allowed app/src/main/kotlin/dev/droidtop/app/OtherScreen.kt 'Text(stringResource(R.string.x), "Needs the helper app")'
if ! "$check" --check --all --root "$tmp/allowed" >/dev/null; then echo "FAIL: an allowlisted site failed"; status=1; fi

mkdir -p "$tmp/clean/app"
printf 'val x = 1\n' > "$tmp/clean/app/A.kt"
if ! "$check" --check --all --root "$tmp/clean" >/dev/null; then echo "FAIL: a clean tree failed"; status=1; fi

if [ "$status" = 0 ]; then
    echo "check-ui-rules: every rule fails on its fixture"
else
    echo "check-ui-rules: fixture test failed" >&2
fi
exit "$status"
