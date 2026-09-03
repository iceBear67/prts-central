#!/usr/bin/env python3
"""Offline management of prts-central project secrets.

Reimplements the ``<format>:<keyId>:<base64(iv || ciphertext || tag)>`` envelope of
``io.ib67.prts.secret.SecretCipher``, so the two have to be changed together.

Rotation lives here rather than in the service because the service only ever seals under
``secret.active-key`` and never needs a retired key for anything but reading one row at a time,
while a rotation needs every key at once and rewrites the whole table. Talks to PostgreSQL through
``psql`` rather than a driver, so there is nothing to install and the file can be copied to wherever
the deployment's ``application.yml`` and database are reachable.

    ./secrets.py genkey                 # a key for secret.keys
    ./secrets.py keys                   # what is configured vs. what rows still name
    ./secrets.py list
    ./secrets.py get <project> <name>
    ./secrets.py rotate [--dry-run]

Reads ``./application.yml`` and its ``%dev`` block; ``--config`` / ``--profile`` / ``--db-url`` /
``--key`` / ``--active-key`` override that.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import parse_qsl, quote, urlsplit

try:
    import yaml
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError as missing:  # pragma: no cover
    sys.exit(f"{missing.name} is required: pip install pyyaml cryptography")

# Kept in step with SecretCipher by hand; a mismatch here is a mismatch there.
FORMAT = "1"
SEPARATOR = ":"
IV_LENGTH = 12
TAG_LENGTH = 16
KEY_ID = re.compile(r"[A-Za-z0-9_-]{1,32}\Z")
KEY_LENGTHS = (16, 24, 32)

TABLE = "project_secret"
# Relative to the working directory, not to this file: the script is meant to be copied next to the
# deployment's own application.yml and run there, away from this repository.
DEFAULT_CONFIG = Path("application.yml")
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\Z")


class SecretError(Exception):
    pass


# --- envelope ---------------------------------------------------------------------------------


@dataclass(frozen=True)
class Envelope:
    header: str
    key_id: str
    iv: bytes
    sealed: bytes


def parse(stored: str) -> Envelope:
    parts = stored.split(SEPARATOR, 2)
    if len(parts) != 3 or parts[0] != FORMAT or not KEY_ID.match(parts[1]):
        raise SecretError(f"not a format {FORMAT} envelope: {stored[:24]!r}...")
    try:
        payload = base64.b64decode(parts[2], validate=True)
    except (ValueError, binascii.Error) as e:
        raise SecretError("envelope payload is not base64") from e
    if len(payload) <= IV_LENGTH + TAG_LENGTH:
        raise SecretError("envelope payload is truncated")
    return Envelope(
        header=parts[0] + SEPARATOR + parts[1],
        key_id=parts[1],
        iv=payload[:IV_LENGTH],
        sealed=payload[IV_LENGTH:],
    )


def aad(header: str, context: str) -> bytes:
    """The header as stored, so relabelling the format or the key id breaks the tag."""
    return (header + SEPARATOR + context).encode()


def context_of(project_id: str, name: str) -> str:
    """SecretService.contextOf: a row cannot be moved to another project or name."""
    return f"{project_id}/{name}"


def open_value(keys: dict[str, bytes], stored: str, context: str) -> str:
    envelope = parse(stored)
    key = keys.get(envelope.key_id)
    if key is None:
        raise SecretError(f"no secret.keys entry named {envelope.key_id}")
    try:
        return AESGCM(key).decrypt(envelope.iv, envelope.sealed, aad(envelope.header, context)).decode()
    except Exception as e:  # never the message: it would describe the value or the key
        raise SecretError(f"failed to open a secret sealed under {envelope.key_id}") from e


def seal(keys: dict[str, bytes], key_id: str, plaintext: str, context: str) -> str:
    iv = os.urandom(IV_LENGTH)
    header = FORMAT + SEPARATOR + key_id
    sealed = AESGCM(keys[key_id]).encrypt(iv, plaintext.encode(), aad(header, context))
    return header + SEPARATOR + base64.b64encode(iv + sealed).decode()


def key_of(key_id: str, material: str) -> bytes:
    if not KEY_ID.match(key_id):
        raise SecretError(f"a secret.keys id must match {KEY_ID.pattern}, got {key_id!r}")
    try:
        decoded = base64.b64decode(material.strip(), validate=True)
    except (ValueError, binascii.Error) as e:
        raise SecretError(f"secret.keys.{key_id} is not base64") from e
    if len(decoded) not in KEY_LENGTHS:
        raise SecretError(
            f"secret.keys.{key_id} must decode to 16, 24 or 32 bytes, got {len(decoded)}"
        )
    return decoded


# --- application.yml --------------------------------------------------------------------------


ENV_REF = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}")


def expand(node: Any) -> Any:
    """SmallRye's ``${VAR}`` / ``${VAR:default}``, so a %prod block resolves the same way it would."""
    if isinstance(node, str):
        def one(m: re.Match[str]) -> str:
            value = os.environ.get(m.group(1), m.group(2))
            if value is None:
                raise SecretError(f"{m.group(0)} is referenced by the config but not set")
            return value

        return ENV_REF.sub(one, node)
    if isinstance(node, dict):
        return {k: expand(v) for k, v in node.items()}
    if isinstance(node, list):
        return [expand(v) for v in node]
    return node


