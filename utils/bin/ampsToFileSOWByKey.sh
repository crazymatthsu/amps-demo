#!/usr/bin/env bash
#
# Dump the part of a SOW topic that matches a selector.
#
#   utils/bin/ampsToFileSOWByKey.sh --topic orders \
#       --filter "/orderId = 'ORD-00007'" --out /tmp/one.json
#
#   utils/bin/ampsToFileSOWByKey.sh --topic orders \
#       --filter "/quantity > 3000 AND /side = 'SIDE_BUY'" --out /tmp/big.json
#
#   utils/bin/ampsToFileSOWByKey.sh --topic orders \
#       --sow-keys 17052545042516163865 --out /tmp/bykey.json
#
# Two ways to select, and the difference matters:
#
#   --filter     a content expression over the message fields, including the
#                business key (/orderId). This is what you normally want.
#   --sow-keys   the opaque identifiers AMPS itself assigns, as reported by a
#                previous query. Cheapest possible lookup, but you cannot
#                compute one -- you can only quote one back.
#
# Same tool as ampsToFileSOW.sh, which dumps everything; this wrapper just
# insists on a selector so "by key" cannot silently become "the whole topic".
#
# --help for every option.

set -euo pipefail

for arg in "$@"; do
    case "${arg}" in
        --filter|--filter=*|--sow-keys|--sow-keys=*|--help|-h|help)
            source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_launch.sh"
            launch sow-to-file "$@"
            ;;
    esac
done

cat >&2 <<'EOF'
error: this tool needs a selector -- pass --filter or --sow-keys.

  --filter "EXPR"      content filter, e.g. --filter "/orderId = 'ORD-00007'"
  --sow-keys K1,K2     server-assigned SOW keys from an earlier query

To dump a whole topic, that is ampsToFileSOW.sh instead.
EOF
exit 2
