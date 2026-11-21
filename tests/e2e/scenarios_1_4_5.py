"""기획서 13장 검증 시나리오 1·4·5를 그대로 실행.
1. 비회원이 검색 → 필터 → 상세 → 장바구니 → 로그인 → 장바구니 병합 → 주문 → 모의 결제 성공 → 주문 완료
4. 주문 → 시뮬레이터 가속 → 배송 완료 → 자동 구매확정 → 리뷰 작성
5. 쿠폰·캐시를 쓴 3개 상품 주문 중 1개만 반품 → 안분 금액만큼 환불·캐시 복원
(시뮬레이터 가속은 주문 상품의 단계 시각을 과거로 당겨서 흉내 낸다)
"""
import re
import subprocess
import time
import urllib.parse
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


def wait(sql, expected, secs=30):
    for _ in range(secs):
        if q(sql) == expected:
            return True
        time.sleep(1)
    return False


def fast_forward_delivery(order_no):
    """시뮬레이터 가속: 결제완료 → 배송완료까지 단계 시각을 과거로 (시뮬레이터가 밀린 단계를 따라잡는다).
    도착 예정일은 이틀 전으로: 시뮬레이터가 일부 상품(주문상품 번호 % 20 == 7)을 하루 늦게 배송하므로 어제로 하면
    그런 상품은 "오늘 14시"가 되어 오전에 돌리면 배송완료가 안 된다"""
    q(f"""update order_item set status_changed_at = now() - interval '2 days', promised_date = (now() at time zone 'Asia/Seoul')::date - 2
          where order_id = (select id from orders where order_no = '{order_no}')""")


def items_status(order_no):
    return q(f"select string_agg(distinct status, ',') from order_item where order_id = (select id from orders where order_no = '{order_no}')")


EMAIL = "normal@example.com"
MID = q(f"select id from member where email='{EMAIL}'")
ADDR = q(f"select id from address where member_id={MID} and is_default")
q(f"delete from cart_item where member_id={MID}")

# ---------------- 시나리오 1 ----------------
g = Client()
code, body, _ = g.get("/search?q=" + urllib.parse.quote("매트"))
check("1-1 비회원 검색", code == 200 and "/products/" in body)
code, body, _ = g.get("/search?q=" + urllib.parse.quote("매트") + "&delivery=YANGPANG&sort=sales")
pids = re.findall(r'<a href="/products/(\d+)"\s+class="group flex flex-col', body)
check("1-2 필터 (양팡배송·판매량순)", code == 200 and len(pids) > 0)
pid = next(p for p in pids if q(f"select (not sold_out and not adult and rep_delivery_type='YANGPANG') from product where id={p}") == "t")
code, body, _ = g.get(f"/products/{pid}")
vi = q(f"select rep_vendor_item_id from product where id={pid}")
check("1-3 상세", code == 200 and f'value="{vi}"' in body)
code, _, _ = g.post("/cart/items", {"vendorItemId": vi, "quantity": 2}, htmx=True)
code, body, _ = g.get("/cart")
check("1-4 비회원 장바구니에 담김", "1 개" in body or "장바구니" in body and q(f"select count(*) from cart_item where vendor_item_id={vi} and member_id={MID}") == "0")
g.login(EMAIL)
check("1-5 로그인 → 장바구니 병합", q(f"select quantity from cart_item where member_id={MID} and vendor_item_id={vi}") == "2")
q(f"update cart_item set selected = (vendor_item_id = {vi}) where member_id={MID}")
code, _, h = g.post("/checkout")
code, body, _ = g.get("/checkout")
check("1-6 장바구니 → 주문서", code == 200 and "주문/결제" in body)
_, _, h = g.post("/checkout/place", {"addressId": ADDR, "method": "CARD", "agree": "true"})
no1 = re.search(r'Y\d{14}', h.get("Location") or "").group(0)
code, _, h = g.post("/checkout/pay/" + no1, {"method": "CARD", "result": "APPROVED"})
code, body, _ = g.get(f"/orders/{no1}/complete")
check("1-7 모의 결제 성공 → 주문 완료", "주문이 완료되었어요" in body and q(f"select status from orders where order_no='{no1}'") == "PAID")
check("1-8 주문한 상품은 장바구니에서 빠짐", q(f"select count(*) from cart_item where member_id={MID} and vendor_item_id={vi}") == "0")

