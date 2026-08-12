#!/usr/bin/env python3
"""Download one catalog-pinned model artifact outside the AIOS source tree."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import BinaryIO, Callable
from urllib.parse import unquote, urlparse
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "config" / "model_catalog.json"
BUFFER_BYTES = 1024 * 1024
SAFE_FILENAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
DIGEST = re.compile(r"[0-9a-f]{64}")


class BootstrapError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(BUFFER_BYTES):
            digest.update(block)
    return digest.hexdigest()


def load_reference(catalog_path: Path, model_id: str) -> tuple[str, str, str]:
    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BootstrapError(f"cannot read model catalog: {error}") from error
    models = catalog.get("models")
    if catalog.get("schema_version") != 1 or not isinstance(models, list):
        raise BootstrapError("unsupported model catalog")
    matches = [item for item in models
               if isinstance(item, dict) and item.get("id") == model_id]
    if len(matches) != 1:
        raise BootstrapError(f"unknown or duplicate catalog model: {model_id}")
    reference = matches[0].get("reference_artifact")
    if not isinstance(reference, dict):
        raise BootstrapError(f"{model_id}: catalog has no single-file reference artifact")
    url = reference.get("url")
    expected = reference.get("sha256")
    if not isinstance(url, str) or not url.startswith("https://") \
            or not isinstance(expected, str) or DIGEST.fullmatch(expected) is None:
        raise BootstrapError(f"{model_id}: malformed reference artifact lock")
    filename = unquote(Path(urlparse(url).path).name)
    if SAFE_FILENAME.fullmatch(filename) is None:
        raise BootstrapError(f"{model_id}: unsafe reference filename")
    return url, expected, filename


def external_directory(output_directory: Path, source_root: Path = ROOT) -> Path:
    if output_directory.exists() and output_directory.is_symlink():
        raise BootstrapError("model output directory must not be a symbolic link")
    root = source_root.resolve()
    output = output_directory.resolve()
    if output == root or root in output.parents:
        raise BootstrapError("model output directory must be outside the AIOS source tree")
    output.mkdir(parents=True, exist_ok=True)
    if not output.is_dir() or output.is_symlink():
        raise BootstrapError("model output directory must be a real directory")
    return output


def _copy_response(response: BinaryIO, partial: Path, append: bool) -> None:
    mode = "ab" if append else "wb"
    with partial.open(mode) as destination:
        while block := response.read(BUFFER_BYTES):
            destination.write(block)
        destination.flush()
        os.fsync(destination.fileno())


def download_reference(
        catalog_path: Path,
        model_id: str,
        output_directory: Path,
        opener: Callable[..., BinaryIO] = urlopen,
        source_root: Path = ROOT,
) -> dict[str, object]:
    url, expected_digest, filename = load_reference(catalog_path, model_id)
    output = external_directory(output_directory, source_root)
    destination = output / filename
    partial = output / f"{filename}.partial"
    lock = output / f"{filename}.download.lock"
    for path in (destination, partial, lock):
        if path.is_symlink():
            raise BootstrapError(f"refusing symbolic-link download path: {path.name}")
    if destination.exists():
        if not destination.is_file() or sha256(destination) != expected_digest:
            raise BootstrapError(f"existing model artifact does not match catalog: {destination}")
        return {
            "model_id": model_id,
            "path": str(destination),
            "sha256": expected_digest,
            "size_bytes": destination.stat().st_size,
            "downloaded": False,
        }

    try:
        lock_fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as error:
        raise BootstrapError(
            f"another download may be active; inspect and remove stale lock: {lock}") from error
    try:
        with os.fdopen(lock_fd, "w", encoding="ascii") as lock_stream:
            lock_stream.write(f"pid={os.getpid()}\n")
            lock_stream.flush()
            os.fsync(lock_stream.fileno())

        offset = partial.stat().st_size if partial.exists() else 0
        headers = {"User-Agent": "AIOS-model-bootstrap/1"}
        if offset > 0:
            headers["Range"] = f"bytes={offset}-"
        request = Request(url, headers=headers)
        try:
            response_context = opener(request, timeout=60)
            with response_context as response:
                status = int(getattr(response, "status", 200))
                append = offset > 0 and status == 206
                if status not in {200, 206}:
                    raise BootstrapError(f"model server returned HTTP {status}")
                if status == 206:
                    content_range = response.headers.get("Content-Range", "")
                    if not content_range.startswith(f"bytes {offset}-"):
                        raise BootstrapError("model server returned an invalid resume range")
                _copy_response(response, partial, append)
        except BootstrapError:
            raise
        except OSError as error:
            raise BootstrapError(f"model download failed: {error}") from error

        actual_digest = sha256(partial)
        if actual_digest != expected_digest:
            raise BootstrapError(
                "downloaded artifact digest mismatch; partial file was preserved for inspection")
        os.replace(partial, destination)
        return {
            "model_id": model_id,
            "path": str(destination),
            "sha256": expected_digest,
            "size_bytes": destination.stat().st_size,
            "downloaded": True,
        }
    finally:
        try:
            lock.unlink()
        except FileNotFoundError:
            pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    arguments = parser.parse_args(argv)
    try:
        result = download_reference(
            arguments.catalog, arguments.model_id, arguments.output_directory)
    except (BootstrapError, OSError) as error:
        print(f"model bootstrap failed: {error}", file=sys.stderr)
        return 1
    action = "downloaded" if result["downloaded"] else "already verified"
    print(f"{action}: {result['path']} ({result['size_bytes']} bytes, {result['sha256']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
