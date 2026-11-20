"""운영 기준값(관리자 설정) 검증: 화면·검사·이력, 그리고 실제 계산(배송비·회비·정기배송 할인·반품 기간·결제 대기·정산 수수료)에 반영되는지. 끝나면 기본값으로 되돌린다."""
import re
import subprocess
import time
from yp import Client

DEFAULTS = {"SHIPPING_FREE_THRESHOLD": 19800, "SHIPPING_FEE": 3000, "PLUS_MONTHLY_FEE": 7890, "SUBSCRIPTION_DISCOUNT_PERCENT": 5,
            "RETURN_WINDOW_DAYS": 7, "PAYMENT_TIMEOUT_MINUTES": 10, "SELLER_COMMISSION_PERCENT": 10, "GLOBAL_RETURN_FEE": 15000}


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


a = Client(); a.login("admin@example.com")
# 이전 실행이 중간에 멈췄을 수 있으니 먼저 기본값으로
for k, v in DEFAULTS.items():
    a.post(f"/admin/policies/{k}", {"value": v, "reason": "검사 시작 전 기본값으로"})


def setp(key, value, reason="검사용으로 바꿔 봄"):
    return a.post(f"/admin/policies/{key}", {"value": value, "reason": reason})


code, body, _ = a.get("/admin/policies")
check("운영 기준값 화면 (21개)", code == 200 and body.count('name="value"') == 21 and "무료배송 기준 금액" in body)
n = Client(); n.login("normal@example.com")
code, _, _ = n.get("/admin/policies")
check("일반 회원은 못 들어감", code in (302, 403))

h0 = int(q("select count(*) from policy_setting_history"))
setp("SHIPPING_FEE", 2500, "")
check("이유 없으면 안 바뀜", q("select value from policy_setting where key='SHIPPING_FEE'") == "3000")
setp("SHIPPING_FEE", 999999)
check("범위 밖이면 안 바뀜", q("select value from policy_setting where key='SHIPPING_FEE'") == "3000")
setp("SHIPPING_FEE", 2500)
check("바꾸면 값·이력", q("select value from policy_setting where key='SHIPPING_FEE'") == "2500"
      and int(q("select count(*) from policy_setting_history")) == h0 + 1
      and q("select updated_by from policy_setting where key='SHIPPING_FEE'") == "admin@example.com")
setp("SHIPPING_FREE_THRESHOLD", 50000)

# 배송비: 2만~5만원 양팡배송 상품 하나 (일반 회원)
vi = q("""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
          where vi.delivery_type='YANGPANG' and vi.is_winner and vi.status='ON_SALE' and vi.stock>=5 and p.status='ON_SALE'
            and vi.sale_price between 32000 and 45000 and vi.plus_price is null and vi.biz_price is null
            and not exists (select 1 from deal d where d.vendor_item_id=vi.id) order by vi.id limit 1""")
pid = q(f"select i.product_id from vendor_item vi join item i on i.id=vi.item_id where vi.id={vi}")
n.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1})
code, body, _ = n.get("/checkout")
check("무료배송 기준 5만원 → 2만원대 상품에 배송비 2,500원", "+2,500원" in body)
code, body, _ = n.get(f"/products/{pid}")
check("상품 상세 배송 안내도 새 값", "50,000원 이상" in body and "미만 2,500원" in body)
setp("SHIPPING_FREE_THRESHOLD", 19800, "검사 중 원래대로 되돌림")
code, body, _ = n.get("/checkout")
check("되돌리면 다시 무료배송", "+2,500원" not in body)

# 플러스 회비
setp("PLUS_MONTHLY_FEE", 9900)
code, body, _ = n.get("/plus")
check("양팡플러스 화면 회비", "9,900" in body)
code, body, _ = n.get(f"/products/{q('select p.id from product p join item i on i.product_id=p.id join vendor_item vi on vi.item_id=i.id where vi.plus_price is not null and vi.is_winner limit 1')}")
check("상품 상세 회원가 안내 회비", "월 9,900원 유료 멤버십" in body)

