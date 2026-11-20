"""M4 ④ 검증: 정기배송(신청·첫 회차·할인·관리·자동 회차·재고 부족 건너뛰기·해지) + 선물하기(링크·배송지 입력·출고 보류·기한 만료 환불)."""
import re
import subprocess
import time
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


def wait(sql, expected, secs=20):
    for _ in range(secs):
        if q(sql) == expected:
            return True
        time.sleep(1)
    return False


EMAIL = "plus@example.com"
MID = q(f"select id from member where email='{EMAIL}'")
ADDR = q(f"select id from address where member_id={MID} and is_default")
c = Client(); c.login(EMAIL)

# ---------------- 정기배송 ----------------
q(f"update subscription set status='CANCELED' where member_id={MID}")
VI = q(f"""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
           join category_tree t on t.id=p.category_id join category tc on tc.id=t.top_id
           where tc.repurchase_days is not null and vi.item_condition='NEW' and vi.status='ON_SALE' and vi.stock>=50 and p.status='ON_SALE'
             and vi.is_winner and vi.max_per_order>=3 and not exists (select 1 from deal d where d.vendor_item_id=vi.id)
           order by vi.id limit 1""")
PID = q(f"select i.product_id from vendor_item vi join item i on i.id=vi.item_id where vi.id={VI}")
code, body, _ = c.get(f"/products/{PID}")
check("재구매 카테고리 상품 상세에 정기배송", code == 200 and "정기배송 신청" in body)
OTHER = q("""select p.id from product p join category_tree t on t.id=p.category_id join category tc on tc.id=t.top_id
             where tc.repurchase_days is null and p.status='ON_SALE' and not p.sold_out limit 1""")
code, body, _ = c.get(f"/products/{OTHER}")
check("그 밖의 상품에는 정기배송 없음", code == 200 and "정기배송 신청" not in body)

code, body, _ = c.get(f"/subscriptions/new?vendorItemId={VI}&quantity=2&cycle=2")
check("신청 화면 (5% 할인 미리보기)", code == 200 and "정기배송 할인 5%" in body)

c.post("/subscriptions", {"vendorItemId": VI, "quantity": 2, "cycle": 2, "addressId": ADDR, "method": "BANK"})
check("무통장입금으로는 신청 못 함", q(f"select count(*) from subscription where member_id={MID} and status='ACTIVE'") == "0")

stock0 = int(q(f"select stock from vendor_item where id={VI}"))
code, _, h = c.post("/subscriptions", {"vendorItemId": VI, "quantity": 2, "cycle": 2, "addressId": ADDR, "method": "CARD"})
SID = q(f"select id from subscription where member_id={MID} and status='ACTIVE' and vendor_item_id={VI}")
check("신청 → 마이양팡 정기배송으로", code == 302 and h.get("Location", "").endswith("/my/subscriptions") and bool(SID))
row = q(f"select rounds, next_delivery_date - last_delivery_date from subscription where id={SID}")
check("첫 회차 바로 주문, 다음 배송 = 도착일 + 2주", row == "1|14", row)
o = q(f"""select o.status, oi.unit_price, oi.quantity, oi.discount_amount, o.shipping_fee, o.paid_amount, mp.method
          from orders o join order_item oi on oi.order_id=o.id join mock_payment mp on mp.order_id=o.id where o.subscription_id={SID}""").split("|")
