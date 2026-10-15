#!/usr/bin/env bash
# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# Re-records docs/teaser.gif against the docker/ rig: setup.sh off camera, demo.sh on camera with
# asciinema (2.4), rendered by agg from its docker image, then the demo objects are deleted again.
# MIDGIE_JAR=/path/to/midgie-X.Y.Z.jar records a released jar instead of the dev build.
set -euo pipefail
source "$(dirname "$0")/env.sh"

"$TEASER/setup.sh" > /dev/null
trap delete_demo_objects EXIT
# stdin from /dev/null so no keypress during the run ends up in the recording.
TERM=xterm-256color asciinema rec -q --overwrite --cols 100 --rows 31 --idle-time-limit 2 \
    --command "$TEASER/demo.sh" "$WORK/teaser.cast" < /dev/null
docker run --rm -u "$(id -u):$(id -g)" -v "$WORK:/data" ghcr.io/asciinema/agg:latest \
    --quiet --theme github-dark --font-size 14 --idle-time-limit 2 --last-frame-duration 6 teaser.cast teaser.gif
cp -f "$WORK/teaser.gif" "$REPO/docs/teaser.gif"
echo "wrote $REPO/docs/teaser.gif"
