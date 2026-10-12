#!/usr/bin/env python3
"""Normalize release metadata to a sortable six-digit version; keep assets and old Git tags."""

import argparse
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request


def version(number):
    if not 1 <= number <= 999_999:
        raise ValueError("Release version must fit the six-digit sequence")
    return f"v{number:06d}"


def identity(release):
    tag = release["tag_name"]
    old = re.fullmatch(r"(build|branch|build-inputs)-(\d+)", tag)
    new = re.fullmatch(r"v(\d{6})(-inputs)?", tag)
    if old:
        kind, number = old.group(1), int(old.group(2))
    elif new:
        number = int(new.group(1))
        kind = "build-inputs" if new.group(2) else "branch" if release["prerelease"] else "build"
    else:
        return None
    canonical = version(number)
    if kind == "build-inputs":
        return number, canonical + "-inputs", canonical + " (build inputs)"
    if kind == "branch":
        # Keep the branch context when converting an old preview title.
        branch = re.search(r"\(([^)]+)\)$", release["name"] or "")
        context = branch.group(1).removeprefix("preview: ") if branch else "unknown branch"
        return number, canonical, f"{canonical} (preview: {context})"
    return number, canonical, canonical


class GitHub:
    def __init__(self, repo, token):
        self.base = f"https://api.github.com/repos/{repo}"
        self.token = token
        self.last_write = 0.0

    def __call__(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        })
        for attempt in range(5):
            if method != "GET":
                time.sleep(max(0, 1.0 - (time.monotonic() - self.last_write)))
                self.last_write = time.monotonic()
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                # Preserve 404 for the caller's missing-alias check.
                if error.code == 404:
                    raise
                detail = error.read().decode("utf-8", errors="replace")
                try:
                    message = json.loads(detail).get("message", detail)
                except ValueError:
                    message = detail
                limited = error.code == 429 or (error.code == 403 and (
                    error.headers.get("Retry-After") is not None or
                    error.headers.get("X-RateLimit-Remaining") == "0" or
                    "rate limit" in message.lower()))
                if limited and attempt < 4:
                    delay = max(60, int(error.headers.get("Retry-After", "60")))
                    print(f"GitHub rate limit; retrying in {delay}s", flush=True)
                    time.sleep(delay)
                    continue
                raise RuntimeError(f"GitHub {method} {path}: {error.code}: {message}") from error


def normalize(api, apply=False):
    releases = []
    page = 1
    while True:
        batch = api("GET", f"/releases?per_page=100&page={page}")
        releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    known = [(release, identity(release)) for release in releases if not release["draft"]]
    known = [(release, item) for release, item in known if item is not None]
    # Check aliases before making any edits; never repoint an existing tag.
    aliases = []
    for release, (_, tag, _) in known:
        if tag == release["tag_name"]:
            continue
        old_path = urllib.parse.quote(release["tag_name"], safe="")
        old_ref = api("GET", f"/git/ref/tags/{old_path}")
        sha = old_ref["object"]["sha"]
        try:
            new_ref = api("GET", f"/git/ref/tags/{tag}")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            aliases.append((tag, sha))
        else:
            if new_ref["object"]["sha"] != sha:
                raise ValueError(f"{tag} already points to a different object")
    for tag, sha in aliases:
        print(f"Add alias {tag} → {sha}")
        if apply:
            api("POST", "/git/refs", {"ref": f"refs/tags/{tag}", "sha": sha})
    for release, (_, tag, name) in known:
        if tag == release["tag_name"] and name == release["name"]:
            continue
        print(f"Release {release['id']}: {release['tag_name']} → {tag}")
        if apply:
            api("PATCH", f"/releases/{release['id']}", {"tag_name": tag, "name": name, "make_latest": "false"})
    stable = [(release, item) for release, item in known
              if not release["prerelease"] and not item[1].endswith("-inputs")]
    if stable:
        latest, (_, tag, _) = max(stable, key=lambda pair: pair[1][0])
        print(f"Latest stable release: {tag}")
        if apply:
            api("PATCH", f"/releases/{latest['id']}", {"make_latest": "true"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="apply the metadata migration (default: dry run)")
    args = parser.parse_args()
    normalize(GitHub(os.environ["GH_REPO"], os.environ["GH_TOKEN"]), args.apply)


if __name__ == "__main__":
    main()