# 정기배송 할인
setp("SUBSCRIPTION_DISCOUNT_PERCENT", 10)
svi = q("""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
           join category_tree t on t.id=p.category_id join category tc on tc.id=t.top_id
           where tc.repurchase_days is not null and vi.is_winner and vi.status='ON_SALE' and vi.stock>5 and vi.item_condition='NEW'
             and vi.delivery_type<>'GLOBAL' limit 1""")
code, body, _ = n.get(f"/subscriptions/new?vendorItemId={svi}&quantity=1&cycle=2")
check("정기배송 신청 화면 10%", "정기배송 할인 10%" in body)
code, body, _ = n.get(f"/products/{q(f'select i.product_id from vendor_item vi join item i on i.id=vi.item_id where vi.id={svi}')}")
check("상품 상세 정기배송 10%", "상품 금액 10% 할인" in body)

# 반품 기간 문구
setp("RETURN_WINDOW_DAYS", 14)
code, body, _ = n.get(f"/products/{pid}")
check("상품 상세 반품 기간 14일", "상품 수령 후 14일 이내" in body)

# 결제 대기 시간 1분 → 시뮬레이터가 2분 지난 미결제 주문 취소
setp("PAYMENT_TIMEOUT_MINUTES", 1)
n.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1}); n.get("/checkout")
addr = q("select id from address where member_id=(select id from member where email='normal@example.com') and is_default")
_, _, h = n.post("/checkout/place", {"addressId": addr, "method": "YANGPAY", "agree": "true"})
no = re.search(r'Y\d{14}', h.get("Location") or "").group(0)
q(f"update orders set ordered_at = now() - interval '2 minutes' where order_no='{no}'")
check("결제 대기 1분 → 2분 지난 미결제 주문 자동 취소", wait(f"select status from orders where order_no='{no}'", "CANCELED"))
check("취소 이력에 바뀐 시간", "(1분)" in q(f"select note from order_event e join orders o on o.id=e.order_id where o.order_no='{no}' order by e.id desc limit 1"))

# 정산 수수료 12% → 지급 전 정산 다시 계산 + 셀러센터 문구
setp("SELLER_COMMISSION_PERCENT", 12)
st = q("select id from settlement where status='SCHEDULED' and item_count > 0 order by id desc limit 1")
ok = wait(f"""select st.commission = (select coalesce(sum(floor(unit_price*quantity*(12 + case when delivery_type='FULFILLMENT' then 5 else 0 end)/100.0)),0)
              from order_item where settlement_id=st.id) and st.payout = st.sales_amount - st.commission - st.ad_cost from settlement st where st.id={st}""", "t", 25)
check("지급 전 정산은 다음 실행에 새 수수료율로 다시 계산", ok)
paid_ok = q("""select count(*) from settlement st where st.status='PAID' and st.item_count > 0 and st.commission = (select coalesce(sum(floor(unit_price*quantity*(12 + case when delivery_type='FULFILLMENT' then 5 else 0 end)/100.0)),0)
              from order_item where settlement_id=st.id) and st.commission > 0""")
check("지급 끝난 정산은 그대로", paid_ok == "0", paid_ok)
s = Client(); s.login("seller@example.com")
code, body, _ = s.get("/seller/settlements")
check("셀러센터 정산 안내 12%", "12%" in body)

# 직구 반송비 문구
setp("GLOBAL_RETURN_FEE", 20000)
gp = q("select p.id from product p where p.rep_delivery_type='GLOBAL' and p.status='ON_SALE' limit 1")
code, body, _ = n.get(f"/products/{gp}")
check("직구 상품 상세 해외 반송비 20,000원", "해외 반송비 20,000원" in body)

code, body, _ = a.get("/admin/policies")
check("변경 이력 화면", "검사용으로 바꿔 봄" in body and "3,000 → 2,500원" in body)

for k, v in DEFAULTS.items():
    a.post(f"/admin/policies/{k}", {"value": v, "reason": "검사 끝, 기본값으로 되돌림"})
check("모두 기본값으로 되돌림", all(q(f"select value from policy_setting where key='{k}'") == str(v) for k, v in DEFAULTS.items()))
