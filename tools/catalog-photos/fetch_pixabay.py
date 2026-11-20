"""카테고리별 상품 사진을 Pixabay에서 받는다 (Pixabay Content License: 무료·상업 이용 가능·출처 표시 의무 없음).

두 단계로 쓴다 (프로젝트 루트에서, PIXABAY_API_KEY 환경 변수 필요):
    python tools/catalog-photos/fetch_pixabay.py candidates <작업폴더>   # 후보 12장씩 썸네일 + 모아보기 이미지
    python tools/catalog-photos/fetch_pixabay.py download <작업폴더>     # selection.csv에 고른 사진만 600px로 저장

- 후보: <작업폴더>/candidates.json, <작업폴더>/sheet_XX.jpg (카테고리 한 줄에 후보 번호 0~11)
- 고르기: tools/catalog-photos/selection.csv (slug, 고른 후보 번호들 "0 2 3 ...")
- 결과: api/src/main/resources/static/images/catalog/<slug>/<n>.jpg, manifest.csv(카테고리→slug·장수), credits.csv(출처)
Pixabay 요청 한도(분당 100회)를 넘지 않게 천천히 요청한다.
"""
import csv
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "api", "src", "main", "resources", "static", "images", "catalog")
CANDIDATES = 12
SIZE = 600
SHARED = {"내복": "baby-bodysuit"}   # 카테고리 → 같이 쓸 사진 폴더


def api_key():
    key = os.environ.get("PIXABAY_API_KEY")
    if not key and os.name == "nt":  # setx로 저장한 값은 새 프로세스에만 보이므로 레지스트리에서 읽는다
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
            key = winreg.QueryValueEx(k, "PIXABAY_API_KEY")[0]
    if not key:
        sys.exit("PIXABAY_API_KEY 환경 변수가 없어요")
    return key


def get(url, tries=5):
    req = urllib.request.Request(url, headers={"User-Agent": "yangpang-research-demo/1.0"})
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == tries - 1:
                raise
            print("요청 한도 → 60초 기다림", flush=True)
            time.sleep(60)


def search(key, query, per_page=40):
    params = {"key": key, "q": query, "image_type": "photo", "safesearch": "true", "per_page": per_page, "order": "popular"}
    data = json.loads(get("https://pixabay.com/api/?" + urllib.parse.urlencode(params)))
    time.sleep(0.8)
    return data.get("hits", [])


def categories():
    with open(os.path.join(HERE, "categories.csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def font(size):
    for path in ("C:/Windows/Fonts/malgun.ttf", "/System/Library/Fonts/AppleSDGothicNeo.ttc"):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def candidates(work, only=()):
    """only: 이 slug들만 다시 받아 기존 후보에 덮어쓴다 (검색어를 고친 카테고리)"""
    key = api_key()
    os.makedirs(work, exist_ok=True)
    path = os.path.join(work, "candidates.json")
    result = {}
    if only and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            result = json.load(f)
    for c in categories():
        if only and c["slug"] not in only:
            continue
        hits = search(key, c["query"])
        # 가로세로 비율이 너무 긴 사진은 정사각형으로 자르면 상품이 잘리므로 뺀다
        hits = [h for h in hits if 0.6 <= h["imageWidth"] / h["imageHeight"] <= 1.7][:CANDIDATES]
        result[c["slug"]] = {"category": c["category"], "query": c["query"],
                             "hits": [{"id": h["id"], "preview": h["previewURL"], "page": h["pageURL"], "user": h["user"],
                                       "tags": h["tags"]} for h in hits]}
        print(c["category"], len(hits))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    sheets(work, result, list(only) or list(result), prefix="resheet" if only else "sheet")


def sheets(work, result, slugs, per_sheet=10, thumb=110, prefix="sheet"):
    f_label, f_num = font(15), font(13)
    for s in range(0, len(slugs), per_sheet):
        chunk = slugs[s:s + per_sheet]
        sheet = Image.new("RGB", (130 + CANDIDATES * (thumb + 4), len(chunk) * (thumb + 6)), "white")
        d = ImageDraw.Draw(sheet)
        for row, slug in enumerate(chunk):
            y = row * (thumb + 6)
            d.text((4, y + thumb // 2 - 10), result[slug]["category"], fill="black", font=f_label)
            for i, h in enumerate(result[slug]["hits"]):
                try:
                    im = Image.open(io.BytesIO(get(h["preview"]))).convert("RGB")
                except Exception:
                    continue
                im = square(im).resize((thumb, thumb))
                x = 130 + i * (thumb + 4)
                sheet.paste(im, (x, y))
                d.rectangle([x, y, x + 18, y + 16], fill="black")
                d.text((x + 3, y), str(i), fill="white", font=f_num)
        sheet.save(os.path.join(work, f"{prefix}_{s // per_sheet:02d}.jpg"), quality=80)


def square(im):
    w, h = im.size
    side = min(w, h)
    return im.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side))


def download(work):
    key = api_key()
    with open(os.path.join(work, "candidates.json"), encoding="utf-8") as f:
        result = json.load(f)
    with open(os.path.join(HERE, "selection.csv"), encoding="utf-8") as f:
        selection = {r["slug"]: [int(x) for x in r["picks"].split()] for r in csv.DictReader(f)}
    manifest, credits = [], []
    for slug, picks in selection.items():
        folder = os.path.join(OUT, slug)
        os.makedirs(folder, exist_ok=True)
        if sorted(os.listdir(folder)) == sorted(f"{i}.jpg" for i in range(1, len(picks) + 1)):
            # 이미 받은 카테고리는 건너뛴다 (중간에 멈췄다 다시 돌릴 때)
            for i, pick in enumerate(picks, 1):
                h = result[slug]["hits"][pick]
                credits.append([slug, i, h["id"], h["user"], h["page"]])
            manifest.append([result[slug]["category"], slug, len(picks)])
            continue
        for old in os.listdir(folder):
            os.remove(os.path.join(folder, old))
        n = 0
        for pick in picks:
            h = result[slug]["hits"][pick]
            # 640px 주소는 24시간만 유효하므로 사진 id로 다시 조회한다
            fresh = json.loads(get("https://pixabay.com/api/?" + urllib.parse.urlencode({"key": key, "id": h["id"]})))["hits"][0]
            time.sleep(0.8)
            im = square(Image.open(io.BytesIO(get(fresh["webformatURL"]))).convert("RGB"))
            n += 1
            im.resize((min(SIZE, im.size[0]),) * 2, Image.LANCZOS).save(os.path.join(folder, f"{n}.jpg"), quality=82, optimize=True)
            credits.append([slug, n, h["id"], h["user"], h["page"]])
        manifest.append([result[slug]["category"], slug, n])
        print(result[slug]["category"], n)
    # 알맞은 사진을 못 찾은 카테고리는 비슷한 카테고리 사진을 같이 쓴다
    counts = {row[1]: row[2] for row in manifest}
    for category, slug in SHARED.items():
        manifest.append([category, slug, counts[slug]])
    with open(os.path.join(OUT, "manifest.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["category", "slug", "count"])
        w.writerows(manifest)
    with open(os.path.join(OUT, "credits.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["slug", "n", "pixabay_id", "user", "page"])
        w.writerows(credits)


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in ("candidates", "download"):
        sys.exit(__doc__)
    if sys.argv[1] == "candidates":
        candidates(sys.argv[2], tuple(sys.argv[3:]))   # 뒤에 slug를 주면 그 카테고리만 다시
    else:
        download(sys.argv[2])
