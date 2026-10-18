#!/bin/bash
echo "acme $*" >> "@STAND@/calls"
case "$*" in
  *--issue*) sleep 3; exit 0 ;;
  *--installcert*)
    while [ $# -gt 0 ]; do
      case "$1" in --key-file) mkdir -p "$(dirname "$2")"; echo key > "$2" ;; --fullchain-file) mkdir -p "$(dirname "$2")"; echo chain > "$2" ;; esac
      shift
    done
    touch "@STAND@/trusted" ;;
esac
exit 0