def merge(into: dict, other: dict) -> dict:
    for key, value in other.items():
        if isinstance(value, dict) and isinstance(into.get(key), dict):
            merge(into[key], value)
        else:
            into[key] = value
    return into


def load_config(path: Path, profile: str) -> dict:
    if not path.is_file():
        raise SecretError(f"no {path} in {Path.cwd()}; pass --config")
    document = yaml.safe_load(path.read_text()) or {}
    config = {k: v for k, v in document.items() if not k.startswith("%")}
    merge(config, document.get(f"%{profile}") or {})
    return expand(config)


def lookup(config: dict, path: str) -> Any:
    """Walks dotted segments, trying the remaining path as a literal key too: yaml may nest or flatten."""
    node: Any = config
    segments = path.split(".")
    for i, segment in enumerate(segments):
        if not isinstance(node, dict):
            return None
        rest = ".".join(segments[i:])
        if rest in node:
            return node[rest]
        if segment not in node:
            return None
        node = node[segment]
    return node


def keyring(config: dict, args: argparse.Namespace) -> tuple[dict[str, bytes], str]:
    raw = dict(lookup(config, "secret.keys") or {})
    for override in args.key:
        key_id, _, material = override.partition("=")
        if not material:
            raise SecretError(f"--key wants ID=BASE64, got {override!r}")
        raw[key_id] = material
    keys = {key_id: key_of(str(key_id), str(material)) for key_id, material in raw.items()}
    active = str(args.active_key or lookup(config, "secret.active-key") or "").strip()
    if not keys:
        raise SecretError("no secret.keys in the config; pass --key ID=BASE64 for a key kept out of it")
    if active not in keys:
        raise SecretError(f"secret.active-key is {active!r}, which is not one of {sorted(keys)}")
    return keys, active


# --- postgres ---------------------------------------------------------------------------------


@dataclass(frozen=True)
class Dsn:
    host: str
    port: str
    database: str
    user: str
    password: str | None

    def uri(self) -> str:
        return f"postgresql://{quote(self.user)}@{self.host}:{self.port}/{quote(self.database)}"


def dsn_of(config: dict, override: str | None) -> Dsn:
    url = override or lookup(config, "quarkus.datasource.jdbc.url")
    if not url:
        raise SecretError("no quarkus.datasource.jdbc.url in the config; pass --db-url")
    split = urlsplit(str(url).removeprefix("jdbc:"))
    if split.scheme not in ("postgresql", "postgres"):
        raise SecretError(f"not a postgresql url: {url}")
    query = dict(parse_qsl(split.query))
    user = split.username or query.get("user") or lookup(config, "quarkus.datasource.username") or "postgres"
    # An empty path is what the dev url has; the server then resolves the database to the user name.
    database = split.path.lstrip("/") or user
    return Dsn(
        host=split.hostname or "localhost",
        port=str(split.port or 5432),
        database=database,
        user=str(user),
        password=split.password
        or query.get("password")
        or lookup(config, "quarkus.datasource.password"),
    )


