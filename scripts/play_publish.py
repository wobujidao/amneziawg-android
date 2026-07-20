#!/usr/bin/env python3
"""Publish a signed AAB to Google Play via the androidpublisher API.

Established Маяк VPN release flow (edits.insert -> bundles.upload -> tracks patch -> commit).
Auth: service-account JWT (RS256) -> OAuth2 access token. SA key is OUTSIDE git.

Usage:
  python3 play_publish.py <path-to.aab> [--track internal] \
      [--package mayaknetworks.app] [--sa ~/.mayak-secrets/mayak-play-publisher.json] \
      [--notes "changelog text"]

Requires: pyjwt, requests (present on nl3). Prints the uploaded versionCode.
Note: FIRST upload of a brand-new package must be done manually via the Play UI;
subsequent uploads work through this API.
"""
import argparse, json, os, sys, time
import jwt
import requests

TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"


def access_token(sa: dict) -> str:
    now = int(time.time())
    claim = {
        "iss": sa["client_email"],
        "scope": SCOPE,
        "aud": TOKEN_URI,
        "iat": now,
        "exp": now + 3600,
    }
    assertion = jwt.encode(claim, sa["private_key"], algorithm="RS256")
    r = requests.post(TOKEN_URI, data={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }, timeout=60)
    r.raise_for_status()
    return r.json()["access_token"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("aab")
    ap.add_argument("--track", default="internal")
    ap.add_argument("--package", default="mayaknetworks.app")
    ap.add_argument("--sa", default=os.path.expanduser("~/.mayak-secrets/mayak-play-publisher.json"))
    ap.add_argument("--notes", default="")
    a = ap.parse_args()

    with open(os.path.expanduser(a.sa)) as f:
        sa = json.load(f)
    tok = access_token(sa)
    h = {"Authorization": f"Bearer {tok}"}
    pkg = a.package

    # 1) start an edit
    r = requests.post(f"{BASE}/{pkg}/edits", headers=h, timeout=60)
    r.raise_for_status()
    edit_id = r.json()["id"]
    print(f"edit={edit_id}")

    # 2) upload the bundle
    with open(a.aab, "rb") as f:
        data = f.read()
    r = requests.post(
        f"{UPLOAD}/{pkg}/edits/{edit_id}/bundles?uploadType=media",
        headers={**h, "Content-Type": "application/octet-stream"},
        data=data, timeout=600,
    )
    r.raise_for_status()
    vcode = r.json()["versionCode"]
    print(f"uploaded versionCode={vcode}")

    # 3) assign to track
    rel = {"status": "completed", "versionCodes": [str(vcode)]}
    if a.notes:
        rel["releaseNotes"] = [{"language": "ru-RU", "text": a.notes}]
    r = requests.put(
        f"{BASE}/{pkg}/edits/{edit_id}/tracks/{a.track}",
        headers={**h, "Content-Type": "application/json"},
        data=json.dumps({"track": a.track, "releases": [rel]}), timeout=60,
    )
    r.raise_for_status()
    print(f"track={a.track} set")

    # 4) commit
    r = requests.post(f"{BASE}/{pkg}/edits/{edit_id}:commit", headers=h, timeout=120)
    r.raise_for_status()
    print(f"committed edit={edit_id} versionCode={vcode} track={a.track}")


if __name__ == "__main__":
    main()
