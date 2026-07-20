#!/usr/bin/env python3
"""Upload the Google Play STORE LISTING (texts + icon + feature graphic) for Mayak Networks.

Companion to play_publish.py (which uploads the AAB). Same auth: service-account JWT (RS256) -> OAuth2.
Sets ru-RU listing (title/short/full), the 512 app icon and the 1024x500 feature graphic, then commits.
Screenshots are NOT set here (owner provides real phone screenshots later).

Usage:
  python3 play_listing.py \
      --package mayaknetworks.app \
      --assets /home/wobujidao/mayak-vpn/docs/assets/play \
      [--sa ~/.mayak-secrets/mayak-play-publisher.json] [--dry-run]

Requires: pyjwt, requests (present on nl3).
Note: listing edits do NOT require screenshots to commit; the "app content" forms (data safety,
rating, VPN declaration) are UI-only and separate — see docs/assets/play/forms.md.
"""
import argparse, json, os, sys, time
import jwt
import requests

TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
# Per-language listing copy. en-US is the app's DEFAULT language (must be complete → icon shows in
# console/store); ru-RU is the primary market. Same images for both.
LISTINGS = {
    "ru-RU": {
        "title": "Mayak Networks",
        "short": "Быстрый и надёжный VPN: свободный доступ, выбор локаций, стабильное соединение",
        "full": """Mayak Networks — быстрый и надёжный VPN для стабильного доступа в интернет.

⚡ Скорость
Современный протокол на базе WireGuard/AmneziaWG: мгновенное подключение и высокая скорость. Приложение само выбирает оптимальный маршрут.

🛡️ Стабильность
Маяк продолжает работать даже при временной недоступности сервера — подключается по последней рабочей конфигурации. Соединение остаётся с вами, когда оно нужнее всего.

🌍 Ваш выбор
Несколько локаций на выбор. Меняйте точку подключения одним касанием.

🔒 Безопасность
Трафик защищён современным шифрованием.

🔀 Раздельное туннелирование (split-tunnel)
Выберите приложения, которые будут работать напрямую, мимо VPN (например банковские сервисы) — остальной трафик остаётся защищённым.

🎭 Гибкая настройка
При желании смените иконку и имя приложения по своему вкусу.

Простой, быстрый и надёжный VPN. Свободный доступ — ваш выбор.

Поддержка: support@mayakvpn.ru
Политика конфиденциальности: https://mayakvpn.ru/privacy""",
    },
    "en-US": {
        "title": "Mayak Networks",
        "short": "Fast, reliable VPN: free access, choice of locations, stable connection",
        "full": """Mayak Networks is a fast and reliable VPN for stable internet access.

⚡ Speed
A modern protocol based on WireGuard/AmneziaWG: instant connection and high speed. The app picks the optimal route for you.

🛡️ Stability
Mayak keeps working even when a server is temporarily unavailable — it reconnects using the last working configuration. Your connection stays with you when you need it most.

🌍 Your choice
Several locations to choose from. Switch your connection point with a single tap.

🔒 Security
Your traffic is protected with modern encryption.

🔀 Split tunneling
Choose which apps go directly, bypassing the VPN (for example banking apps) — the rest of your traffic stays protected.

🎭 Flexible
If you like, change the app icon and name to your taste.

Simple, fast and reliable VPN. Free access — your choice.

Support: support@mayakvpn.ru
Privacy policy: https://mayakvpn.ru/privacy""",
    },
}


def access_token(sa: dict) -> str:
    now = int(time.time())
    claim = {"iss": sa["client_email"], "scope": SCOPE, "aud": TOKEN_URI,
             "iat": now, "exp": now + 3600}
    assertion = jwt.encode(claim, sa["private_key"], algorithm="RS256")
    r = requests.post(TOKEN_URI, data={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion}, timeout=60)
    r.raise_for_status()
    return r.json()["access_token"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", default="mayaknetworks.app")
    ap.add_argument("--assets", default="/home/wobujidao/mayak-vpn/docs/assets/play")
    ap.add_argument("--sa", default=os.path.expanduser("~/.mayak-secrets/mayak-play-publisher.json"))
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    for lang, c in LISTINGS.items():
        assert len(c["title"]) <= 30, f"{lang} title {len(c['title'])}>30"
        assert len(c["short"]) <= 80, f"{lang} short {len(c['short'])}>80"
        assert len(c["full"]) <= 4000, f"{lang} full {len(c['full'])}>4000"

    icon = os.path.join(a.assets, "icon-512.png")
    feature = os.path.join(a.assets, "feature-graphic-1024x500.png")
    for p in (icon, feature):
        if not os.path.exists(p):
            sys.exit(f"missing asset: {p}")

    with open(os.path.expanduser(a.sa)) as f:
        sa = json.load(f)
    tok = access_token(sa)
    h = {"Authorization": f"Bearer {tok}"}
    pkg = a.package

    # 1) start edit
    r = requests.post(f"{BASE}/{pkg}/edits", headers=h, timeout=60)
    r.raise_for_status()
    edit = r.json()["id"]
    print(f"edit={edit}")

    # 2) show existing listings (which languages already exist)
    r = requests.get(f"{BASE}/{pkg}/edits/{edit}/listings", headers=h, timeout=60)
    print("existing listings:", r.status_code,
          [l.get("language") for l in r.json().get("listings", [])] if r.ok else r.text[:200])

    if a.dry_run:
        print("dry-run: not modifying; deleting edit")
        requests.delete(f"{BASE}/{pkg}/edits/{edit}", headers=h, timeout=60)
        return

    for lang, c in LISTINGS.items():
        # 3) upsert listing text
        body = {"language": lang, "title": c["title"],
                "shortDescription": c["short"], "fullDescription": c["full"]}
        r = requests.put(f"{BASE}/{pkg}/edits/{edit}/listings/{lang}", headers=h, json=body, timeout=60)
        r.raise_for_status()
        print(f"listing {lang} set: title={c['title']!r} short={len(c['short'])}c full={len(c['full'])}c")

        # 4) upload images (icon + featureGraphic) to this listing language
        for image_type, path, ctype in [("icon", icon, "image/png"),
                                        ("featureGraphic", feature, "image/png")]:
            with open(path, "rb") as f:
                data = f.read()
            r = requests.post(
                f"{UPLOAD}/{pkg}/edits/{edit}/listings/{lang}/{image_type}?uploadType=media",
                headers={**h, "Content-Type": ctype}, data=data, timeout=120)
            r.raise_for_status()
            img = r.json().get("image", {})
            print(f"  {lang} {image_type}: sha256={img.get('sha256','?')[:12]}")

    # 5) commit
    r = requests.post(f"{BASE}/{pkg}/edits/{edit}:commit", headers=h, timeout=120)
    if not r.ok:
        print("COMMIT FAILED:", r.status_code, r.text[:800])
        r.raise_for_status()
    print("committed:", r.json().get("id", "ok"))
    print("DONE — listing texts + icon + feature graphic live on Play (ru-RU).")


if __name__ == "__main__":
    main()
