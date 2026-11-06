#!/usr/bin/env bash
# Builds rime-data-luna, the Chinese pinyin data the keyboard downloads the
# first time Chinese is used (Droidtop/tracker#903 H8), already deployed, so the
# phone does not compile dictionaries. Run by .github/workflows/rime-engine.yml.
#
#   build-data-luna.sh RIME_DEPLOYER OPENCC_SHARE PRELUDE ESSAY LUNA OUT
#
# RIME_DEPLOYER is rime_deployer built from the same librime commit as the
# engine; OPENCC_SHARE is the .json and .ocd2 files of the OpenCC commit that
# librime pins; PRELUDE, ESSAY and LUNA are checkouts of rime/rime-prelude,
# rime/rime-essay and rime/rime-luna-pinyin. Writes to OUT:
#
#  - rime-data-luna.tar.gz: shared/ as the engine reads it (its
#    shared_data_dir): the schemas and settings (yaml), shared/build/ (the
#    compiled table, prisms, reverse index and configs: librime's
#    prebuilt_data_dir, which it falls back to before compiling anything) and
#    shared/opencc/ (t2s.json for 简化字, t2tw.json and t2hk.json, and their
#    .ocd2; librime looks for OpenCC's files only in the user and shared
#    directories, src/rime/gear/simplifier.cc). The sources it was compiled
#    from (luna_pinyin.dict.yaml, essay.txt) are left out: with a compiled
#    table and no source, librime reuses the table (DictCompiler::Compile).
#  - rime-data-luna.files.sha256: every unpacked file's SHA-256 (sha256sum -c).
#  - rime-data-luna-sources.tar.gz: the exact yaml and txt the data was built
#    from, the patched schema included, with SOURCES.txt naming each
#    repository and commit. prelude, essay and luna-pinyin are LGPL-3.0, and
#    shared/build is compiled from them, so their source goes beside it.
#
# The schemas built are luna_pinyin_simp (简化字), luna_pinyin (傳統漢字) and
# luna_pinyin_tw (臺灣字形), one dictionary for all three.
#
# One change to upstream: luna_pinyin.schema.yaml loses its stroke reverse
# lookup (the dependency on the stroke schema, reverse_lookup_translator, its
# reverse_lookup settings and the recognizer's pattern for it). Typing ` and
# strokes to find a character is a desktop habit; the stroke tables would add
# 9.5 MB unpacked to every download. The sed below does it, the file says so
# at its top, and the result is checked.
set -eu
deployer=$(realpath "$1")
opencc=$(realpath "$2")
prelude=$(realpath "$3")
essay=$(realpath "$4")
luna=$(realpath "$5")
out=$(realpath -m "$6")

work="$out/work"
src="$work/src"
rm -rf -- "${work:?}"
mkdir -p "$src" "$work/user"

cp "$prelude"/default.yaml "$prelude"/key_bindings.yaml "$prelude"/punctuation.yaml "$prelude"/symbols.yaml "$src/"
cp "$essay/essay.txt" "$src/"
cp "$luna"/luna_pinyin.dict.yaml "$luna"/pinyin.yaml "$luna"/luna_pinyin.schema.yaml \
   "$luna"/luna_pinyin_simp.schema.yaml "$luna"/luna_pinyin_tw.schema.yaml "$src/"

# The stroke reverse lookup, out (see above).
schema="$src/luna_pinyin.schema.yaml"
sed -i \
    -e '/^  dependencies:$/{N;/\n    - stroke$/d}' \
    -e '/^    - reverse_lookup_translator$/d' \
    -e '/^reverse_lookup:$/,/^$/d' \
    -e '/^    reverse_lookup: /d' \
    "$schema"
if grep -nE 'stroke|reverse_lookup_translator|^reverse_lookup:' "$schema"; then
    echo "luna_pinyin.schema.yaml still has the stroke reverse lookup"; exit 1
fi
sed -i '1i # Changed for droidtop by build-scripts/rime/build-data-luna.sh: the stroke reverse lookup is removed.' "$schema"

# The schemas the deployer builds (prelude's default.yaml lists others).
cat > "$src/default.custom.yaml" <<'EOF'
# droidtop (build-scripts/rime/build-data-luna.sh): the schemas in rime-data-luna.
patch:
  schema_list:
    - schema: luna_pinyin_simp
    - schema: luna_pinyin
    - schema: luna_pinyin_tw
EOF

{
    echo "rime-data-luna, built by build-scripts/rime/build-data-luna.sh (droidtop, Droidtop/tracker#903 H8)."
    echo "rime/rime-prelude $(git -C "$prelude" rev-parse HEAD) (LGPL-3.0)"
    echo "rime/rime-essay $(git -C "$essay" rev-parse HEAD) (LGPL-3.0)"
    echo "rime/rime-luna-pinyin $(git -C "$luna" rev-parse HEAD) (LGPL-3.0)"
    echo "luna_pinyin.schema.yaml is changed: the stroke reverse lookup is removed (see the script)."
    echo "default.custom.yaml is droidtop's: the schema list."
    echo "opencc/: the OpenCC data (Apache-2.0) of the OpenCC commit librime pins, compiled with its own opencc_dict."
} > "$src/SOURCES.txt"
cp "$prelude/LICENSE" "$src/LICENSE-rime-data"
tar --sort=name --owner=0 --group=0 --numeric-owner -C "$work" -cf - src \
    | gzip -9 -n > "$out/rime-data-luna-sources.tar.gz"

# Deploy into shared/build, as the phone's librime would.
shared="$work/pkg/shared"
mkdir -p "$shared/opencc"
cp "$src"/*.yaml "$src"/essay.txt "$src"/SOURCES.txt "$src"/LICENSE-rime-data "$shared/"
cp "$opencc"/*.json "$opencc"/*.ocd2 "$shared/opencc/"
[ -f "$shared/opencc/t2s.json" ] || { echo "no t2s.json in $opencc"; exit 1; }
"$deployer" --build "$work/user" "$shared" "$shared/build" 2>&1 | tee "$work/deploy.log"
if grep -E '^[EF][0-9]{4}' "$work/deploy.log"; then
    echo "rime_deployer logged errors"; exit 1
fi
for f in default.yaml luna_pinyin.table.bin luna_pinyin.prism.bin luna_pinyin.reverse.bin \
         luna_pinyin.schema.yaml luna_pinyin_simp.prism.bin luna_pinyin_simp.schema.yaml \
         luna_pinyin_tw.prism.bin luna_pinyin_tw.schema.yaml; do
    [ -s "$shared/build/$f" ] || { echo "rime_deployer made no build/$f"; exit 1; }
done
if ls "$shared/build" | grep -q '^stroke'; then
    echo "build/ has stroke tables"; exit 1
fi

# The compiled sources stay out of the package.
rm -- "$shared"/*.dict.yaml "$shared/essay.txt"
ls -l "$shared" "$shared/build"
(cd "$work/pkg" && find shared -type f | LC_ALL=C sort | xargs sha256sum) > "$out/rime-data-luna.files.sha256"
tar --sort=name --owner=0 --group=0 --numeric-owner -C "$work/pkg" -cf - shared \
    | gzip -9 -n > "$out/rime-data-luna.tar.gz"
du -sb "$shared"
ls -l "$out"