# ---------------- 시나리오 4 ----------------
fast_forward_delivery(no1)
check("4-1 시뮬레이터 가속 → 배송 완료", wait(f"select string_agg(distinct status, ',') from order_item where order_id = (select id from orders where order_no = '{no1}')", "DELIVERED"))
q(f"update order_item set delivered_at = now() - interval '8 days' where order_id = (select id from orders where order_no='{no1}')")
check("4-2 배송완료 7일 → 자동 구매확정", wait(f"select string_agg(distinct status, ',') from order_item where order_id = (select id from orders where order_no = '{no1}')", "CONFIRMED"))
oi = q(f"select id from order_item where order_id = (select id from orders where order_no='{no1}') limit 1")
code, body, _ = g.get(f"/my/reviews/new?orderItem={oi}")
code, _, h = g.post("/my/reviews/new", {"orderItemId": oi, "rating": "5", "headline": "시나리오 4 리뷰",
                                         "content": "배송이 빨랐고 품질도 좋아요. 시나리오 검증용 리뷰입니다."})
check("4-3 리뷰 작성", q(f"select rating from review where order_item_id={oi}") == "5")
code, body, _ = Client().get(f"/products/{pid}/reviews")
check("4-4 상품 리뷰에 보임", "시나리오 4 리뷰" in body or q(f"select status from review where order_item_id={oi}") == "VISIBLE")

# ---------------- 시나리오 5 ----------------
three = q("""select string_agg(id::text, ',') from (select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
             where vi.delivery_type='YANGPANG' and vi.is_winner and vi.status='ON_SALE' and vi.stock>20 and p.status='ON_SALE' and not p.adult
               and vi.sale_price between 8000 and 30000 and not exists (select 1 from deal d where d.vendor_item_id=vi.id)
             order by vi.id limit 3) x""").split(",")
q(f"delete from cart_item where member_id={MID}")
for v in three:
    g.post("/cart/items", {"vendorItemId": v, "quantity": 1}, htmx=True)
# 준비: 장바구니 쿠폰(2만원 이상 2,000원 할인) 한 장과 캐시 3,000원
q(f"""insert into member_coupon(member_id, coupon_id, status, issued_at, expires_at)
      select {MID}, id, 'AVAILABLE', now(), now() + interval '7 days' from coupon where name = '장바구니 2,000원 할인'
      on conflict (member_id, coupon_id) do update set status = 'AVAILABLE', used_at = null, used_order_id = null,
          expires_at = now() + interval '7 days'""")  # 회원당 쿠폰 한 장이라 이미 썼으면 다시 쓸 수 있게
q(f"""insert into cash_ledger(member_id, amount, entry_type, description, expires_at, created_at)
      values ({MID}, 3000, 'EARN_EVENT', '시나리오 5 준비 캐시', now() + interval '1 year', now())""")
g.post("/checkout")
code, body, _ = g.get("/checkout")
coupon_ok = "쿠폰 할인" in body
# 자동으로 고른 쿠폰 그대로 + 캐시 3,000원 사용
cart_coupon = re.search(r'name="cartCoupon"[^>]*>.*?<option value="(\d+)"[^>]*selected', body, re.S)
params = {"addressId": ADDR, "method": "YANGPAY", "agree": "true", "manual": "true", "cash": "3000"}
for k, v in re.findall(r'<select name="(pc_\d+)"[^>]*>.*?<option value="(\d+)"[^>]*selected', body, re.S):
    params[k] = v
