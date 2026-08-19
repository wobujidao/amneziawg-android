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
import argparse, json, os, re, sys, time
import jwt
import requests

TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
# 🔴 Тексты витрины СЮДА НЕ ЗАШИВАЮТСЯ (19-08). До этой правки они лежали здесь константой и
# протухли: в консоли с 26-07 стоит текст без слова «VPN» (директива владельца «публично мы не VPN,
# а Сети»), а в скрипте оставался прежний — «Быстрый и надёжный VPN… мимо VPN…», да ещё со СНЯТЫМ
# дев-доменом mayakvpn.ru в поддержке и в ссылке на политику. Запуск скрипта молча вернул бы на
# живую витрину и запрещённые формулировки, и мёртвый домен, и нерабочую ссылку на политику
# (её Google проверяет). Источник правды теперь ОДИН — docs/assets/play/listing-text.md в монорепо,
# он же зеркало консоли; разъехаться им больше негде.
LISTING_DOC = "listing-text.md"   # лежит рядом с картинками, путь задаётся --assets

# Слова, которых на витрине быть не должно. Это не вкусовщина: с 01.09.2025 в РФ наказуема реклама
# средств обхода блокировок, и штрафуют владельца материалов. «VpnService» — НАЗВАНИЕ системного
# интерфейса Android, его Google требует упоминать прямым текстом («Document use of the VpnService
# in the Google Play listing»), поэтому оно разрешено; отдельное рекламное «VPN» — нет.
FORBIDDEN = ("обход", "обойт", "блокировк", "заблокирован", "dpi", "тспу",
             "bypass", "circumvent", "mayakvpn.ru")


def _clean(text: str) -> str:
    return text.strip("\n").strip()


def load_listings(assets_dir: str) -> dict:
    """Читает тексты витрины из listing-text.md. Не распарсилось — падаем, а не «берём умолчание»:
    молчаливый откат витрины на старый текст — ровно то, ради чего эта функция и написана."""
    path = os.path.join(assets_dir, LISTING_DOC)
    if not os.path.isfile(path):
        sys.exit(f"нет файла с текстами витрины: {path}")
    with open(path, encoding="utf-8") as f:
        doc = f.read()
    out = {}
    # Разделы вида «## Русский (ru-RU)» — язык берём из скобок, название раздела роли не играет.
    chunks = re.split(r"^##\s+.*?\(([a-z]{2}-[A-Z]{2})\)\s*$", doc, flags=re.M)
    for lang, body in zip(chunks[1::2], chunks[2::2]):
        fields = {}
        for head, value in re.findall(r"^###\s+([^\n(]+?)\s*(?:\([^)]*\))?\s*$\n(.*?)(?=^###\s|\Z)",
                                      body, flags=re.M | re.S):
            fields[head.strip().lower()] = _clean(value)
        try:
            out[lang] = {"title": fields["название"],
                         "short": fields["краткое описание"],
                         "full": fields["полное описание"]}
        except KeyError as e:
            sys.exit(f"{path}: в разделе {lang} не найден заголовок {e}")
    if not out:
        sys.exit(f"{path}: не нашёл ни одного языкового раздела вида «## … (ru-RU)»")
    for lang, c in out.items():
        for field, text in c.items():
            low = text.lower()
            for word in FORBIDDEN:
                if word in low:
                    sys.exit(f"{lang}/{field}: запрещённое слово «{word}» — витрина не заливается")
            # «VPN» отдельным словом запрещено, «VpnService» — разрешено (требование Google).
            if re.search(r"(?<![a-zA-Zа-яА-Я])vpn(?!service)(?![a-zA-Zа-яА-Я])", text, re.I):
                sys.exit(f"{lang}/{field}: слово «VPN» само по себе — витрина не заливается "
                         f"(допустимо только имя интерфейса «VpnService»)")
    return out


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
    ap.add_argument("--assets", default=os.path.expanduser("~/mayak/docs/assets/play"))
    ap.add_argument("--sa", default=os.path.expanduser("~/.mayak-secrets/mayak-play-publisher.json"))
    ap.add_argument("--dry-run", action="store_true")
    # 🔴 По умолчанию правка витрины НЕ уходит на проверку. Проверка накрывает ВСЁ
    # накопленное в консоли разом и отменить её нельзя: 19-08 в продакшен-треке лежала
    # подготовленная сборка, ждавшая анкеты VpnService, — заливка витрины «заодно»
    # отправила бы на ревью и её.
    ap.add_argument("--send-for-review", action="store_true",
                    help="отправить накопленные правки на проверку Google (по умолчанию нет)")
    a = ap.parse_args()

    LISTINGS = load_listings(a.assets)
    print("языки витрины:", ", ".join(LISTINGS))

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
    commit_url = f"{BASE}/{pkg}/edits/{edit}:commit"
    if not a.send_for_review:
        commit_url += "?changesNotSentForReview=true"
        print("правка применяется БЕЗ отправки на проверку (--send-for-review, чтобы отправить)")
    r = requests.post(commit_url, headers=h, timeout=120)
    if not r.ok:
        print("COMMIT FAILED:", r.status_code, r.text[:800])
        r.raise_for_status()
    print("committed:", r.json().get("id", "ok"))
    print("DONE — тексты витрины, иконка и баннер применены (языки: " + ", ".join(LISTINGS) + ").")


if __name__ == "__main__":
    main()
