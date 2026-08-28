# Real-OpenSSH test matrix for conch integration tests.
#
# One image recipe, many bases: BASE selects the distro (and therefore the
# OpenSSH version and userland — glibc vs busybox, procps vs busybox free/df)
# the app is exercised against. The default is Debian bookworm because
# lrzsz (rz/sz) was removed from every alpine repo and bookworm still ships
# it, enabling ZMODEM integration tests against the real binaries; knockd
# (port-knocking daemon) is Debian/Ubuntu-only too. install-packages.sh
# degrades gracefully on bases that lack an optional package.
#
# The docker CLI (static binary) is copied in so the in-session Docker tab
# can be tested against a real `docker ps` when run.sh mounts the host's
# docker socket into the default container.
ARG BASE=debian:bookworm-slim
FROM docker:27-cli AS dockercli

FROM ${BASE}
ARG DEBIAN_FRONTEND=noninteractive
COPY install-packages.sh /install-packages.sh
RUN sh /install-packages.sh && rm /install-packages.sh
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 2223 pw+pubkey · 2224 pubkey-only · 2225 forwarding · 2226 keyboard-interactive
# (PAM) · 2227 gated behind a UDP knock sequence on 2260-2262 (knockd)
EXPOSE 2223 2224 2225 2226 2227 2260/udp 2261/udp 2262/udp
ENTRYPOINT ["/entrypoint.sh"]
