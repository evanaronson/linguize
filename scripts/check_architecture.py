#!/usr/bin/env python3
"""Check production Kotlin imports against the package boundaries in README.md.

This is an import guard, not a Kotlin compiler or a transitive dependency analyzer.
It deliberately treats ui as one layer: hosts may share controls and localized text.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys


APP_PACKAGE = "com.evanaronson.linguize"
SOURCE_ROOT = Path("app/src/main/java")
PACKAGE_PATH = Path(*APP_PACKAGE.split("."))

# Each layer may import itself and the listed lower layers. Generated resources are
# separate from the composition root so data can use R without depending on App.
ALLOWED = {
    "codec": {"codec"},
    "core": {"core", "codec"},
    "llm": {"llm", "core", "codec"},
    "history": {"history", "core", "codec"},
    "data": {"data", "core", "codec", "llm", "resources"},
    "root": {"root", "core", "codec", "llm", "history", "data", "resources"},
    "ui": {"ui", "root", "core", "codec", "llm", "history", "data", "resources"},
    "menu": {"menu", "ui", "root", "core", "codec", "llm", "history", "data", "resources"},
    "accessibility": {"accessibility", "ui", "root", "core", "codec", "llm", "history", "data", "resources"},
}
ANDROID_PACKAGES = ("android", "androidx", "com.android", "kotlinx.coroutines.android")
DECLARATION = re.compile(r"^\s*(package|import)\s+([\w.*]+)(?:\s+as\s+\w+)?\s*;?\s*$")


def declarations(source: str):
    """Read header declarations, ignoring comments (including nested Kotlin comments).

    Stop at the first code declaration, so import-looking lines in a function, KDoc
    example or a raw string body cannot be mistaken for actual imports.
    """
    depth = 0
    for number, line in enumerate(source.splitlines(), 1):
        clean = []
        index = 0
        while index < len(line):
            pair = line[index:index + 2]
            if pair == "/*":
                depth += 1
                index += 2
            elif depth and pair == "*/":
                depth -= 1
                index += 2
            elif depth:
                index += 1
            elif pair == "//":
                break
            else:
                clean.append(line[index])
                index += 1
        content = "".join(clean).strip()
        if not content or content.startswith("@file:"):
            continue
        match = DECLARATION.fullmatch(content)
        if match is None:
            break
        yield number, match.group(1), match.group(2)


def imported_layer(name: str) -> str | None:
    if not name.startswith(APP_PACKAGE + "."):
        return None
    first = name[len(APP_PACKAGE) + 1:].split(".")[0]
    if first in ("R", "BuildConfig"):
        return "resources"
    return first if first in ALLOWED else "root"


def inspect_file(path: Path, repo: Path) -> list[str]:
    relative = path.relative_to(repo)
    package_parts = path.relative_to(repo / SOURCE_ROOT / PACKAGE_PATH).parts[:-1]
    layer = package_parts[0] if package_parts else "root"
    expected_package = ".".join((APP_PACKAGE, *package_parts))
    errors = []
    if layer not in ALLOWED:
        return [f"{relative}:1: unknown layer {layer!r}; define its direction in the guard and README"]
    found_package = False
    for line, kind, name in declarations(path.read_text(encoding="utf-8")):
        if kind == "package":
            found_package = True
            if name != expected_package:
                errors.append(f"{relative}:{line}: package {name}; expected {expected_package} for this directory")
            continue
        if layer in {"codec", "core"} and any(
            name == prefix or name.startswith(prefix + ".") for prefix in ANDROID_PACKAGES
        ):
            errors.append(f"{relative}:{line}: import {name}; {layer} must remain free of Android")
            continue
        target = imported_layer(name)
        if target is not None and target not in ALLOWED[layer]:
            errors.append(f"{relative}:{line}: import {name}; forbidden dependency {layer} -> {target}")
    if not found_package:
        errors.append(f"{relative}:1: missing package declaration in the Kotlin header")
    return errors


def check(repo: Path) -> tuple[int, list[str]]:
    files = sorted((repo / SOURCE_ROOT / PACKAGE_PATH).rglob("*.kt"))
    if not files:
        return 0, [f"{SOURCE_ROOT / PACKAGE_PATH}: no Kotlin sources found"]
    return len(files), [error for path in files for error in inspect_file(path, repo)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1], help="repository root")
    args = parser.parse_args()
    count, errors = check(args.root.resolve())
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"Architecture check passed: {count} Kotlin files respect the package boundaries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
