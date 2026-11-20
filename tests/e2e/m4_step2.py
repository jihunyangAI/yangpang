"""M4 ② 검증: 행동 로그 기록, 회원님을 위한 추천, 함께 본 상품, 다시 살 때가 됐어요."""
import re
import subprocess
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


MID = q("select id from member where email='normal@example.com'")
c = Client(); c.login("normal@example.com")


def count(t):
    return int(q(f"select count(*) from user_event where member_id={MID} and event_type='{t}'"))


# 1) 행동 기록
pid = q("select p.id from product p join product_co_view cv on cv.product_id=p.id where p.status='ON_SALE' and not p.sold_out group by p.id order by count(*) desc limit 1")
before = {t: count(t) for t in ["VIEW", "CART", "WISH", "SEARCH"]}
code, body, _ = c.get(f"/products/{pid}")
check("상품 상세 200", code == 200)
check("조회 → VIEW 기록", count("VIEW") == before["VIEW"] + 1)
check("상세에 '함께 본 상품' 표시", "함께 본 상품" in body)
co = q(f"select other_id from product_co_view where product_id={pid} order by score desc limit 1")
check("함께 본 상품 1위가 화면에 있음", f"/products/{co}" in body)

vi = q(f"select rep_vendor_item_id from product where id={pid}")
c.post("/cart/items", {"vendorItemId": vi, "quantity": 1}, htmx=True)
check("담기 → CART 기록", count("CART") == before["CART"] + 1)
wished = q(f"select count(*) from wishlist where member_id={MID} and product_id={pid}") != "0"
c.post(f"/products/{pid}/wish", htmx=True)
if wished:  # 원래 찜이었으면 해제된 것 → 다시 켬
    c.post(f"/products/{pid}/wish", htmx=True)
check("찜 켜기 → WISH 기록", count("WISH") >= before["WISH"] + 1)
c.get("/search?q=tent")
check("검색 → SEARCH 기록", count("SEARCH") == before["SEARCH"] + 1)

# 2) 홈 개인화 추천
code, body, _ = c.get("/")
name = q(f"select name from member where id={MID}")
check("홈에 '○○님을 위한 추천'", f"{name}님을 위한 추천" in body)
check("관심 분류 안내 문구", "관심을 바탕으로 골랐어요" in body)
sec = body[body.find('id="home-foryou"'):]
sec = sec[:sec.find("</section>")]
ids = re.findall(r'href="/products/(\d+)"', sec)
uniq = list(dict.fromkeys(ids))
check("추천 상품 수 (최대 10)", 1 <= len(uniq) <= 10, f"{len(uniq)}개")
bought = q(f"""select count(*) from order_item oi join orders o on o.id=oi.order_id where o.member_id={MID}
             and o.ordered_at >= now() - interval '60 days' and oi.product_id in ({','.join(uniq) or '0'})""")
check("최근 60일에 산 상품은 추천에서 빠짐", bought == "0")
check("방금 본 상품은 추천에서 빠짐", pid not in uniq)
mids = q(f"select count(distinct t.mid_id) from product p join category_tree t on t.id=p.category_id where p.id in ({','.join(uniq) or '0'})")
check("여러 관심 분류가 섞임", int(mids) >= 2, f"{mids}개 분류")

g = Client()
code, body, _ = g.get("/")
check("비회원 홈에는 추천 섹션 없음", 'id="home-foryou"' not in body)

# 3) 재구매 추천: 생활용품(30일 주기) 상품을 35일 전에 산 주문 하나를 만들어 확인
order_item = q(f"""select oi.id from order_item oi join orders o on o.id=oi.order_id join product p on p.id=oi.product_id
    join category_tree t on t.id=p.category_id join category tc on tc.id=t.top_id
    where o.member_id={MID} and tc.name='생활용품' and oi.status in ('DELIVERED','CONFIRMED') and p.status='ON_SALE' limit 1""")
if not order_item:
    print("SKIP 재구매: normal@ 생활용품 구매 이력 없음")
else:
    prod = q(f"select product_id from order_item where id={order_item}")
    q(f"update orders set ordered_at = now() - interval '35 days' where id=(select order_id from order_item where id={order_item})")
    q(f"""update orders set ordered_at = now() - interval '120 days' where member_id={MID} and id in
          (select order_id from order_item where product_id={prod} and id<>{order_item})""")
    code, body, _ = c.get("/my")
    check("마이양팡에 '다시 살 때가 됐어요'", "다시 살 때가 됐어요" in body)
    sec = body[body.find("다시 살 때가 됐어요"):]
    check("35일 전 산 생활용품이 목록에 있음", f"/products/{prod}" in sec[:6000])
    check("주기 안내(보통 30일 주기 · 5일 지났어요)", "30일 주기" in sec and "지났어요" in sec)
