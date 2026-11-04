#!/bin/sh
set -eu
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 ADRESSE_IP_PRIVEE [--pairing]" >&2
  exit 2
fi
address=$1
shift
exec "${DUCKYBRIDGE_AGENT:-./duckybridge-agent}" serve --bind "$address" "$@"
