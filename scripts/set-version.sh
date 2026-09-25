#!/usr/bin/env bash
# Sets the release version in build.sbt and in the facts row and installation examples of README.md.
# With --check it changes nothing and fails when a file names another version; without a
# VERSION, --check takes the one in build.sbt, so CI can verify that the files agree.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/set-version.sh VERSION
       scripts/set-version.sh --check [VERSION]

Example: scripts/set-version.sh 1.0.1
EOF
}

CHECK=0
if [[ "${1:-}" == "--check" ]]; then
  CHECK=1
  shift
fi
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

build_version() {
  sed -n 's/^[[:space:]]*version[[:space:]]*:=[[:space:]]*"\([^"]*\)".*/\1/p' build.sbt | head -n 1
}

VERSION="${1:-}"
if [[ -z "$VERSION" && "$CHECK" -eq 1 ]]; then
  VERSION="$(build_version)"
fi
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  usage >&2
  exit 2
fi

# Every version the README names: the facts row, sbt's `% "x"` after a module and Maven's <version>.
readme_versions() {
  { grep -o '^| [0-9][^ |]* |' README.md | sed 's/^| \([^ |]*\) |$/\1/'
    grep -o '"com\.anjunar\.hibernateddl" %% "schema-[a-z]*" % "[^"]*"' README.md | sed 's/.*% "\([^"]*\)"$/\1/'
    grep -o '<version>[^<]*</version>' README.md | sed 's/<version>\(.*\)<\/version>/\1/'
  } | sort -u
}

if [[ "$CHECK" -eq 1 ]]; then
  status=0
  found="$(build_version)"
  if [[ "$found" != "$VERSION" ]]; then
    echo "build.sbt has version '$found', expected '$VERSION'." >&2
    status=1
  fi
  while IFS= read -r found; do
    if [[ "$found" != "$VERSION" ]]; then
      echo "README.md names version '$found', expected '$VERSION'." >&2
      status=1
    fi
  done < <(readme_versions)
  [[ "$status" -eq 0 ]] && echo "build.sbt and README.md name version $VERSION."
  exit "$status"
fi

# perl edits in place on Linux, macOS and Git Bash alike and keeps each file's line endings.
perl -pi -e "s/^(\\s*version\\s*:=\\s*\")[^\"]*\"/\${1}${VERSION}\"/" build.sbt
perl -pi \
  -e "s/^(\\| )[0-9][^ |]*( \\|)/\${1}${VERSION}\${2}/;" \
  -e "s/(\"com\\.anjunar\\.hibernateddl\" %% \"schema-[a-z]*\" % \")[^\"]*\"/\${1}${VERSION}\"/g;" \
  -e "s/<version>[^<]*<\\/version>/<version>${VERSION}<\\/version>/g" \
  README.md
echo "Set version $VERSION in build.sbt and README.md."