check("첫 회차 결제완료 · 카드(모의)", o[0] == "PAID" and o[6] == "CARD", str(o))
unit, qty, disc = int(o[1]), int(o[2]), int(o[3])
check("정기배송 할인 5% (10원 단위 내림)", disc == unit * qty * 5 // 100 // 10 * 10, f"{unit}x{qty} → -{disc}")
check("결제금액 = 상품 - 할인 + 배송비", int(o[5]) == unit * qty - disc + int(o[4]))
check("재고 차감", int(q(f"select stock from vendor_item where id={VI}")) == stock0 - 2)

c.post("/subscriptions", {"vendorItemId": VI, "quantity": 1, "cycle": 1, "addressId": ADDR, "method": "CARD"})
check("같은 옵션 중복 신청 막음", q(f"select count(*) from subscription where member_id={MID} and status='ACTIVE' and vendor_item_id={VI}") == "1")

code, body, _ = c.get("/my/subscriptions")
check("마이양팡 정기배송 화면", code == 200 and "2주마다" in body and "이번 회차 건너뛰기" in body)
code, body, _ = c.get(f"/my/orders/{q(f'select order_no from orders where subscription_id={SID}')}")
check("주문 상세에 정기배송 표시·할인 이름", "정기배송 주문" in body and "정기배송 할인" in body)

# 관리: 주기·수량·건너뛰기
c.post(f"/my/subscriptions/{SID}/cycle", {"cycle": 4})
check("주기 4주로 변경", q(f"select cycle_weeks || ',' || (next_delivery_date - last_delivery_date) from subscription where id={SID}") == "4,28")
c.post(f"/my/subscriptions/{SID}/quantity", {"quantity": 3})
check("수량 3개로 변경", q(f"select quantity from subscription where id={SID}") == "3")
before = q(f"select next_delivery_date from subscription where id={SID}")
c.post(f"/my/subscriptions/{SID}/skip")
check("건너뛰기 → 4주 밀림", q(f"select next_delivery_date - date '{before}' || ',' || skipped from subscription where id={SID}") == "28,1")

stranger = Client(); stranger.login("normal@example.com")
stranger.post(f"/my/subscriptions/{SID}/cycle", {"cycle": 1})
stranger.post(f"/my/subscriptions/{SID}/cancel")
check("남의 정기배송은 못 바꿈", q(f"select cycle_weeks || status from subscription where id={SID}") == "4ACTIVE")

# 자동 회차: 주문일이 되면 시뮬레이터가 주문
noti0 = int(q(f"select count(*) from notification where member_id={MID} and title like '정기배송 2회차%'"))
q(f"update subscription set next_delivery_date = (now() at time zone 'Asia/Seoul')::date + 2 where id={SID}")
check("주문일 → 시뮬레이터가 2회차 주문", wait(f"select rounds from subscription where id={SID}", "2"))
o2 = q(f"select o.status || ',' || oi.quantity from orders o join order_item oi on oi.order_id=o.id where o.subscription_id={SID} order by o.id desc limit 1")
check("2회차는 바뀐 수량(3개)으로 결제", o2 == "PAID,3", o2)
check("2회차 알림", int(q(f"select count(*) from notification where member_id={MID} and title like '정기배송 2회차%'")) == noti0 + 1)

# 재고 부족 → 건너뛰기
q(f"update vendor_item set stock=0 where id={VI}")
q(f"update subscription set next_delivery_date = (now() at time zone 'Asia/Seoul')::date + 1 where id={SID}")
check("재고 없으면 그 회차 건너뜀", wait(f"select skipped from subscription where id={SID}", "2"))
check("건너뛴 이유 기록·알림", "재고" in q(f"select last_note from subscription where id={SID}")
      and q(f"select count(*) from notification where member_id={MID} and title='정기배송 한 회차를 건너뛰었어요'") >= "1",
      q(f"select last_note from subscription where id={SID}"))
check("건너뛴 회차는 주문 없음", q(f"select rounds from subscription where id={SID}") == "2")
q(f"update vendor_item set stock={stock0} where id={VI}")
q(f"update product set sold_out=false where id={PID}")

# 해지
c.post(f"/my/subscriptions/{SID}/cancel")
q(f"update subscription set next_delivery_date = (now() at time zone 'Asia/Seoul')::date where id={SID}")
time.sleep(7)
check("해지 후 자동 주문 없음", q(f"select status || ',' || rounds from subscription where id={SID}") == "CANCELED,2")

# ---------------- 선물하기 ----------------
GVI = q("""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
           where vi.status='ON_SALE' and vi.stock>=20 and vi.item_condition='NEW' and p.status='ON_SALE' and vi.delivery_type='YANGPANG'
             and not p.adult and not exists (select 1 from deal d where d.vendor_item_id=vi.id) order by vi.id desc limit 1""")


def gift_order(name, phone, message="생일 축하해!"):
    c.post("/checkout/direct", {"vendorItemId": GVI, "quantity": 1})
    c.get("/checkout?gift=true")
    code, _, h = c.post("/checkout/place", {"gift": "true", "giftName": name, "giftPhone": phone, "giftMessage": message,
                                            "method": "YANGPAY", "agree": "true"})
    m = re.search(r'Y\d{14}', h.get("Location") or "")
    return code, (m.group(0) if m else None)


c.post("/checkout/direct", {"vendorItemId": GVI, "quantity": 1})
code, body, _ = c.get("/checkout?gift=true")
check("주문서 선물하기 모드", code == 200 and "giftPhone" in body and "받는 분 이름" in body)
n0 = q("select count(*) from orders")
code, no = gift_order("김선물", "12345")
check("휴대폰 번호가 틀리면 주문 안 됨", no is None and q("select count(*) from orders") == n0)

code, no = gift_order("김선물", "01098765432")
check("선물 주문 → 결제 화면", no is not None)
c.post("/checkout/pay/" + no, {"method": "YANGPAY", "result": "APPROVED"})
g = q(f"select g.status, g.token, o.status, o.gift_waiting, o.recipient, o.recipient_phone from gift g join orders o on o.id=g.order_id where o.order_no='{no}'").split("|")
TOKEN = g[1]
check("결제완료 · 선물 대기 · 받는 분 연락처 정리", g[0] == "WAITING" and g[2] == "PAID" and g[3] == "t" and g[4] == "김선물" and g[5] == "010-9876-5432", str(g))
code, body, _ = c.get(f"/orders/{no}/complete")
check("주문 완료 화면에 선물 링크", f"/gift/{TOKEN}" in body)
check("선물 링크 알림 (모의 문자)", q(f"select count(*) from notification where member_id={MID} and title like '선물 링크를 보냈어요%'") >= "1")
code, body, _ = c.get(f"/my/orders/{no}")
check("주문 상세: 링크·가린 연락처", f"/gift/{TOKEN}" in body and "010-****-5432" in body)

# 배송지 입력 전에는 출고하지 않음 (시뮬레이터·판매자)
q(f"update order_item set status_changed_at = now() - interval '3 days' where order_id=(select id from orders where order_no='{no}')")
time.sleep(7)
check("배송지 입력 전 시뮬레이터가 진행하지 않음", q(f"select string_agg(distinct status, ',') from order_item where order_id=(select id from orders where order_no='{no}')") == "PAID")

anon = Client()
code, body, _ = anon.get(f"/gift/{TOKEN}")
paid = q(f"select paid_amount from orders where order_no='{no}'")
check("받는 분 화면 (로그인 없이, 가격 없음)", code == 200 and "선물 받을 주소" in body and "생일 축하해!" in body
      and f"{int(paid):,}원" not in body)
code, _, _ = anon.get("/gift/nosuchtoken123")
check("없는 링크 404", code == 404)
anon.post(f"/gift/{TOKEN}", {"zipcode": "12", "address1": "서울 강남구 테헤란로 1"})
check("우편번호 틀리면 그대로 대기", q(f"select status from gift where token='{TOKEN}'") == "WAITING")
anon.get(f"/gift/{TOKEN}")
anon.post(f"/gift/{TOKEN}", {"zipcode": "63100", "address1": "제주 제주시 연동 1", "address2": "101호",
                             "entranceType": "PASSWORD", "entranceNote": "#1234", "requestNote": "경비실에 맡겨 주세요"})
r = q(f"select g.status, o.gift_waiting, o.region, o.zipcode, o.entrance_note from gift g join orders o on o.id=g.order_id where g.token='{TOKEN}'")
check("배송지 입력 → 접수, 권역 다시 계산(도서산간)", r == "ACCEPTED|f|REMOTE|63100|#1234", r)
check("도착 예정일을 받는 분 지역으로 다시 계산", q(f"select min(promised_date) - (now() at time zone 'Asia/Seoul')::date from order_item where order_id=(select id from orders where order_no='{no}')") == "3")
check("보내는 분께 알림", q(f"select count(*) from notification where member_id={MID} and title='선물 받는 분이 배송지를 입력했어요'") >= "1")
code, body, _ = c.get(f"/my/orders/{no}")
check("보내는 분 화면에는 받는 분 주소 숨김", "제주 제주시" not in body and "배송지 입력 완료" in body)
anon.post(f"/gift/{TOKEN}", {"zipcode": "06236", "address1": "서울 강남구 테헤란로 1"})
check("두 번 입력해도 바뀌지 않음", q(f"select zipcode from orders where order_no='{no}'") == "63100")
q(f"update order_item set status_changed_at = now() - interval '1 day' where order_id=(select id from orders where order_no='{no}')")
check("배송지 입력 후 출고 진행", wait(f"select count(*) from order_item where order_id=(select id from orders where order_no='{no}') and status='PAID'", "0"))

# 기한 만료 → 자동 취소·환불
code, no2 = gift_order("박기한", "010-1111-2222", "")
c.post("/checkout/pay/" + no2, {"method": "YANGPAY", "result": "APPROVED"})
gstock = int(q(f"select stock from vendor_item where id={GVI}"))
q(f"update gift set expires_at = now() - interval '1 minute' where order_id=(select id from orders where order_no='{no2}')")
check("5일 지나면 자동 취소", wait(f"select o.status || ',' || g.status from orders o join gift g on g.order_id=o.id where o.order_no='{no2}'", "CANCELED,EXPIRED"))
check("전액 환불 (모의)", q(f"select refunded_amount = paid_amount from orders where order_no='{no2}'") == "t")
check("재고 복구", int(q(f"select stock from vendor_item where id={GVI}")) == gstock + 1)
t2 = q(f"select token from gift g join orders o on o.id=g.order_id where o.order_no='{no2}'")
code, body, _ = anon.get(f"/gift/{t2}")
check("받는 분 화면: 기한 지나 취소 안내", "기한이 지나" in body and "선물 받을 주소" not in body)
