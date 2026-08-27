#!/bin/sh
# Sets up auth-scenario users and runs three sshd instances:
#   :2223 pw+pubkey, :2224 pubkey-only, :2225 pw+pubkey with forwarding.
# Expects /keys/{keyA.pub,keyB.pub} mounted read-only.
# lrzsz (rz/sz) and tmux are installed for ZMODEM / tmux integration tests.
set -eu

mkdir -p /run/sshd

useradd -m -s /bin/sh pwuser
useradd -m -s /bin/sh bothuser
useradd -m -s /bin/sh keyuser

# An account with no password set would be "locked" ('!' in shadow); sshd
# then refuses even pubkey auth ("account is locked"). Set an
# unmatchable-but-unlocked hash instead — same as real hardened servers.
echo 'keyuser:*' | chpasswd -e

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

ssh-keygen -A

common_cfg='
HostKey /etc/ssh/ssh_host_ed25519_key
HostKey /etc/ssh/ssh_host_rsa_key
PermitRootLogin no
AllowUsers pwuser bothuser keyuser
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

/usr/sbin/sshd -f /etc/ssh/sshd_config_pwpub -E /var/log/sshd_pwpub.log
/usr/sbin/sshd -f /etc/ssh/sshd_config_fwd -E /var/log/sshd_fwd.log
exec /usr/sbin/sshd -D -f /etc/ssh/sshd_config_keyonly -E /var/log/sshd_keyonly.log
