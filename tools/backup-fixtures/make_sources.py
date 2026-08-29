#!/usr/bin/env python3
"""Writes the fixture payload SOURCES (full-v1.json, sparse-v1.json) in the
canonical JSON form the spec requires (sorted keys, no whitespace, raw
non-ASCII). Edit the dicts below, run this, then regen.sh."""
import json
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIB3z4kLp1o3Qy9Fh0mF4y2Nn1YQe4rZ1B3o5vE7d2mXU"
PEM = "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----\n"

FULL = {
    "exportedAt": "2026-08-29T05:30:00Z",
    "origin": {"platform": "android", "appVersion": "0.9.1"},
    "hosts": [
        {
            "id": "h-prod",
            "name": "生產機 prod",
            "hostname": "prod.example.com",
            "port": 2222,
            "username": "alice",
            "group": "Production",
            "auth": {"method": "key", "keyId": "k-phone"},
            "jumpHostId": "h-bastion",
            "knockPorts": [7000, 8000, 9000],
            "forwards": [
                {"type": "local", "listenPort": 8080, "targetHost": "db.internal", "targetPort": 5432},
                {"type": "remote", "listenHost": "0.0.0.0", "listenPort": 9000, "targetHost": "127.0.0.1", "targetPort": 9001},
                {"type": "dynamic", "listenPort": 1080},
            ],
            "fontSize": 14.5,
            "keepAlive": False,
            "tmuxAutoAttach": True,
            "forwardAgent": True,
            "exposeFiles": True,
        },
        {
            "id": "h-bastion",
            "name": "",
            "hostname": "bastion.example.com",
            "port": 22,
            "username": "alice",
            "group": "",
            "auth": {"method": "password", "password": "s3cret-パスワード🔑"},
            "knockPorts": [],
            "forwards": [],
            "keepAlive": True,
            "tmuxAutoAttach": False,
            "forwardAgent": False,
            "exposeFiles": False,
        },
        {
            "id": "h-v6",
            "name": "ipv6 box",
            "hostname": "2001:db8::10",
            "port": 22,
            "username": "root",
            "group": "",
            "auth": {"method": "password"},
            "knockPorts": [],
            "forwards": [],
            "keepAlive": True,
            "tmuxAutoAttach": True,
            "forwardAgent": False,
            "exposeFiles": False,
        },
    ],
    "keys": [
        {
            "id": "k-phone",
            "name": "my-phone",
            "algorithm": "ssh-ed25519",
            "createdAt": "2025-01-01T00:00:00.123Z",
            "publicKey": f"ssh-ed25519 {ED25519_BLOB} my-phone",
            "fingerprint": "SHA256:parityfixture",
            "privateKey": PEM,
        },
        {
            "id": "k-orphan",
            "name": "no-private-half",
            "algorithm": "ssh-rsa",
            "createdAt": "2025-06-01T12:00:00Z",
            "publicKey": "ssh-rsa AAAAB3NzaC1yc2E orphan",
            "fingerprint": "SHA256:orphan",
        },
    ],
    "snippets": [
        {"id": "s-disk", "label": "磁碟", "command": "df -h"},
        {"id": "s-multi", "label": "q\"uote", "command": "echo 'multi\nline' && ls /"},
    ],
    "knownHosts": [
        {"host": "prod.example.com", "port": 2222, "algorithm": "ssh-ed25519", "publicKey": ED25519_BLOB},
        {"host": "bastion.example.com", "port": 22, "algorithm": "ssh-ed25519", "publicKey": ED25519_BLOB},
        {"host": "2001:db8::10", "port": 22, "algorithm": "ssh-ed25519", "publicKey": ED25519_BLOB},
        {"host": "old.example.com", "port": 22, "algorithm": "ssh-ed25519", "publicKey": ED25519_BLOB, "marker": "revoked"},
    ],
}

# Minimal writer + forward-compat: every optional field absent, unknown keys everywhere.
SPARSE = {
    "hosts": [
        {"id": "h-min", "hostname": "min.example.com", "username": "bob", "futureField": {"nested": True}},
    ],
    "keys": [
        {"id": "k-min", "privateKey": PEM, "futureField": 1},
    ],
    "snippets": [{"id": "s-min", "futureField": None}],
    "knownHosts": [
        {"host": "min.example.com", "algorithm": "ssh-ed25519", "publicKey": ED25519_BLOB, "futureField": "x"},
    ],
    "futureSection": [1, 2, 3],
}


def canonical(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


for name, obj in (("full-v1", FULL), ("sparse-v1", SPARSE)):
    (HERE / f"{name}.json").write_text(canonical(obj) + "\n", encoding="utf-8")
    print(name, "written")
