#!/usr/bin/env bash
#
# Dump a topic's transaction-log history to a file.
#
#   utils/bin/ampsToFileTxLog.sh --topic orders --out /tmp/journal.jsonl
#   utils/bin/ampsToFileTxLog.sh --topic orders --out /tmp/tail.jsonl --max 100
#   utils/bin/ampsToFileTxLog.sh --topic orders --out /tmp/since.jsonl \
#       --bookmark '17824661523014277262|1787013856000000030|'
#
# This is a bookmark replay, so what you get is the message stream in journal
# order -- exactly what a resuming subscriber would receive. Only topics listed
# in <TransactionLog> have any history; anything else reports zero, correctly.
#
# Defaults to --format jsonl so the bookmarks are recorded alongside each
# message; --format lines gives a file fileToAMPS.sh can republish.
#
# --help for every option.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_launch.sh"
launch txlog-to-file "$@"
