#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
i686-w64-mingw32-gcc -Os -s -municode -Wl,--no-insert-timestamp -Wall -Wextra -Werror -o ../../assets/winnative/battlenet-session.exe battlenet.c -lcrypt32 -ladvapi32
