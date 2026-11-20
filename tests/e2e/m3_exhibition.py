"""M3 ④ 기획전·전문관 확인"""
import re, subprocess, time
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def text(html):
    return re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', html)).strip()


g = Client()
for path in ["/fresh", "/brands", "/refurb", "/events", "/events/kitchen-refresh", "/"]:
    code, html, _ = g.get(path)
    print(path, code, "| 카드", html.count('href="/products/'))
fresh_cat = q("select c.id from category c join category t on t.id=c.parent_id where t.name='신선식품' limit 1")
print("/fresh?category=", g.get(f"/fresh?category={fresh_cat}")[0])
brand = q("select brand_id from product where brand_id is not null group by brand_id order by count(*) desc limit 1")
code, html, _ = g.get(f"/brands/{brand}?sort=price_asc")
prices = [int(x.replace(',', '')) for x in re.findall(r'<span th:text[^>]*>|>([0-9,]+)</span><span class="text-sm font-bold">원', html) if x]
print(f"/brands/{brand} 낮은가격순 정렬 확인:", prices[:5], prices == sorted(prices))
print("상품 상세 브랜드 링크:", f'/brands/{brand}' in g.get("/products/" + q(f"select id from product where brand_id={brand} limit 1"))[1])
print("없는 기획전:", g.get("/events/no-such")[0], "| 종료 처리된 기획전 숨김:", end=" ")
q("update exhibition set active=false where slug='winter-camping'")
print(g.get("/events/winter-camping")[0], "winter-camping" not in g.get("/events")[1])
q("update exhibition set active=true where slug='winter-camping'")

# 기획전 전용 쿠폰 받기
m = Client(); m.login("normal@example.com")
html = m.get("/events/kitchen-refresh")[1]
cid = re.search(r'hx-post="/coupons/(\d+)/download"', html)
print("기획전 쿠폰 버튼:", bool(cid), end=" ")
if cid:
    code, body, _ = m.post(f"/coupons/{cid.group(1)}/download", htmx=True)
    print("→ 받기:", text(body), "| 다시 보면:", "받은 쿠폰" in m.get("/events/kitchen-refresh")[1])

# 리퍼 구매: 장바구니에 등급 표시, 위너 영향 없음
r = q("select v.id||':'||v.item_id||':'||v.sale_price||':'||v.item_condition from vendor_item v where item_condition<>'NEW' and stock>0 order by id limit 1").split(':')
print("리퍼 오퍼", r)
m.post("/cart/items", {"vendorItemId": r[0], "quantity": 1}, htmx=True)
cart = m.get("/cart")[1]
print("장바구니 리퍼 표시:", re.findall(r'\[리퍼[^\]]*\]', cart)[:1])
pid = q(f"select product_id from item where id={r[1]}")
print("상세 다른 판매자 보기에 리퍼:", re.findall(r'리퍼 [최상중]+', g.get(f"/products/offers?itemId={r[1]}")[1])[:1])
print("위너는 새 상품:", q(f"select item_condition from vendor_item where item_id={r[1]} and is_winner"))

# 관리자 기획전 만들기
a = Client(); a.login("admin@example.com"); a.get("/admin/exhibitions")
code, _, h = a.post("/admin/exhibitions", {"slug": "test-event", "title": "테스트 기획전", "theme": "SALE",
                                           "startsOn": time.strftime("%Y-%m-%d"), "endsOn": time.strftime("%Y-%m-%d"), "sortOrder": 1})
eid = h.get("Location").rsplit('/', 1)[-1]
ids = q("select string_agg(id::text, ',') from (select id from product order by id limit 3) x")
a.post(f"/admin/exhibitions/{eid}/products", {"section": "첫 구역", "productIds": ids + ", 999999"})
print("관리자 기획전 생성:", eid, "상품", q(f"select count(*) from exhibition_product where exhibition_id={eid}"),
      "| 고객 화면:", g.get("/events/test-event")[0])
code, _, h = a.post("/admin/exhibitions", {"slug": "Bad Slug", "title": "x", "theme": "SALE", "startsOn": "2026-11-20", "endsOn": "2026-11-21"})
print("잘못된 주소 이름 →", h.get("Location"))
print("관리자 화면:", a.get("/admin/exhibitions")[0], a.get(f"/admin/exhibitions/{eid}")[0])
