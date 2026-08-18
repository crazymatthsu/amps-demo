#!/usr/bin/env bash
#
# Clear SOW records out of one or more topics.
#
#   utils/bin/truncateAMPS.sh --topic orders                  # dry run: counts only
#   utils/bin/truncateAMPS.sh --topic orders --yes            # actually delete
#   utils/bin/truncateAMPS.sh --topic orders,instruments --yes
#   utils/bin/truncateAMPS.sh --topic orders --filter "/status = 'ORDER_STATUS_FILLED'" --yes
#   utils/bin/truncateAMPS.sh --journal                       # about the transaction log
#
# DRY RUN BY DEFAULT. Without --yes it reports what it would delete and changes
# nothing.
#
# ---------------------------------------------------------------------------
# This clears the SOW. It does NOT truncate the transaction log, and no client
# can: AMPS exposes no command that removes journal entries, because bookmark
# replay and publisher deduplication depend on the journal being an ordered,
# append-only record. A sow_delete is itself a write, so clearing a SOW makes
# the journal larger, not smaller.
#
# Run with --journal for the two approaches that do bound it: the
# amps-action-do-remove-journal ageing action, and ./server/scripts/amps.sh
# reset. Never prune journal files with an external rm while AMPS is running.
# ---------------------------------------------------------------------------
#
# --help for every option.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_launch.sh"
launch truncate "$@"