def psql(dsn: Dsn, sql: str) -> str:
    env = dict(os.environ)
    if dsn.password:
        env["PGPASSWORD"] = dsn.password
    result = subprocess.run(
        ["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-d", dsn.uri(), "-f", "-"],
        input=sql,
        capture_output=True,
        text=True,
        env=env,
    )
    if result.returncode != 0:
        raise SecretError(f"psql failed:\n{result.stderr.strip()}")
    if result.stderr.strip():
        print(result.stderr.strip(), file=sys.stderr)
    return result.stdout


def sql_literal(value: str) -> str:
    if "\x00" in value:
        raise SecretError("a value contains a NUL byte")
    return "'" + value.replace("'", "''") + "'"


@dataclass(frozen=True)
class Row:
    project_id: str
    name: str
    description: str | None
    cipher_text: str
    created_at: str

    @property
    def context(self) -> str:
        return context_of(self.project_id, self.name)

    @property
    def key_id(self) -> str:
        try:
            return parse(self.cipher_text).key_id
        except SecretError:
            return "?"


def fetch(dsn: Dsn, project: str | None) -> list[Row]:
    where = ""
    if project:
        if not UUID.match(project):
            raise SecretError(f"not a uuid: {project}")
        where = f"WHERE project_id = {sql_literal(project)}"
    # Read as one JSON document: a description may hold newlines, which -A -t output would not survive.
    out = psql(
        dsn,
        f"""
        SELECT coalesce(json_agg(json_build_object(
                   'project_id', project_id, 'name', name, 'description', description,
                   'cipher_text', cipher_text, 'created_at', created_at
               ) ORDER BY project_id, name)::text, '[]')
        FROM {TABLE} {where};
        """,
    )
    return [Row(**row) for row in json.loads(out.strip() or "[]")]


# --- commands ---------------------------------------------------------------------------------


def cmd_genkey(args: argparse.Namespace) -> int:
    if args.bytes not in KEY_LENGTHS:
        raise SecretError(f"--bytes must be one of {KEY_LENGTHS}")
    material = base64.b64encode(os.urandom(args.bytes)).decode()
    print(material)
    print(
        f"add it to secret.keys under a new id, point secret.active-key at that id, then run"
        f" `{Path(__file__).name} rotate`",
        file=sys.stderr,
    )
    return 0


def cmd_keys(args: argparse.Namespace) -> int:
    config = load_config(args.config, args.profile)
    keys, active = keyring(config, args)
    counts: dict[str, int] = {}
    for row in fetch(dsn_of(config, args.db_url), args.project):
        counts[row.key_id] = counts.get(row.key_id, 0) + 1
    print(f"{'KEY':<20} {'ROWS':>5}  STATUS")
    for key_id in sorted(set(keys) | set(counts)):
        rows = counts.get(key_id, 0)
        if key_id not in keys:
            status = "MISSING from secret.keys — those rows cannot be opened"
        elif key_id == active:
            status = "active"
        elif rows:
            status = "retired, still in use — rotate before dropping it"
        else:
            status = "retired, droppable"
        print(f"{key_id:<20} {rows:>5}  {status}")
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    config = load_config(args.config, args.profile)
    rows = fetch(dsn_of(config, args.db_url), args.project)
    if not rows:
        print("no secrets", file=sys.stderr)
        return 0
    print(f"{'PROJECT':<38} {'NAME':<24} {'KEY':<10} {'CREATED':<30} DESCRIPTION")
    for row in rows:
        description = (row.description or "").replace("\n", " ")
        print(f"{row.project_id:<38} {row.name:<24} {row.key_id:<10} {row.created_at:<30} {description}")
    return 0


def cmd_get(args: argparse.Namespace) -> int:
    config = load_config(args.config, args.profile)
    keys, _ = keyring(config, args)
    rows = [
        row
        for row in fetch(dsn_of(config, args.db_url), args.project_id)
        if row.name == args.name
    ]
    if not rows:
        raise SecretError(f"project {args.project_id} has no secret named {args.name}")
    print(open_value(keys, rows[0].cipher_text, rows[0].context))
    return 0


