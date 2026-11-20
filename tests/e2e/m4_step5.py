"""M4 ⑤ 검증: 양팡직구(전용 화면·통관부호·별도 배송 묶음·해외 반송비) + 비즈(사업자 회원가·세금계산서 요청·모의 발행)."""
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


def place(client, email, vi, extra=None, qty=1):
    mid = q(f"select id from member where email='{email}'")
    client.post("/checkout/direct", {"vendorItemId": vi, "quantity": qty})
    client.get("/checkout")
    data = {"addressId": q(f"select id from address where member_id={mid} and is_default"), "method": "YANGPAY", "agree": "true"}
    data.update(extra or {})
    code, _, h = client.post("/checkout/place", data)
    m = re.search(r'Y\d{14}', h.get("Location") or "")
    if not m:
        return None
    client.post("/checkout/pay/" + m.group(0), {"method": "YANGPAY", "result": "APPROVED"})
    return m.group(0)


# ---------------- 양팡직구 ----------------
anon = Client()
code, body, _ = anon.get("/global")
check("양팡직구 화면", code == 200 and "해외 상품을 국내 배송처럼" in body and "/products/" in body)
check("상단 메뉴에 양팡직구", 'href="/global"' in body)
GVI, GPID = q("""select vi.id, p.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
                 where vi.delivery_type='GLOBAL' and vi.stock>=10 and vi.status='ON_SALE' and p.status='ON_SALE' and vi.is_winner
                   and not exists (select 1 from deal d where d.vendor_item_id=vi.id) order by vi.id limit 1""").split("|")
code, body, _ = anon.get(f"/products/{GPID}")
check("직구 상품 상세: 해외 배송·통관부호 안내", "해외 배송" in body and "개인통관고유부호" in body and "15,000원" in body)
code, body, _ = anon.get("/search?delivery=GLOBAL")
check("검색 직구 필터", code == 200 and "양팡직구" in body)

EMAIL = "normal@example.com"
q(f"update member set customs_code=null where email='{EMAIL}'")
c = Client(); c.login(EMAIL)
YVI = q("""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
           where vi.delivery_type='YANGPANG' and vi.stock>=10 and vi.status='ON_SALE' and p.status='ON_SALE' and vi.is_winner
             and vi.sale_price between 3000 and 9000 and not exists (select 1 from deal d where d.vendor_item_id=vi.id) order by vi.id limit 1""")
c.post("/checkout/direct", {"vendorItemId": GVI, "quantity": 1})
code, body, _ = c.get("/checkout")
check("주문서에 통관부호 입력칸·직구 묶음", 'name="customsCode"' in body and "양팡직구 (해외 배송)" in body)
n0 = q("select count(*) from orders")
check("통관부호 없이 주문 불가", place(c, EMAIL, GVI) is None and q("select count(*) from orders") == n0)
check("형식 틀린 통관부호 거절", place(c, EMAIL, GVI, {"customsCode": "P12345"}) is None and q("select count(*) from orders") == n0)
no = place(c, EMAIL, GVI, {"customsCode": "p1234 5678 9012"})
check("직구 주문 → 통관부호 정리해서 저장", no is not None and q(f"select customs_code from orders where order_no='{no}'") == "P123456789012")
check("회원에게도 저장 (다음 주문 자동 채움)", q(f"select customs_code from member where email='{EMAIL}'") == "P123456789012")
c.post("/checkout/direct", {"vendorItemId": GVI, "quantity": 1})
code, body, _ = c.get("/checkout")
check("다음 주문서에 통관부호 채워짐", 'value="P123456789012"' in body)
code, body, _ = c.get(f"/my/orders/{no}")
check("주문 상세는 가린 통관부호", "P12******9012" in body and "P123456789012" not in body)

# 직구 + 양팡배송 함께 → 배송비 묶음 따로
c.post("/cart/items", {"vendorItemId": YVI, "quantity": 1}, htmx=True)
c.post("/cart/items", {"vendorItemId": GVI, "quantity": 1}, htmx=True)
code, body, _ = c.get("/cart")
check("장바구니 직구 묶음 따로", "양팡직구 (해외 배송)" in body and "양팡배송 상품" in body)

# 해외 반송비: 직구 상품 단순 변심 반품 = 15,000원
q(f"update order_item set status='DELIVERED', delivered_at=now(), status_changed_at=now() where order_id=(select id from orders where order_no='{no}')")
q(f"update orders set status='DELIVERED' where order_no='{no}'")
oi = q(f"select id from order_item where order_id=(select id from orders where order_no='{no}')")
c.get(f"/my/orders/{no}/claim?type=RETURN&item={oi}")
c.post(f"/my/orders/{no}/claim", {"type": "RETURN", "items": oi, "reason": "CHANGE_OF_MIND", "pickup": "DOOR"})
fee = q(f"select return_shipping_fee from claim where order_id=(select id from orders where order_no='{no}') and claim_type='RETURN'")
check("직구 단순 변심 반품비 15,000원", fee == "15000", fee)

