#!/usr/bin/env bash
# Runs amapi.py in its own virtualenv (created on first use).
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -d .venv ]]; then
  python3 -m venv .venv
  .venv/bin/pip install -q -r requirements.txt
fi
exec .venv/bin/python amapi.py "$@"
