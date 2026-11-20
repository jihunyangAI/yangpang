"""M3 ⑤ 알림 확인"""
import re, subprocess, time
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def badge(html):
    m = re.search(r'알림\s*<span[^>]*>(\d+\+?)</span>', html)
    return m.group(1) if m else "0"


def wait_tick():
    time.sleep(7)


c = Client(); c.login("normal@example.com")
home = c.get("/")[1]
print("헤더 알림 배지:", badge(home), "| DB 안 읽음:", q("select count(*) from notification where member_id=1 and read_at is null"))
page = c.get("/my/notifications?tab=wish")[1]
print("찜 알림 탭:", len(re.findall(r'가격이 \d+% 내렸어요', page)), "건")
nid, link = q("select id||'|'||link from notification where member_id=1 and type='PRICE_DROP' order by id limit 1").split('|')
code, _, h = c.get(f"/my/notifications/{nid}")
print("알림 열기 →", code, h.get("Location"), "| 읽음:", q(f"select read_at is not null from notification where id={nid}"))
print("남의 알림 열기:", Client().get(f"/my/notifications/{nid}")[0], end=" / ")
p = Client(); p.login("plus@example.com"); print(p.get(f"/my/notifications/{nid}")[2].get("Location"),
                                                  "| 그 알림 여전히 읽음 상태 유지(남이 못 바꿈):", q(f"select count(*) from notification where id={nid}"))

# 판매자 가격 인하 → 찜한 회원에게 알림
pid, vi = q("""select p.id||'|'||v.id from product p join item i on i.product_id=p.id join vendor_item v on v.item_id=i.id
               where v.is_winner and v.seller_id=4 and p.status='ON_SALE' and not p.sold_out and v.stock>5
                 and (select count(*) from item x where x.product_id=p.id)=1 order by p.id limit 1""").split('|')
c.post(f"/products/{pid}/wish", htmx=True)
print("찜 기준가:", q(f"select notified_price from wishlist where member_id=1 and product_id={pid}"))
s = Client(); s.login("seller@example.com")
sale, lst = q(f"select sale_price||'|'||list_price from vendor_item where id={vi}").split('|')
new = int(int(sale) * 0.8) // 10 * 10
s.post(f"/seller/offers/{vi}", {"listPrice": lst, "salePrice": new, "stock": 30, "status": "ON_SALE"})
wait_tick()
print("가격 인하 알림:", q(f"select title||' / '||body from notification where member_id=1 and type='PRICE_DROP' and link='/products/{pid}'"))
wait_tick()
print("같은 알림 반복 안 함:", q(f"select count(*) from notification where member_id=1 and link='/products/{pid}'"))

# 품절 → 재입고
s.post(f"/seller/offers/{vi}", {"listPrice": lst, "salePrice": new, "stock": 0, "status": "ON_SALE"})
wait_tick()
print("품절 표시:", q(f"select sold_out from product where id={pid}"), "| 찜 품절 기억:", q(f"select was_sold_out from wishlist where member_id=1 and product_id={pid}"))
s.post(f"/seller/offers/{vi}", {"listPrice": lst, "salePrice": new, "stock": 10, "status": "ON_SALE"})
wait_tick()
print("재입고 알림:", q(f"select title||' / '||body from notification where member_id=1 and type='RESTOCK' and link='/products/{pid}'"))

# 주문 → 결제 완료, 배송 알림 / 배송 알림 끄기
b = Client(); b.login("biz@example.com"); b.get("/")
b.post("/my/notifications/settings", {"wish": "true"})  # 배송 알림 끔
v2 = q("select id from vendor_item where is_winner and delivery_type='YANGPANG' and stock>50 and item_condition='NEW' order by id limit 1")
b.post("/checkout/direct", {"vendorItemId": v2, "quantity": 1}); b.get("/checkout")
code, _, h = b.post("/checkout/place", {"addressId": q("select id from address where member_id=4 and is_default"), "method": "YANGPAY", "agree": "true"})
no = re.search(r'Y\d{14}', h.get("Location")).group(0)
b.post("/checkout/pay/" + no, {"method": "YANGPAY", "result": "APPROVED"})
c2 = Client(); c2.login("plus@example.com"); c2.get("/")
c2.post("/checkout/direct", {"vendorItemId": v2, "quantity": 1}); c2.get("/checkout")
code, _, h = c2.post("/checkout/place", {"addressId": q("select id from address where member_id=3 and is_default"), "method": "YANGPAY", "agree": "true"})
no2 = re.search(r'Y\d{14}', h.get("Location")).group(0)
c2.post("/checkout/pay/" + no2, {"method": "YANGPAY", "result": "APPROVED"})
a = Client(); a.login("admin@example.com"); a.get("/admin/simulator")
a.post("/admin/simulator/jump", {"hours": 30}); time.sleep(8)
print("비즈(배송 알림 끔):", q(f"select string_agg(title, ' > ' order by id) from notification where member_id=4 and link='/my/orders/{no}'"))
print("플러스(켜 둠):", q(f"select string_agg(title, ' > ' order by id) from notification where member_id=3 and link='/my/orders/{no2}'"))
a.post("/admin/simulator/reset")

code, _, h = c.post("/my/notifications/read-all")
print("모두 읽음 →", q("select count(*) from notification where member_id=1 and read_at is null"), "| 배지:", badge(c.get("/")[1]))
print("비회원 알림함:", Client().get("/my/notifications")[0])
