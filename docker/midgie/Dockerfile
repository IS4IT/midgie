# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2

# midgie as a container image: the fat JAR, the bin/midgie launcher, and git.
#
# git is not optional garnish — midgie forks it (GitRevision) to derive a tree's deployment identity and
# to recover what it applied last time, which is what 'deploy --prune' compares against. Without it the
# tool still applies, but delete detection is off.
#
# The JAR is built outside this file and copied in. It is architecture-independent, so one Maven build
# feeds both platforms and nothing but the base image's package install ever runs under emulation:
#
#   mvn -q package
#   docker build -f docker/midgie/Dockerfile -t is4it/midgie:dev .
#
# The base is the glibc (Ubuntu) Temurin JRE rather than the Alpine one, which publishes no linux/arm64
# manifest. It is deliberately a floating tag: an image tag is cut once per release and never rebuilt, so
# pinning a digest here would only mean shipping a staler base than the day's build could have had.
FROM eclipse-temurin:17-jre

# Overridden by the release workflow, which builds the JAR under its version-stamped name.
ARG JAR=target/midgie.jar

# Enough to fetch the tree in this container rather than having an init container do it: git over HTTPS
# (ca-certificates, curl) and over SSH (openssh-client). curl is in the base image today, but naming it
# here is what keeps it there. less is git's pager — without it an interactive 'git log' or 'git diff'
# fails instead of paging, and this image is meant to be shelled into, as is vi for editing a file in
# place. vim-tiny, not vim: the full package pulls vim-runtime and libpython for ~80 MB, against ~4 MB
# for the one that provides /usr/bin/vi.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git less openssh-client vim-tiny \
    && rm -rf /var/lib/apt/lists/*

# git refuses a repository owned by another user, and midgie reports that refusal as "not in a git
# repository (or git is not available)" — after which tracking is silently unavailable and --prune finds
# nothing to remove. That is exactly the case here: an init container clones as one UID, this container
# reads as another. The ownership check guards against running config and hooks from a repository you do
# not control; a single-purpose container that is about to apply the tree's contents to midPoint anyway
# gains little from it, so the check is relaxed rather than left to fail quietly.
RUN git config --system --add safe.directory '*'

# GID 0 (not a private group) so an arbitrary UID — OpenShift assigns one per namespace — still lands in
# a group that can write HOME and the work directory. UID 1001, because the Ubuntu base already ships a
# user at 1000.
RUN useradd --uid 1001 --gid 0 --create-home --home-dir /home/midgie --shell /bin/bash midgie \
    && mkdir -p /work \
    && chown -R 1001:0 /home/midgie /work \
    && chmod -R g=u /home/midgie /work

# The launcher resolves the JAR relative to itself (../lib/midgie.jar), so this layout is the one it
# already knows. MIDGIE_JAR is set anyway: it survives the script being moved or symlinked, and gives a
# way to point a running container at a JAR copied in with 'kubectl cp'.
COPY bin/midgie /opt/midgie/bin/midgie
COPY ${JAR} /opt/midgie/lib/midgie.jar

ENV PATH=/opt/midgie/bin:$PATH \
    MIDGIE_JAR=/opt/midgie/lib/midgie.jar \
    HOME=/home/midgie

USER 1001
WORKDIR /work

LABEL org.opencontainers.image.title="midgie" \
      org.opencontainers.image.description="Schema-aware CLI that reconciles midPoint configuration held as object XML" \
      org.opencontainers.image.source="https://github.com/IS4IT/midgie" \
      org.opencontainers.image.licenses="EUPL-1.2" \
      org.opencontainers.image.vendor="IS4IT"

# A tool image: 'docker run … is4it/midgie deploy /work --url …', and in a Job 'args: [deploy, /work, …]'.
# The launcher execs the JVM, so it becomes PID 1 — a SIGTERM on 'kubectl delete job' reaches the JVM,
# and midgie's exit codes (0/1/2) surface as the pod's exit status.
#
# An interactive shell is 'docker run --entrypoint bash' / 'kubectl run … --command -- sleep infinity'
# plus 'kubectl exec -it … -- bash'; 'docker run <image> bash' would run 'midgie bash' and be rejected.
ENTRYPOINT ["/opt/midgie/bin/midgie"]
CMD ["--help"]
