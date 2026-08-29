package at.least.conch

import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for every on-disk / wire format (hosts.json,
 * snippets.json, command_history.bin plaintext, docker NDJSON). The CONCHBAK
 * backup payload has its own Json in BackupCodec. These settings ARE the format contract:
 *
 *  - encodeDefaults: every field is written unconditionally (org.json
 *    JSONObject.put semantics; iOS parity readers rely on the full shape)
 *  - ignoreUnknownKeys: unknown fields from newer versions/foreign docker
 *    output are skipped, not fatal (org.json opt* semantics)
 *  - coerceInputValues: explicit nulls fall back to property defaults
 *
 * Wire format is pinned by GoldenFormatTest; changes must pass it byte-for-byte
 * (canonical form).
 */
val ConchJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}
