#!/usr/bin/env bash
# Apply the reviewed description and add topics without losing existing topics.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
repo="wangbf123/StudyFlow"
current_topics="$(gh api "repos/$repo/topics" --jq '.names')"
merged_topics="$(mktemp)"
trap 'rm -f "$merged_topics"' EXIT
python3 - "$project_dir/docs/distribution/topics.json" "$current_topics" "$merged_topics" <<'PYMERGE'
import json, pathlib, sys
requested=json.loads(pathlib.Path(sys.argv[1]).read_text())['names']
current=json.loads(sys.argv[2])
names=list(dict.fromkeys(current+requested))
if len(names)>20:
    raise SystemExit('More than 20 merged topics; review topics manually before applying.')
pathlib.Path(sys.argv[3]).write_text(json.dumps({'names':names}))
PYMERGE
gh api --method PATCH "repos/$repo" --input "$project_dir/docs/distribution/about.json" --silent
gh api --method PUT "repos/$repo/topics" --input "$merged_topics" --silent
gh api "repos/$repo" --jq '{description,topics}'
