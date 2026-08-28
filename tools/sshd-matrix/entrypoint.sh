#!/bin/sh
# Sets up auth-scenario users and runs the sshd instances of the matrix:
#   :2223 pw+pubkey, :2224 pubkey-only, :2225 pw+pubkey with forwarding,
#   :2226 keyboard-interactive via PAM (only where PAM is installed),
#   :2227 "gated": started by knockd for 8 s after the UDP knock sequence
#         2260,2261,2262 (only where knockd is installed).
# Expects /keys/{keyA.pub,keyB.pub} mounted read-only.
#
# Idempotent: `docker restart` re-runs this on a filesystem where the users
# and host keys already exist — the reconnect tests depend on the host keys
# SURVIVING a restart (a changed key must fail TOFU, a restart must not).
set -eu

mkdir -p /run/sshd

ensure_user() {
    id "$1" >/dev/null 2>&1 || useradd -m -s /bin/sh "$1"
}
ensure_user pwuser
ensure_user bothuser
ensure_user keyuser
ensure_user skuser

# An account with no password set would be "locked" ('!' in shadow); sshd
# then refuses even pubkey auth ("account is locked"). Set an
# unmatchable-but-unlocked hash instead — same as real hardened servers.
echo 'keyuser:*' | chpasswd -e
echo 'skuser:*' | chpasswd -e

echo 'pwuser:conch-pw-1' | chpasswd
echo 'bothuser:conch-pw-2' | chpasswd

setup_ak() {
    user="$1"; key="$2"
    install -d -m 700 -o "$user" -g "$user" "/home/$user/.ssh"
    cp "$key" "/home/$user/.ssh/authorized_keys"
    chown "$user:$user" "/home/$user/.ssh/authorized_keys"
    chmod 600 "/home/$user/.ssh/authorized_keys"
}
setup_ak bothuser /keys/keyA.pub
setup_ak keyuser /keys/keyB.pub
# FIDO2 scenario: a security-key public key (sk-ssh-ed25519@openssh.com) in
# authorized_keys. No token exists in the test harness, so nobody can log in
# as skuser — what is exercised is that sshd accepts the key type without
# choking and that the app's failure path is a clean auth rejection.
if [ -f /keys/keySK.pub ]; then setup_ak skuser /keys/keySK.pub; fi

# Docker tab tests: the host's docker socket may be mounted in. Give the
# test users access via the socket's group (root on Docker Desktop, docker
# on Linux) rather than chmod-ing a host-owned socket.
if [ -S /var/run/docker.sock ]; then
    gid=$(stat -c %g /var/run/docker.sock)
    getent group "$gid" >/dev/null || groupadd -g "$gid" dockersock
    gname=$(getent group "$gid" | cut -d: -f1)
    for u in pwuser bothuser keyuser; do usermod -aG "$gname" "$u"; done
fi

ssh-keygen -A

common_cfg='
HostKey /etc/ssh/ssh_host_ed25519_key
HostKey /etc/ssh/ssh_host_rsa_key
PermitRootLogin no
AllowUsers pwuser bothuser keyuser skuser
PubkeyAuthentication yes
StrictModes yes
AllowTcpForwarding no
X11Forwarding no
PrintMotd no
Subsystem sftp internal-sftp
LogLevel INFO
'

{
    echo 'Port 2223'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_pwpub.pid'
} > /etc/ssh/sshd_config_pwpub

{
    echo 'Port 2224'
    echo 'PasswordAuthentication no'
    echo 'KbdInteractiveAuthentication no'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_keyonly.pid'
} > /etc/ssh/sshd_config_keyonly

# OpenSSH is first-match-wins, so AllowTcpForwarding must come BEFORE
# common_cfg (which pins it to no) for this instance to allow forwarding.
{
    echo 'Port 2225'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo 'AllowTcpForwarding yes'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_fwd.pid'
} > /etc/ssh/sshd_config_fwd

# Keyboard-interactive ONLY (the shape of every 2FA / PAM-prompt server):
# plain "password" auth is refused; the client must answer the PAM prompt.
{
    echo 'Port 2226'
    echo 'UsePAM yes'
    echo 'PasswordAuthentication no'
    echo 'KbdInteractiveAuthentication yes'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_kbd.pid'
} > /etc/ssh/sshd_config_kbd

# Gated instance: nothing listens on 2227 until knockd sees the sequence.
{
    echo 'Port 2227'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_gated.pid'
} > /etc/ssh/sshd_config_gated

/usr/sbin/sshd -f /etc/ssh/sshd_config_pwpub -E /var/log/sshd_pwpub.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_fwd -E /var/log/sshd_fwd.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_gated -E /var/log/sshd_gated.log

if [ -f /etc/pam.d/sshd ]; then
    /usr/sbin/sshd -f /etc/ssh/sshd_config_kbd -E /var/log/sshd_kbd.log
else
    echo "no PAM on this base: keyboard-interactive instance (:2226) not started"
fi

# Realistic port knocking: the gated sshd (:2227) is already listening, but
# the firewall DROPs new connections to it until knockd sees the UDP
# sequence and inserts a time-limited ACCEPT — exactly how a real knockd
# guards an always-running sshd, and (unlike spawning sshd on the knock)
# with no start-up race against the client's immediate dial.
if command -v iptables >/dev/null 2>&1 && iptables -N CONCH_GATE 2>/dev/null; then
    iptables -A INPUT -p tcp --dport 2227 -j CONCH_GATE
    iptables -A CONCH_GATE -j DROP
    gate_ok=1
else
    echo "iptables unavailable: gated instance (:2227) left open"
    gate_ok=0
fi

if [ "$gate_ok" = 1 ] && command -v knockd >/dev/null 2>&1; then
    cat > /etc/knockd.conf <<'KNOCK'
[options]
    logfile = /var/log/knockd.log
    interface = eth0

[openGate]
    sequence      = 2260:udp,2261:udp,2262:udp
    seq_timeout   = 5
    start_command = iptables -I CONCH_GATE 1 -s %IP% -p tcp --dport 2227 -j ACCEPT
    cmd_timeout   = 8
    stop_command  = iptables -D CONCH_GATE -s %IP% -p tcp --dport 2227 -j ACCEPT
KNOCK
    pkill knockd 2>/dev/null || true
    knockd -d -c /etc/knockd.conf || echo "knockd failed to start (pcap on eth0?)"
elif [ "$gate_ok" = 1 ]; then
    echo "no knockd on this base: gated instance (:2227) stays firewalled"
fi

exec /usr/sbin/sshd -D -f /etc/ssh/sshd_config_keyonly -E /var/log/sshd_keyonly.log
