#!/usr/bin/env bash
#
# Publish a file to an AMPS topic, one message per line.
#
#   utils/bin/fileToAMPS.sh --topic orders --file orders.json
#   utils/bin/fileToAMPS.sh --topic orders --file dump.jsonl --format jsonl
#   utils/bin/fileToAMPS.sh --topic orders --file one-record.json --whole-file
#   utils/bin/fileToAMPS.sh --topic orders --file orders.json --dry-run
#
# --help for every option.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_launch.sh"
launch file-to-amps "$@"
