#!/bin/sh
# Sets up auth-scenario users and runs the sshd instances of the matrix:
#   :2223 pw+pubkey, :2224 pubkey-only, :2225 pw+pubkey with forwarding,
#   :2226 keyboard-interactive via PAM (only where PAM is installed),
#   :2227 "gated": firewalled until knockd sees the UDP sequence 2260,2261,2262
#         (only where iptables + knockd are installed),
#   :2228 "hardened": Banner, MaxSessions 2, PermitOpen to the inner sshd
#         only, a CA-trusted certificate user, authorized_keys option users
#         (forced command / restrict / no-pty) and a chrooted SFTP-only user,
#   :2229 "strict": MaxAuthTries 1, shell channels reaped after 12 s idle
#         (ChannelTimeouts, OpenSSH ≥ 9.2) and idle transports 5 s later,
#   :2230 ecdsa host key only, :2231 rsa host key only (DEBUG1 log),
#   :2232 "legacy": SHA-1 kex + CBC ciphers + ssh-rsa only, the shape of an
#         old appliance,
#   :2270 accepts TCP and never speaks, :2271 sends an SSH banner and then
#         stalls forever (handshake-timeout fixtures; plain nc, no sshd).
# Expects /keys/{keyA.pub,keyB.pub,keyRSA.pub,keyECDSA.pub,ca.pub} mounted
# read-only; optional keySK.pub.
#
# Idempotent: `docker restart` re-runs this on a filesystem where the users
# and host keys already exist — the reconnect tests depend on the host keys
# SURVIVING a restart (a changed key must fail TOFU, a restart must not).
set -eu

mkdir -p /run/sshd

ensure_user() {
    id "$1" >/dev/null 2>&1 || useradd -m -s /bin/sh "$1"
}
for u in pwuser bothuser keyuser skuser certuser cmduser restrictuser noptyuser sftponly; do
    ensure_user "$u"
done

# An account with no password set would be "locked" ('!' in shadow); sshd
# then refuses even pubkey auth ("account is locked"). Set an
# unmatchable-but-unlocked hash instead — same as real hardened servers.
for u in keyuser skuser certuser cmduser restrictuser noptyuser; do
    echo "$u:*" | chpasswd -e
done

echo 'pwuser:conch-pw-1' | chpasswd
echo 'bothuser:conch-pw-2' | chpasswd
echo 'sftponly:conch-pw-3' | chpasswd

# setup_ak USER [OPTIONS] KEY.pub...  — OPTIONS (may be empty) is the
# authorized_keys option prefix applied to every listed key.
setup_ak() {
    user="$1"; opts="$2"; shift 2
    install -d -m 700 -o "$user" -g "$user" "/home/$user/.ssh"
    : > "/home/$user/.ssh/authorized_keys"
    for key in "$@"; do
        [ -f "$key" ] || continue
        if [ -n "$opts" ]; then printf '%s ' "$opts"; fi >> "/home/$user/.ssh/authorized_keys"
        cat "$key" >> "/home/$user/.ssh/authorized_keys"
    done
    chown "$user:$user" "/home/$user/.ssh/authorized_keys"
    chmod 600 "/home/$user/.ssh/authorized_keys"
}
setup_ak bothuser "" /keys/keyA.pub
# keyuser accepts every client key algorithm the app can generate/import
setup_ak keyuser "" /keys/keyB.pub /keys/keyRSA.pub /keys/keyECDSA.pub
# FIDO2 scenario: a security-key public key (sk-ssh-ed25519@openssh.com) in
# authorized_keys. No token exists in the test harness, so nobody can log in
# as skuser — what is exercised is that sshd accepts the key type without
# choking and that the app's failure path is a clean auth rejection.
setup_ak skuser "" /keys/keySK.pub
# authorized_keys option users (hardened-server shapes), all with keyA:
#   cmduser      command="..."  every exec/shell runs the forced command
#   restrictuser restrict,pty   pty allowed, no forwarding / agent / X11
#   noptyuser    no-pty         exec works, pty-req is refused
setup_ak cmduser 'command="echo FORCED_COMMAND_ONLY"' /keys/keyA.pub
setup_ak restrictuser 'restrict,pty' /keys/keyA.pub
setup_ak noptyuser 'no-pty' /keys/keyA.pub
# certuser has NO authorized_keys: only a certificate signed by /keys/ca
# (TrustedUserCAKeys on :2228, principal "certuser") gets in.

# Chrooted SFTP-only account (:2228 Match block): the chroot itself must be
# root-owned and not group/world-writable; the user writes under /upload.
install -d -m 755 -o root -g root /srv/sftp /srv/sftp/sftponly
install -d -m 755 -o sftponly -g sftponly /srv/sftp/sftponly/upload
[ -f /srv/sftp/sftponly/upload/README ] || echo "chroot upload dir" > /srv/sftp/sftponly/upload/README
chown sftponly:sftponly /srv/sftp/sftponly/upload/README

printf 'conch matrix banner line 1\nauthorized use only\n' > /etc/ssh/banner.txt

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

# Everything below is what every instance shares; instance-specific lines
# come FIRST in each file because OpenSSH is first-match-wins.
common_cfg='
PermitRootLogin no
AllowUsers pwuser bothuser keyuser skuser certuser cmduser restrictuser noptyuser sftponly
PubkeyAuthentication yes
StrictModes yes
AllowTcpForwarding no
X11Forwarding no
PrintMotd no
Subsystem sftp internal-sftp
LogLevel INFO
'
hostkeys_all='
HostKey /etc/ssh/ssh_host_ed25519_key
HostKey /etc/ssh/ssh_host_rsa_key
'

