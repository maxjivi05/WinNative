package com.winlator.cmod.feature.stores.steam.linux

/**
 * Parser for Valve's Steam Client manifest VDF format, as served from
 *     https://media.steampowered.com/client/steam_client_publicbeta_linuxarm64
 *
 * The format is a Valve KeyValues / VDF text document with one top-level
 * keyed block (e.g. `"linuxarm64" { ... }`) whose entries are either
 * scalar strings (`"version" "1777686219"`) or nested package blocks.
 *
 * Each package block has at least:
 *   "file"   = uncompressed zip filename, suffixed with the SHA-1 hash (and size)
 *   "sha2"   = SHA-256 of the uncompressed zip
 *   "zipvz"  = (optional) Valve LZMA1-wrapped variant — same content, smaller
 *   "sha2vz" = SHA-256 of the .zip.vz blob
 *   "size"   = size of the uncompressed zip
 *
 * We always prefer the .zip.vz variant when present (it's smaller; we
 * unwrap it locally with python-style LZMA1 raw decode). If absent, we
 * fall back to the plain .zip.
 *
 * Nested packages exist inside a top-level package for regional variants
 * (e.g. `_steamrow`, `_steamchina`). We ignore those and pull only the
 * top-level fields of the package itself.
 */
internal object LinuxSteamManifest {

    /**
     * One package row from the manifest. [filename] is whichever variant we'll
     * actually fetch (.zip.vz preferred). [sha256] verifies the downloaded blob.
     * [isVzWrapped] tells the unwrapper whether to LZMA-decode before treating
     * as a ZIP.
     */
    data class Package(
        val name: String,
        val filename: String,
        val sha256: String,
        val isVzWrapped: Boolean,
    )

    data class Manifest(
        val rootKey: String,
        val version: String,
        val packages: List<Package>,
    )

    fun parse(text: String): Manifest {
        val tokens = tokenize(text).iterator()
        val first = tokens.nextOrNull() ?: error("empty manifest")
        require(first.type == TokenType.STR) { "manifest must start with a string key" }
        val second = tokens.nextOrNull() ?: error("manifest truncated after root key")
        require(second.type == TokenType.LB) { "expected '{' after root key" }

        val rootKey = first.value
        val (version, packages) = parseRootBlock(tokens)
        return Manifest(rootKey = rootKey, version = version, packages = packages)
    }

    // ─── Internal: tokenizer + recursive parser ─────────────────────────

    private enum class TokenType { STR, LB, RB }

    private data class Token(val type: TokenType, val value: String)

    private fun tokenize(text: String): Sequence<Token> = sequence {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    // line comment
                    while (i < n && text[i] != '\n') i++
                }
                c == '{' -> {
                    yield(Token(TokenType.LB, ""))
                    i++
                }
                c == '}' -> {
                    yield(Token(TokenType.RB, ""))
                    i++
                }
                c == '"' -> {
                    val start = i + 1
                    var j = start
                    while (j < n && text[j] != '"') {
                        if (text[j] == '\\' && j + 1 < n) j += 2 else j++
                    }
                    yield(Token(TokenType.STR, text.substring(start, j)))
                    i = j + 1
                }
                else -> {
                    // bare token (rare in client manifests)
                    var j = i
                    while (j < n && !text[j].isWhitespace() && text[j] != '{' && text[j] != '}') j++
                    yield(Token(TokenType.STR, text.substring(i, j)))
                    i = j
                }
            }
        }
    }

    private fun Iterator<Token>.nextOrNull(): Token? = if (hasNext()) next() else null

    /**
     * Walks the root block (`{ ... }`) and collects every depth-1 keyed
     * block as a candidate package. Returns the manifest version + the
     * resolved package list.
     */
    private fun parseRootBlock(tokens: Iterator<Token>): Pair<String, List<Package>> {
        var version = ""
        val packages = mutableListOf<Package>()

        while (tokens.hasNext()) {
            val tok = tokens.next()
            if (tok.type == TokenType.RB) break
            require(tok.type == TokenType.STR) { "expected key, got $tok" }
            val key = tok.value

            val next = tokens.nextOrNull() ?: break
            when (next.type) {
                TokenType.STR -> {
                    if (key == "version") version = next.value
                    // ignore other top-level scalars (e.g. "ostype")
                }
                TokenType.LB -> {
                    // nested keyed block — likely a package
                    val pkg = parsePackageBlock(key, tokens)
                    if (pkg != null) packages.add(pkg)
                }
                TokenType.RB -> error("unexpected '}' after $key")
            }
        }
        return version to packages
    }

    /**
     * Walks a package block. We collect file/sha2/zipvz/sha2vz from the
     * top level only; if we hit a nested keyed block (regional variant)
     * we skip it entirely.
     */
    private fun parsePackageBlock(name: String, tokens: Iterator<Token>): Package? {
        var file: String? = null
        var sha2: String? = null
        var zipvz: String? = null
        var sha2vz: String? = null

        while (tokens.hasNext()) {
            val tok = tokens.next()
            if (tok.type == TokenType.RB) break
            require(tok.type == TokenType.STR) { "expected key in $name block" }
            val k = tok.value

            val next = tokens.nextOrNull() ?: break
            when (next.type) {
                TokenType.STR -> when (k) {
                    "file" -> file = next.value
                    "sha2" -> sha2 = next.value
                    "zipvz" -> zipvz = next.value
                    "sha2vz" -> sha2vz = next.value
                }
                TokenType.LB -> skipBlock(tokens) // regional variant — skip
                TokenType.RB -> error("unexpected '}' in $name after $k")
            }
        }

        return when {
            zipvz != null && sha2vz != null ->
                Package(name = name, filename = zipvz, sha256 = sha2vz, isVzWrapped = true)
            file != null && sha2 != null ->
                Package(name = name, filename = file, sha256 = sha2, isVzWrapped = false)
            else -> null // package block had no usable file/sha — skip
        }
    }

    private fun skipBlock(tokens: Iterator<Token>) {
        var depth = 1
        while (tokens.hasNext() && depth > 0) {
            val tok = tokens.next()
            when (tok.type) {
                TokenType.LB -> depth++
                TokenType.RB -> depth--
                else -> { /* skip */ }
            }
        }
    }
}
