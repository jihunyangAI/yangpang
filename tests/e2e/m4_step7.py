"""M4 ⑦ 검증: 이미지 검색(색 분포) — 화면, 색으로 찾기 정확도, 사진 올리기, 잘못된 파일·큰 파일, 색상 반영 상품 이미지."""
import colorsys
import io
import random
import re
import subprocess
import urllib.parse
import urllib.request
import uuid
from PIL import Image, ImageDraw
from yp import Client, BASE


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


def result_ids(body):
    return [int(x) for x in re.findall(r'<a href="/products/(\d+)"\s+class="group flex flex-col', body)]


def upload(client, data, filename="photo.png", ctype="image/png"):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"{filename}\"\r\nContent-Type: {ctype}\r\n\r\n").encode() \
        + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/search/image", data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "X-CSRF-TOKEN": client.csrf})
    try:
        r = client.op.open(req)
        return r.status, r.read().decode("utf-8"), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), e.headers


def photo(rgb, size=(640, 480), noise=True):
    """흰 테이블 위에 그 색 물건 (+그림자, 잡음) — 실제 사진 흉내"""
    img = Image.new("RGB", size, (236, 234, 230))
    d = ImageDraw.Draw(img)
    d.ellipse([170, 330, 470, 400], fill=(205, 203, 199))
    d.rounded_rectangle([200, 90, 440, 360], radius=30, fill=rgb)
    if noise:
        px = img.load()
        rnd = random.Random(1)
        for _ in range(4000):
            x, y = rnd.randrange(size[0]), rnd.randrange(size[1])
            r, g, b = px[x, y]
            k = rnd.randint(-12, 12)
            px[x, y] = (max(0, min(255, r + k)), max(0, min(255, g + k)), max(0, min(255, b + k)))
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


c = Client()
code, body, _ = c.get("/")
check("헤더에 이미지로 찾기", 'href="/search/image"' in body)
code, body, _ = c.get("/search/image")
check("이미지 검색 화면", code == 200 and "사진 올리기" in body and "블랙" in body)

COLORS = {"블랙": (35, 35, 38), "네이비": (31, 47, 94), "그린": (74, 154, 58), "핑크": (238, 134, 174), "베이지": (216, 192, 154),
          "그레이": (140, 144, 150), "화이트": (247, 247, 245)}


def bin_of(rgb):
    """ColorHistogram.bin과 같은 규칙 (회색 4칸 + 색상 12 × 채도 2 × 밝기 2)"""
    h, s_, v = colorsys.rgb_to_hsv(*(x / 255 for x in rgb))
    if s_ < 0.15 or v < 0.12:
        return 0 if v < 0.25 else 1 if v < 0.5 else 2 if v < 0.85 else 3
    return 4 + min(11, int(h * 12)) * 4 + (0 if s_ < 0.5 else 1) * 2 + (0 if v < 0.5 else 1)


def share_sql(rgb):
    """상품 사진에서 그 색(이웃 색상 칸은 절반)이 차지하는 비율 SQL 식 (배열은 1부터)"""
    b = bin_of(rgb)
    if b < 4:
        return f"hist[{b + 1}]"
    hue, rest = (b - 4) // 4, (b - 4) % 4
    left, right = 4 + (hue - 1) % 12 * 4 + rest, 4 + (hue + 1) % 12 * 4 + rest
    return f"(hist[{b + 1}] + 0.5 * (hist[{left + 1}] + hist[{right + 1}]))"


def avg_share(ids, rgb):
    if not ids:
        return 0.0
    return float(q(f"select avg({share_sql(rgb)}) from product_image_feature where product_id in ({','.join(map(str, ids))})") or 0)


def base_share(rgb):
    return float(q(f"select avg({share_sql(rgb)}) from product_image_feature"))


# 실제 사진이라 "같은 색 이름"이 아니라, 결과 사진에 그 색이 전체 평균보다 훨씬 많이 들어 있는지 본다
for name, rgb in COLORS.items():
    code, body, _ = c.get("/search/image?color=" + urllib.parse.quote(name))
    ids = result_ids(body)[:10]
    top, base = avg_share(ids, rgb), base_share(rgb)
    check(f"색으로 찾기 '{name}' 상위 10개에 그 색이 많음", code == 200 and len(ids) >= 5 and top >= base * 2.5 and top >= 0.08,
          f"상위 {top:.2f} / 전체 평균 {base:.2f}")

for name in ("네이비", "그린", "핑크"):
    code, body, _ = upload(c, photo(COLORS[name]))
    ids = result_ids(body)[:10]
    top, base = avg_share(ids, COLORS[name]), base_share(COLORS[name])
    check(f"사진 올리기 ({name} 물건 사진)", code == 200 and top >= base * 2.5 and "올린 사진의 주요 색" in body, f"상위 {top:.2f} / 평균 {base:.2f}")

jpg = io.BytesIO(); Image.open(io.BytesIO(photo(COLORS["블랙"]))).save(jpg, "JPEG", quality=80)
code, body, _ = upload(c, jpg.getvalue(), "photo.jpg", "image/jpeg")
check("JPG도 됨", code == 200 and avg_share(result_ids(body)[:10], COLORS["블랙"]) >= base_share(COLORS["블랙"]) * 2.5)

before = q("select count(*) from product_image_feature")
code, body, h = upload(c, b"this is not an image", "x.png")
check("이미지가 아니면 안내 (500 아님)", code == 302)
big = b"\x89PNG" + b"0" * (5 * 1024 * 1024 + 100)
code, body, h = upload(c, big, "big.png")
check("5MB 넘으면 안내", code == 302 and h.get("Location", "").endswith("/search/image"))
code, body, _ = c.get("/search/image")
check("안내 메시지 표시", "5MB까지" in body)
check("올린 사진은 저장하지 않음", q("select count(*) from product_image_feature") == before)

# 상품 이미지: 카테고리 사진(Pixabay 무료 사진)으로 보낸다
pid = q("select id from product where status='ON_SALE' order by id limit 1")
code, _, h1 = c.get(f"/images/products/{pid}/1.svg")
code2, _, h2 = c.get(f"/images/products/{pid}/2.svg")
loc1, loc2 = h1.get("Location", ""), h2.get("Location", "")
check("상품 이미지 → 카테고리 사진으로", code == 302 and re.search(r"/images/catalog/[a-z0-9-]+/\d+\.jpg$", loc1) is not None, loc1)
check("같은 상품의 두 번째 이미지는 다른 사진", code2 == 302 and loc1 != loc2)
req = urllib.request.Request(loc1 if loc1.startswith("http") else BASE + loc1)
with urllib.request.urlopen(req) as r:
    check("사진 파일 (jpg)", r.status == 200 and r.headers.get("Content-Type", "").startswith("image/jpeg") and len(r.read()) > 5000)
code, body, _ = c.get("/images/catalog/credits.csv")
check("사진 출처 목록", code == 200 and "pixabay" in body)
check("모든 상품 카테고리에 사진", q("""select count(distinct c.name) from product p join category c on c.id=p.category_id""") ==
      str(len(c.get("/images/catalog/manifest.csv")[1].strip().splitlines()) - 1))