# 준비한 장바구니 쿠폰을 직접 고른다
params["cartCoupon"] = q(f"""select mc.id from member_coupon mc join coupon c on c.id=mc.coupon_id
                            where mc.member_id={MID} and mc.status='AVAILABLE' and c.name='장바구니 2,000원 할인' order by mc.id desc limit 1""")
code, body2, _ = g.get("/checkout?" + urllib.parse.urlencode({k: v for k, v in params.items() if k not in ("addressId", "method", "agree")}))
exp = re.search(r'name="expectedAmount" value="(\d+)"', body2)
if exp:
    params["expectedAmount"] = exp.group(1)
_, _, h = g.post("/checkout/place", params)
no5 = re.search(r'Y\d{14}', h.get("Location") or "").group(0)
g.post("/checkout/pay/" + no5, {"method": "YANGPAY", "result": "APPROVED"})
o = q(f"select coupon_discount || ',' || cash_used || ',' || (select count(*) from order_item where order_id=o.id) from orders o where order_no='{no5}'").split(",")
check("5-1 쿠폰·캐시 쓴 3개 상품 주문", o[2] == "3" and int(o[1]) == 3000 and int(o[0]) > 0, f"쿠폰 {o[0]} · 캐시 {o[1]}")
cash_rows = q(f"select string_agg(cash_amount::text, ',') from order_item where order_id=(select id from orders where order_no='{no5}')")
check("5-2 캐시가 상품별로 안분됨", sum(int(x) for x in cash_rows.split(",")) == 3000 and all(int(x) > 0 for x in cash_rows.split(",")), cash_rows)
fast_forward_delivery(no5)
check("5-3 배송 완료", wait(f"select string_agg(distinct status, ',') from order_item where order_id = (select id from orders where order_no = '{no5}')", "DELIVERED", 60))
target = q(f"""select id || ',' || (line_amount - discount_amount) || ',' || cash_amount from order_item
               where order_id=(select id from orders where order_no='{no5}') order by id limit 1""").split(",")
bal0 = int(q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={MID}"))
g.get(f"/my/orders/{no5}/claim?type=RETURN&item={target[0]}")
g.post(f"/my/orders/{no5}/claim", {"type": "RETURN", "items": target[0], "reason": "DEFECTIVE", "pickup": "DOOR"})
claim = q(f"select claim_no from claim where order_id=(select id from orders where order_no='{no5}') and claim_type='RETURN'")
check("5-4 1개만 반품 신청 (판매자 귀책 → 반품비 없음)", bool(claim) and q(f"select return_shipping_fee from claim where claim_no='{claim}'") == "0")
for _ in range(3):  # 접수 → 회수중 → 회수완료 → 완료 (단계 시각을 과거로)
    q(f"update claim set status_changed_at = now() - interval '2 days' where claim_no='{claim}'")
    time.sleep(6)
check("5-5 반품 완료", wait(f"select status from claim where claim_no='{claim}'", "COMPLETED"))
refund = q(f"select coalesce(sum(amount),0) from refund where claim_id=(select id from claim where claim_no='{claim}')")
check("5-6 안분 금액만큼만 환불 (상품 결제액)", int(refund) == int(target[1]), f"환불 {refund} / 상품 {target[1]}")
cash_back = q(f"select coalesce(sum(amount),0) from refund where claim_id=(select id from claim where claim_no='{claim}') and method='CASH'")
check("5-7 그 상품에 쓴 캐시만 캐시로 복원", int(cash_back) == int(target[2])
      and int(q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={MID}")) == bal0 + int(target[2]), f"캐시 복원 {cash_back} / 안분 {target[2]}")
check("5-8 나머지 2개는 그대로", q(f"select count(*) from order_item where order_id=(select id from orders where order_no='{no5}') and status='DELIVERED'") == "2")