{
    echo 'Port 2223'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo "$hostkeys_all"
    echo "$common_cfg"
    echo 'PidFile /run/sshd_pwpub.pid'
} > /etc/ssh/sshd_config_pwpub

{
    echo 'Port 2224'
    echo 'PasswordAuthentication no'
    echo 'KbdInteractiveAuthentication no'
    echo "$hostkeys_all"
    echo "$common_cfg"
    echo 'PidFile /run/sshd_keyonly.pid'
} > /etc/ssh/sshd_config_keyonly

{
    echo 'Port 2225'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo 'AllowTcpForwarding yes'
    echo "$hostkeys_all"
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
    echo "$hostkeys_all"
    echo "$common_cfg"
    echo 'PidFile /run/sshd_kbd.pid'
} > /etc/ssh/sshd_config_kbd

# Gated instance: nothing listens on 2227 until knockd sees the sequence.
{
    echo 'Port 2227'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo "$hostkeys_all"
    echo "$common_cfg"
    echo 'PidFile /run/sshd_gated.pid'
} > /etc/ssh/sshd_config_gated

# Hardened: the policy knobs real admins turn. PermitOpen lets tunnels reach
# only the inner sshd; MaxSessions 2 = shell + one more channel; the CA
# makes certificate auth possible for certuser; the Match block turns
# sftponly into a chrooted SFTP-only account.
{
    echo 'Port 2228'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo 'AllowTcpForwarding yes'
    echo 'PermitOpen 127.0.0.1:2223'
    echo 'MaxSessions 2'
    echo 'Banner /etc/ssh/banner.txt'
    echo 'TrustedUserCAKeys /keys/ca.pub'
    echo "$hostkeys_all"
    echo "$common_cfg"
    echo 'PidFile /run/sshd_hardened.pid'
    echo 'Match User sftponly'
    echo '    ChrootDirectory /srv/sftp/%u'
    echo '    ForceCommand internal-sftp'
    echo '    AllowTcpForwarding no'
    echo '    PermitTTY no'
} > /etc/ssh/sshd_config_hardened

# Strict: one auth attempt, idle shells reaped by the server, idle
# transports closed. ChannelTimeouts / UnusedConnectionTimeout need
# OpenSSH 9.2; on older bases the instance runs without them.
{
    echo 'Port 2229'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo 'MaxAuthTries 1'
    if sshd -T -f /dev/null -o ChannelTimeouts=session:shell=12s >/dev/null 2>&1; then
        echo 'ChannelTimeouts session:shell=12s session:command=12s'
        echo 'UnusedConnectionTimeout 5s'
    else
        echo "# ChannelTimeouts unsupported by $(sshd -V 2>&1 | head -1)" >&2
    fi
    echo "$hostkeys_all"
    echo "$common_cfg"
    echo 'PidFile /run/sshd_strict.pid'
} > /etc/ssh/sshd_config_strict

{
    echo 'Port 2230'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo 'HostKey /etc/ssh/ssh_host_ecdsa_key'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_ecdsa.pid'
} > /etc/ssh/sshd_config_ecdsa

{
    echo 'Port 2231'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo 'HostKey /etc/ssh/ssh_host_rsa_key'
    echo 'LogLevel DEBUG1'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_rsa.pid'
} > /etc/ssh/sshd_config_rsa

# Legacy appliance: SHA-1 kex, CBC ciphers, SHA-1 MAC, SHA-1 RSA host-key
# signature — every algorithm modern sshd disables by default. OpenSSH 10
# has dropped some of them entirely; the instance then does not start.
{
    echo 'Port 2232'
    echo 'PasswordAuthentication yes'
    echo 'KbdInteractiveAuthentication no'
    echo 'HostKey /etc/ssh/ssh_host_rsa_key'
    echo 'KexAlgorithms diffie-hellman-group14-sha1'
    echo 'Ciphers aes256-cbc,aes128-cbc'
    echo 'MACs hmac-sha1'
    echo 'HostKeyAlgorithms ssh-rsa'
    echo "$common_cfg"
    echo 'PidFile /run/sshd_legacy.pid'
} > /etc/ssh/sshd_config_legacy

/usr/sbin/sshd -f /etc/ssh/sshd_config_pwpub -E /var/log/sshd_pwpub.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_fwd -E /var/log/sshd_fwd.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_gated -E /var/log/sshd_gated.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_hardened -E /var/log/sshd_hardened.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_strict -E /var/log/sshd_strict.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_ecdsa -E /var/log/sshd_ecdsa.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_rsa -E /var/log/sshd_rsa.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_legacy -E /var/log/sshd_legacy.log \
    || echo "legacy instance (:2232) not started: this OpenSSH no longer offers SHA-1/CBC"

if [ -f /etc/pam.d/sshd ]; then
    /usr/sbin/sshd -f /etc/ssh/sshd_config_kbd -E /var/log/sshd_kbd.log
else
    echo "no PAM on this base: keyboard-interactive instance (:2226) not started"
fi

# Handshake-timeout fixtures: a port that accepts and never speaks, and one
# that sends a banner and then stalls. One client at a time each; the
# loops restart nc after every client. stdin never reaches EOF so nc never
# half-closes the socket — the client must give up on its own.
(while :; do sleep 2147483647 | nc -l 2270 >/dev/null 2>&1; done) &
(while :; do (printf 'SSH-2.0-OpenSSH_9.2 conch-stall\r\n'; sleep 2147483647) | nc -l 2271 >/dev/null 2>&1; done) &

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