# ---------------- 비즈 ----------------
BVI, BPID, BIZP, SALE, BITEM = q("""select vi.id, p.id, vi.biz_price, vi.sale_price, i.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
                 where vi.biz_price is not null and vi.stock>=10 and vi.status='ON_SALE' and p.status='ON_SALE' and vi.is_winner
                   and vi.delivery_type<>'GLOBAL' and vi.plus_price is null and not exists (select 1 from deal d where d.vendor_item_id=vi.id)
                 order by vi.id limit 1""").split("|")
b = Client(); b.login("biz@example.com")
code, body, _ = b.get(f"/products/{BPID}?item={BITEM}")
check("비즈 회원에게 사업자 회원가", f"사업자 회원가 {int(BIZP):,}원" in body)
code, body, _ = c.get(f"/products/{BPID}?item={BITEM}")
check("일반 회원에게는 가격 숨기고 안내만", f"사업자 회원가 {int(BIZP):,}원" not in body and "비즈 회원 전용" in body)
b.post("/cart/items", {"vendorItemId": BVI, "quantity": 1}, htmx=True)
code, body, _ = b.get("/cart")
cq = int(q(f"select quantity from cart_item where vendor_item_id={BVI} and member_id=(select id from member where email='biz@example.com')"))
check("비즈 장바구니에 사업자가", f"{int(BIZP) * cq:,}원" in body, f"{BIZP} x {cq}")
bno = place(b, "biz@example.com", BVI)
check("비즈 주문 = 사업자가", q(f"select unit_price from order_item where order_id=(select id from orders where order_no='{bno}')") == BIZP)
nno = place(c, EMAIL, BVI)
check("일반 회원 주문 = 판매가", q(f"select unit_price from order_item where order_id=(select id from orders where order_no='{nno}')") == SALE)

code, body, _ = b.get(f"/my/orders/{bno}")
check("비즈 주문 상세에 세금계산서 요청", "세금계산서 요청" in body and 'value="123-45-67890"' in body)
code, body, _ = c.get(f"/my/orders/{nno}")
check("일반 회원 주문 상세에는 없음", "세금계산서 요청" not in body)
b.post(f"/my/orders/{bno}/tax-invoice", {"businessNo": "12-3", "companyName": "가짜상사", "ceoName": "김비즈", "email": "tax@example.com"})
check("사업자번호 형식 틀리면 거절", q(f"select count(*) from tax_invoice where order_id=(select id from orders where order_no='{bno}')") == "0")
b.post(f"/my/orders/{bno}/tax-invoice", {"businessNo": "1234567890", "companyName": "가짜상사", "ceoName": "김비즈", "email": "tax@example.com"})
t = q(f"select status || ',' || business_no || ',' || amount from tax_invoice where order_id=(select id from orders where order_no='{bno}')")
paid = q(f"select paid_amount + cash_used from orders where order_no='{bno}'")
check("세금계산서 요청 기록", t == f"REQUESTED,123-45-67890,{paid}", t)
b.post(f"/my/orders/{bno}/tax-invoice", {"businessNo": "123-45-67890", "companyName": "또", "ceoName": "김", "email": "tax@example.com"})
check("같은 주문 중복 요청 막음", q(f"select count(*) from tax_invoice where order_id=(select id from orders where order_no='{bno}')") == "1")
c.post(f"/my/orders/{nno}/tax-invoice", {"businessNo": "123-45-67890", "companyName": "a", "ceoName": "b", "email": "a@b.co"})
check("비즈가 아니면 요청 불가", q(f"select count(*) from tax_invoice where order_id=(select id from orders where order_no='{nno}')") == "0")
q(f"update tax_invoice set requested_at = now() - interval '2 hours' where order_id=(select id from orders where order_no='{bno}')")
check("1시간 뒤 모의 발행 (시뮬레이터)", wait(f"select status from tax_invoice where order_id=(select id from orders where order_no='{bno}')", "ISSUED"))
check("발행 번호·알림", bool(q(f"select invoice_no from tax_invoice where order_id=(select id from orders where order_no='{bno}')"))
      and q("select count(*) from notification n join member m on m.id=n.member_id where m.email='biz@example.com' and n.title like '세금계산서가 발행%'") >= "1")
code, body, _ = b.get("/my/biz")
check("비즈 · 세금계산서 화면", code == 200 and bno in body and "발행 완료" in body)
check("비즈 메뉴는 비즈 회원에게만", 'href="/my/biz"' in body and 'href="/my/biz"' not in c.get("/my")[1])
code, _, h = c.get("/my/biz")
check("일반 회원은 비즈 화면 → 마이양팡", code == 302)