def cmd_rotate(args: argparse.Namespace) -> int:
    config = load_config(args.config, args.profile)
    keys, active = keyring(config, args)
    dsn = dsn_of(config, args.db_url)

    updates: list[tuple[Row, str]] = []
    broken: list[tuple[Row, SecretError]] = []
    for row in fetch(dsn, args.project):
        if row.key_id == active:
            continue
        try:
            plaintext = open_value(keys, row.cipher_text, row.context)
            resealed = seal(keys, active, plaintext, row.context)
            # Read back before writing: a key that seals but does not open must not reach the table.
            if open_value(keys, resealed, row.context) != plaintext:
                raise SecretError("re-sealed value did not open to the same plaintext")
        except SecretError as e:
            broken.append((row, e))
            continue
        updates.append((row, resealed))

    for row, error in broken:
        print(f"{row.project_id}/{row.name}: {error}", file=sys.stderr)
    if broken and not args.skip_broken:
        raise SecretError(
            f"{len(broken)} secret(s) could not be opened; add the key they name, or pass"
            " --skip-broken to rotate the rest"
        )

    if not updates:
        print(f"nothing to rotate: every secret is already sealed under {active}", file=sys.stderr)
        return 1 if broken else 0
    if args.dry_run:
        for row, _ in updates:
            print(f"would re-seal {row.project_id}/{row.name} from {row.key_id} to {active}")
        return 0

    # One transaction, and each update carries the ciphertext it read as a compare-and-set: a row
    # rewritten by the service in the meantime is left alone rather than reverted.
    statements = [
        "BEGIN;",
        "SET standard_conforming_strings = on;",  # so a backslash in a value stays a backslash
    ]
    for row, resealed in updates:
        statements.append(
            f"UPDATE {TABLE} SET cipher_text = {sql_literal(resealed)}"
            f" WHERE project_id = {sql_literal(row.project_id)}"
            f" AND name = {sql_literal(row.name)}"
            f" AND cipher_text = {sql_literal(row.cipher_text)};"
        )
    statements.append("COMMIT;")
    psql(dsn, "\n".join(statements))

    print(f"re-sealed {len(updates)} secret(s) under {active}", file=sys.stderr)
    # The distribution is the answer to "can I drop the old key now", and re-reads what was written.
    return cmd_keys(args)


# --- cli --------------------------------------------------------------------------------------


def add_common(parser: argparse.ArgumentParser, *, needs_project: bool = False) -> None:
    parser.add_argument(
        "--config", type=Path, default=DEFAULT_CONFIG, help=f"config to read (default: ./{DEFAULT_CONFIG})"
    )
    parser.add_argument("--profile", default="dev", help="which %%<profile> block to merge in")
    parser.add_argument("--db-url", help="overrides quarkus.datasource.jdbc.url")
    parser.add_argument(
        "--key",
        action="append",
        default=[],
        metavar="ID=BASE64",
        help="adds or overrides a secret.keys entry, for a key kept out of the config file",
    )
    parser.add_argument("--active-key", help="overrides secret.active-key")
    if not needs_project:
        parser.add_argument("--project", help="limit to one project id")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    genkey = commands.add_parser("genkey", help="print a new base64 AES key for secret.keys")
    genkey.add_argument("--bytes", type=int, default=32, choices=KEY_LENGTHS, help="key size")
    genkey.set_defaults(run=cmd_genkey)

    keys = commands.add_parser("keys", help="which keys are configured and what rows still name them")
    add_common(keys)
    keys.set_defaults(run=cmd_keys)

    listing = commands.add_parser("list", help="the secrets, without their values")
    add_common(listing)
    listing.set_defaults(run=cmd_list)

    get = commands.add_parser("get", help="print one secret in the clear")
    get.add_argument("project_id")
    get.add_argument("name")
    add_common(get, needs_project=True)
    get.set_defaults(run=cmd_get)

    rotate = commands.add_parser("rotate", help="re-seal every secret under secret.active-key")
    add_common(rotate)
    rotate.add_argument("--dry-run", action="store_true", help="open and re-seal, but write nothing")
    rotate.add_argument(
        "--skip-broken", action="store_true", help="rotate the rest when a row cannot be opened"
    )
    rotate.set_defaults(run=cmd_rotate)

    args = parser.parse_args(list(argv) if argv is not None else None)
    try:
        return args.run(args)
    except SecretError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
