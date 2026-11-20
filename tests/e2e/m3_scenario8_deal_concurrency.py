"""시나리오 8: 오늘의 특가 재고 10개에 동시 주문 100건 → 정확히 10건만 성공"""
import re, subprocess, threading, time
from concurrent.futures import ThreadPoolExecutor
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


# 대상: 재고 넉넉한 양팡배송 위너 오퍼
vi, sale = q("""select v.id||':'||v.sale_price from vendor_item v where v.is_winner and v.status='ON_SALE' and v.stock >= 200
               and v.delivery_type='YANGPANG' and v.plus_price is null
               and not exists(select 1 from deal d where d.vendor_item_id=v.id) order by v.id limit 1""").split(':')
stock_before = int(q(f"select stock from vendor_item where id={vi}"))
deal_price = int(sale) // 2 // 10 * 10
admin = Client(); admin.login("admin@example.com"); admin.get("/admin/deals")
admin.post("/admin/deals", {"vendorItemId": vi, "dealPrice": deal_price, "quantity": 10, "maxPerOrder": 1, "maxPerMember": 1})
deal_id = q(f"select id from deal where vendor_item_id={vi} and source='ADMIN'")
print(f"특가 #{deal_id}: 오퍼 {vi}, {sale}원 → {deal_price}원, 수량 10, 재고 {stock_before}")

# 일반 회원 100명 (멤버십·판매자 아닌) 로그인 + 바로구매 주문서 준비
ids = [int(x) for x in q("""select m.id from member m where m.role='USER' and m.id > 5
                            and exists(select 1 from address a where a.member_id=m.id and a.is_default) order by m.id limit 100""").split()]
emails = dict(l.split('|') for l in q(f"select id, email from member where id in ({','.join(map(str, ids))})").splitlines())
addr = dict(l.split('|') for l in q(f"select member_id, id from address where is_default and member_id in ({','.join(map(str, ids))})").splitlines())
print("참가 회원", len(ids))


def prepare(mid):
    c = Client(); c.login(emails[str(mid)])
    c.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1})
    html = c.get("/checkout")[1]
    c.expected = re.search(r'name="expectedAmount" value="(\d+)"', html).group(1)
    return mid, c


t0 = time.time()
with ThreadPoolExecutor(20) as ex:
    clients = list(ex.map(prepare, ids))
print(f"준비 {time.time() - t0:.1f}초")

barrier = threading.Barrier(len(clients))
results = {}


def place(arg):
    mid, c = arg
    barrier.wait()  # 100명이 동시에 결제하기
    code, body, h = c.post("/checkout/place", {"addressId": addr[str(mid)], "method": "YANGPAY", "agree": "true", "expectedAmount": c.expected})
    loc = h.get("Location") or ""
    m = re.search(r'Y\d{14}', loc)
    results[mid] = m.group(0) if m else (re.sub(r'<[^>]+>', '', body).strip()[:60] or loc)


t0 = time.time()
with ThreadPoolExecutor(len(clients)) as ex:
    list(ex.map(place, clients))
elapsed = time.time() - t0
ok = {k: v for k, v in results.items() if v.startswith('Y')}
print(f"동시 주문 {len(results)}건 / {elapsed:.2f}초 → 성공 {len(ok)}건, 실패 {len(results) - len(ok)}건")
print("실패 응답 예:", {v for v in results.values() if not v.startswith('Y')} or "-")
print("DB 특가 판매 수:", q(f"select sold||'/'||quantity from deal where id={deal_id}"),
      "| 특가 주문 상품:", q(f"select count(*)||'건, 단가 '||string_agg(distinct unit_price::text, ',') from order_item where deal_id={deal_id}"),
      "| 재고:", stock_before, "→", q(f"select stock from vendor_item where id={vi}"))

# 결제 실패 1건 → 특가 수량 되돌림 → 다른 회원이 살 수 있음
first = next(iter(ok.items()))
c = dict(clients)[first[0]]
c.post("/checkout/pay/" + first[1], {"method": "YANGPAY", "result": "FAILED"})
print("1건 결제 실패 후:", q(f"select sold||'/'||quantity from deal where id={deal_id}"))
loser = next(mid for mid, v in results.items() if not v.startswith('Y'))
lc = dict(clients)[loser]
lc.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1}); lc.get("/checkout")
code, body, h = lc.post("/checkout/place", {"addressId": addr[str(loser)], "method": "YANGPAY", "agree": "true"})
print("떨어졌던 회원 재시도:", "성공" if 'Y' in (h.get("Location") or "") else re.sub(r'<[^>]+>', '', body).strip()[:60],
      "→", q(f"select sold||'/'||quantity from deal where id={deal_id}"))
# 1인 1개 제한
code, body, h = lc.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1}); lc.get("/checkout")
code, body, h = lc.post("/checkout/place", {"addressId": addr[str(loser)], "method": "YANGPAY", "agree": "true"})
print("같은 회원 한 번 더:", h.get("Location"), "(특가 소진 → 일반가 주문)" if 'Y' in (h.get("Location") or "") else "")
print("마지막 주문 단가:", q(f"select unit_price||' deal='||coalesce(deal_id::text,'없음') from order_item where vendor_item_id={vi} order by id desc limit 1"))
