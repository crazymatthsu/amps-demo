#!/usr/bin/env bash
#
# Dump a SOW topic to a file.
#
#   utils/bin/ampsToFileSOW.sh --topic orders --out /tmp/orders.json
#   utils/bin/ampsToFileSOW.sh --topic instruments --out /tmp/i.jsonl --format jsonl
#
# The default 'lines' format writes one payload per line, which fileToAMPS.sh
# reads back directly -- so dump and reload is a round trip.
#
# --help for every option.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_launch.sh"
launch sow-to-file "$@"
