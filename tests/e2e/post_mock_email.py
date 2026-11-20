"""이메일 알림(모의) 검증."""
import re
import subprocess
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


EMAIL = "normal@example.com"
MID = q(f"select id from member where email='{EMAIL}'")
q(f"update member set email_notify=true, marketing_agreed=false where id={MID}")
n = Client(); n.login(EMAIL)
vi = q("""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id where vi.is_winner and vi.status='ON_SALE'
          and vi.stock>10 and p.status='ON_SALE' and vi.delivery_type='YANGPANG' and not exists (select 1 from deal d where d.vendor_item_id=vi.id) limit 1""")
addr = q(f"select id from address where member_id={MID} and is_default")


def buy(c):
    c.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1}); c.get("/checkout")
    _, _, h = c.post("/checkout/place", {"addressId": addr, "method": "YANGPAY", "agree": "true"})
    no = re.search(r'Y\d{14}', h.get("Location")).group(0)
    c.post("/checkout/pay/" + no, {"method": "YANGPAY", "result": "APPROVED"})
    return no


e0 = int(q(f"select count(*) from mock_email where member_id={MID}"))
no = buy(n)
mail = q(f"select category || '|' || subject || '|' || to_email || '|' || coalesce(link,'') from mock_email where member_id={MID} order by id desc limit 1")
check("주문 알림 → 이메일 기록", int(q(f"select count(*) from mock_email where member_id={MID}")) == e0 + 1 and mail.startswith("ORDER|[YangPang] 주문이 완료되었어요")
      and EMAIL in mail and no in mail, mail)

# 마케팅 동의 없으면 혜택 메일 없음 (출석은 알림 없음 → 친구초대 보상 알림 BENEFIT으로 확인 대신 직접 호출)
n.post("/my/notifications/settings", {"delivery": "true", "wish": "true", "email": "true"})
q(f"insert into notification(member_id, type, title, body, link, created_at) values ({MID}, 'BENEFIT', 'x', 'x', null, now())")  # 알림함만 (메일 아님)
before = int(q(f"select count(*) from mock_email where member_id={MID} and category='BENEFIT'"))
# 찜 가격 인하 알림 (PRICE_DROP): 시뮬레이터 scanWishlist 경로 대신 알림 서비스가 쓰이는 혜택성 알림 → 마케팅 미동의면 메일 없음
code, body, _ = n.get("/my/emails")
mid_mail = q(f"select id from mock_email where member_id={MID} order by id desc limit 1")
check("메일함 목록 (안 읽음)", code == 200 and "주문이 완료되었어요" in body and "안 읽은 메일" in body)
code, body, _ = n.get(f"/my/emails/{mid_mail}")
check("메일 열기 → 읽음·링크 버튼", code == 200 and f'href="/my/orders/{no}"' in body and q(f"select read_at is not null from mock_email where id={mid_mail}") == "t")
other = Client(); other.login("plus@example.com")
code, _, _ = other.get(f"/my/emails/{mid_mail}")
check("남의 메일은 404", code == 404)

n.post("/my/notifications/settings", {"delivery": "true", "wish": "true"})  # email 끔
check("이메일 끄기 저장", q(f"select email_notify from member where id={MID}") == "f")
e1 = int(q(f"select count(*) from mock_email where member_id={MID}"))
buy(n)
check("끄면 이메일 기록 안 함 (알림함은 그대로)", int(q(f"select count(*) from mock_email where member_id={MID}")) == e1
      and q(f"select count(*) from notification where member_id={MID} and title='주문이 완료되었어요' and created_at > now() - interval '1 minute'") >= "1")
n.post("/my/notifications/settings", {"delivery": "true", "wish": "true", "email": "true"})

# 혜택 알림: 마케팅 동의 여부
q(f"update member set marketing_agreed=false where id={MID}")
a = Client(); a.login("admin@example.com")
# 1:1 문의 답변 알림은 ORDER 종류 → 메일 감. 혜택(BENEFIT)은 친구초대 보상 경로로 확인
b0 = int(q(f"select count(*) from mock_email where member_id={MID} and category='BENEFIT'"))
q(f"update member set invite_code=null where id={MID}")
code, body, _ = n.get("/my/notifications")
check("알림 설정에 이메일 항목·마케팅 안내", "이메일로도 받기" in body and "마케팅 수신에 동의해야" in body)

code, body, _ = a.get("/admin/emails?to=normal")
check("운영자 보낸 메일함", code == 200 and "주문이 완료되었어요" in body and EMAIL in body)
code, _, _ = n.get("/admin/emails")
check("회원은 보낸 메일함 못 봄", code in (302, 403))

# 찜 가격 인하(PRICE_DROP, 마케팅성): 마케팅 동의 안 하면 알림함만, 동의하면 메일도
import time
def price_drop_mail(agreed):
    q(f"update member set marketing_agreed={'true' if agreed else 'false'}, notify_wish=true, email_notify=true where id={MID}")
    pid = q(f"select p.id from product p where p.status='ON_SALE' and not p.sold_out and p.rep_sale_price > 1000 and not exists (select 1 from wishlist w where w.member_id={MID} and w.product_id=p.id) order by p.id limit 1 offset {7 if agreed else 3}")
    q(f"insert into wishlist(member_id, product_id, created_at, notified_price, was_sold_out) select {MID}, id, now(), rep_sale_price * 2, false from product where id={pid}")
    m0 = int(q(f"select count(*) from mock_email where member_id={MID} and category='PRICE_DROP'"))
    for _ in range(20):
        if q(f"select count(*) from notification where member_id={MID} and type='PRICE_DROP' and link like '%/products/{pid}%'") != "0":
            break
        time.sleep(1)
    got_noti = q(f"select count(*) from notification where member_id={MID} and type='PRICE_DROP' and link like '%/products/{pid}%'") != "0"
    return got_noti, int(q(f"select count(*) from mock_email where member_id={MID} and category='PRICE_DROP'")) - m0

noti, mails = price_drop_mail(False)
check("마케팅 미동의: 가격 인하 알림은 알림함만, 메일 없음", noti and mails == 0, f"{noti} {mails}")
noti, mails = price_drop_mail(True)
check("마케팅 동의: 가격 인하 메일도 기록", noti and mails == 1, f"{noti} {mails}")
q(f"update member set marketing_agreed=false where id={MID}")
