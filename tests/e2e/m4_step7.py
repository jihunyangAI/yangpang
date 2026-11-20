"""M4 ⑦ 검증: 이미지 검색(색 분포) — 화면, 색으로 찾기 정확도, 사진 올리기, 잘못된 파일·큰 파일, 색상 반영 상품 이미지."""
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


def swatches(ids):
    if not ids:
        return []
    rows = q(f"select product_id, coalesce(swatch, '-') from product_image_feature where product_id in ({','.join(map(str, ids))})")
    m = dict(r.split("|") for r in rows.splitlines())
    return [m.get(str(i), "?") for i in ids]


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

COLORS = {"블랙": (35, 35, 38), "네이비": (31, 47, 94), "그린": (47, 138, 85), "핑크": (238, 134, 174), "베이지": (216, 192, 154),
          "그레이": (140, 144, 150), "화이트": (247, 247, 245)}
for name in COLORS:
    code, body, _ = c.get("/search/image?color=" + urllib.parse.quote(name))
    sw = swatches(result_ids(body)[:10])
    hit = sum(1 for s in sw if s == name)
    need = 6 if name in ("화이트", "그레이") else 9   # 흰색·회색은 파스텔 배경과 섞여 덜 정확
    check(f"색으로 찾기 '{name}' 상위 10개 중 같은 색", code == 200 and hit >= need, f"{hit}/10 {sw}")

for name in ("네이비", "그린", "핑크"):
    code, body, _ = upload(c, photo(COLORS[name]))
    sw = swatches(result_ids(body)[:10])
    hit = sum(1 for s in sw if s == name)
    check(f"사진 올리기 ({name} 물건 사진)", code == 200 and hit >= 8 and "올린 사진의 주요 색" in body, f"{hit}/10")

jpg = io.BytesIO(); Image.open(io.BytesIO(photo(COLORS["블랙"]))).save(jpg, "JPEG", quality=80)
code, body, _ = upload(c, jpg.getvalue(), "photo.jpg", "image/jpeg")
check("JPG도 됨", code == 200 and sum(1 for s in swatches(result_ids(body)[:10]) if s == "블랙") >= 8)

before = q("select count(*) from product_image_feature")
code, body, h = upload(c, b"this is not an image", "x.png")
check("이미지가 아니면 안내 (500 아님)", code == 302)
big = b"\x89PNG" + b"0" * (5 * 1024 * 1024 + 100)
code, body, h = upload(c, big, "big.png")
check("5MB 넘으면 안내", code == 302 and h.get("Location", "").endswith("/search/image"))
code, body, _ = c.get("/search/image")
check("안내 메시지 표시", "5MB까지" in body)
check("올린 사진은 저장하지 않음", q("select count(*) from product_image_feature") == before)

# 상품 이미지: 색상 옵션이 있으면 그 색, 옵션마다 다른 색
pid, colors = q("""select p.id, string_agg(distinct i.option1_value, ',') from product p join item i on i.product_id=p.id
                   where p.option1_name='색상' group by p.id having count(distinct i.option1_value) >= 2 order by p.id limit 1""").split("|")
code, svg1, _ = c.get(f"/images/products/{pid}/1.svg")
code, svg2, _ = c.get(f"/images/products/{pid}/2.svg")
check("색상 상품 이미지에 색 이름", "YANGPANG DEMO · " in svg1 and any(x in svg1 for x in colors.split(",")))
check("두 번째 이미지는 다른 색", re.search(r'rx="30" fill="(#[0-9A-F]{6})"', svg1).group(1) != re.search(r'rx="30" fill="(#[0-9A-F]{6})"', svg2).group(1))
plain = q("select id from product where coalesce(option1_name,'') <> '색상' order by id limit 1")
code, svg, _ = c.get(f"/images/products/{plain}/1.svg")
check("색상 없는 상품은 기존 그림", 'r="46"' in svg and "YANGPANG DEMO · 1" in svg)
