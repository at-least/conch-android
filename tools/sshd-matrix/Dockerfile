# Real-OpenSSH test matrix for conch integration tests.
# Three sshd instances, fixed users/keys — see entrypoint.sh and run.sh.
# Debian (not alpine): lrzsz (rz/sz) was removed from every alpine repo;
# bookworm still ships it, enabling ZMODEM integration tests against the
# real binaries. tmux likewise; procps for the readiness probe, netcat for
# remote-forward tests.
FROM debian:bookworm-slim

RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        openssh-server openssh-client lrzsz tmux procps netcat-openbsd && \
    rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 2223 2224 2225
ENTRYPOINT ["/entrypoint.sh"]
