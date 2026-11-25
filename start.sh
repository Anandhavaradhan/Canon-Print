#!/usr/bin/env bash
# Start Canon Print on macOS or Linux.
set -e
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
  echo "First start: setting things up. This takes a minute..."
  python3 -m venv .venv
  .venv/bin/python -m pip install --upgrade pip >/dev/null
  .venv/bin/python -m pip install -r requirements.txt
fi
exec .venv/bin/python run.py "$@"
