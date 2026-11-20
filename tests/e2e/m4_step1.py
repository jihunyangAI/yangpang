"""M4 ① 검증: 판매자 반품 승인·거절, 판매자 미처리 자동 승인, 판매자 문의 답변."""
import re
import subprocess
import time
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond):
    print(("OK  " if cond else "FAIL"), name)


SID = q("select sm.seller_id from seller_member sm join member m on m.id=sm.member_id where m.email='seller@example.com'")
rows = q(f"""select oi.id || ',' || o.order_no from order_item oi join orders o on o.id=oi.order_id join member m on m.id=o.member_id
  where m.email='normal@example.com' and oi.seller_id={SID} and oi.status in ('CONFIRMED','DELIVERED') order by oi.id limit 3""").splitlines()
assert len(rows) == 3, rows
items = [r.split(",") for r in rows]
# 테스트 준비: 구매확정 → 배송완료(방금)로 되돌림
q(f"update order_item set status='DELIVERED', delivered_at=now(), confirmed_at=null where id in ({','.join(i for i, _ in items)})")
q(f"update orders set status='DELIVERED' where order_no in ({','.join(repr(o) for _, o in items)})")

member = Client(); member.login("normal@example.com")
reasons = ["CHANGE_OF_MIND", "DEFECTIVE", "WRONG_OPTION"]
claim_nos = []
for (iid, ono), reason in zip(items, reasons):
    code, body, h = member.post(f"/my/orders/{ono}/claim", {"type": "RETURN", "items": iid, "reason": reason, "pickup": "DOOR"})
    no = q(f"select c.claim_no from claim c join claim_item ci on ci.claim_id=c.id where ci.order_item_id={iid} order by c.id desc limit 1")
    claim_nos.append(no)
    check(f"반품 신청 {reason} → {no} (판매자 지정)", q(f"select seller_id from claim where claim_no='{no}'") == SID)

admin = Client(); admin.login("admin@example.com")
for no in claim_nos:
    admin.get("/admin/claims")
    admin.post(f"/admin/claims/{no}/advance"); admin.post(f"/admin/claims/{no}/advance")
check("세 건 모두 회수완료(검수 대기)", all(q(f"select status from claim where claim_no='{n}'") == "PICKED_UP" for n in claim_nos))

seller = Client(); seller.login("seller@example.com")
code, body, _ = seller.get("/seller/claims")
check("셀러센터 검수 대기 목록에 표시", all(n in body for n in claim_nos))
code, body, _ = seller.get("/seller")
check("대시보드에 검수 대기 카드", "반품·교환 검수 대기" in body)

a, b, c = claim_nos
seller.get("/seller/claims")
seller.post(f"/seller/claims/{b}/reject", {"reason": "재판매가 어려워요"})
check("판매자 귀책(불량)은 거절 불가", q(f"select status from claim where claim_no='{b}'") == "PICKED_UP")
seller.get("/seller/claims")
seller.post(f"/seller/claims/{a}/reject", {"reason": "사용 흔적이 있어 재판매 불가"})
check("단순 변심 거절 → REJECTED", q(f"select status||','||decided_by||','||refund_amount from claim where claim_no='{a}'") == "REJECTED,SELLER,0")
check("거절된 상품은 배송완료로 되돌아감", q(f"select status from order_item where id={items[0][0]}") == "DELIVERED")
seller.get("/seller/claims")
seller.post(f"/seller/claims/{b}/approve")
check("불량 반품 승인 → 완료·환불", q(f"select status||','||decided_by from claim where claim_no='{b}'") == "COMPLETED,SELLER"
      and q(f"select count(*) from refund r join claim c on c.id=r.claim_id where c.claim_no='{b}'") != "0")

other = Client(); other.login("normal@example.com")
code, body, _ = other.get(f"/my/claims/{a}")
check("고객 화면에 거절 사유 표시", "사용 흔적이 있어 재판매 불가" in body and "판매자가 검수 후 신청을 거절했어요" in body)
check("고객 알림함에 거절 알림", q(f"select count(*) from notification where member_id=(select id from member where email='normal@example.com') and title like '판매자가 반품 신청을 거절%'") != "0")

# 판매자 미처리 → 24시간 뒤 자동 승인 (회수완료 시각을 25시간 전으로 당겨 시뮬레이터가 처리하는지 본다)
q(f"update claim set status_changed_at = now() - interval '25 hours' where claim_no='{c}'")
for _ in range(20):
    if q(f"select status from claim where claim_no='{c}'") == "COMPLETED":
        break
    time.sleep(1)
check("24시간 미처리 → 자동 승인(SYSTEM)", q(f"select status||','||decided_by from claim where claim_no='{c}'") == "COMPLETED,SYSTEM")

# 문의 답변
iq = q(f"""select q.id || ',' || q.product_id from product_inquiry q where q.answer is null and not q.secret and exists (select 1 from vendor_item vi join item i on i.id=vi.item_id
         where vi.seller_id={SID} and i.product_id=q.product_id and vi.item_condition='NEW') order by q.id limit 1""")
check("답변 대기 문의 있음", bool(iq))
qid, pid = iq.split(",")
code, body, _ = seller.get("/seller/inquiries")
check("셀러센터 문의 목록", f"/seller/inquiries/{qid}/answer" in body)
seller.post(f"/seller/inquiries/{qid}/answer", {"answer": "네, 해당 옵션은 내일 출고됩니다."})
check("판매자 답변 저장", q(f"select answered_by||','||answer_seller_id from product_inquiry where id={qid}") == f"SELLER,{SID}")
code, body, _ = member.get(f"/products/{pid}/inquiries")
check("상품 문의에 '판매자 답변' 표시", "판매자 답변 · 네, 해당 옵션은 내일 출고됩니다." in body)

not_mine = q(f"""select q.id from product_inquiry q where q.answer is null and not exists (select 1 from vendor_item vi join item i on i.id=vi.item_id
         where vi.seller_id={SID} and i.product_id=q.product_id) order by q.id limit 1""")
seller.get("/seller/inquiries")
seller.post(f"/seller/inquiries/{not_mine}/answer", {"answer": "남의 상품 답변 시도"})
check("남의 상품 문의는 답변 불가", q(f"select answer is null from product_inquiry where id={not_mine}") == "t")
