"""출석체크 · 친구초대 검증."""
import re
import subprocess
import time
import uuid
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


def signup(invite=None):
    c = Client()
    code, body, _ = c.get("/signup" + (f"?invite={invite}" if invite else ""))
    phone = "010" + str(uuid.uuid4().int)[:8]
    _, msg, _ = c.post("/signup/phone-code", {"phone": phone}, htmx=True)
    vcode = re.search(r'>(\d{6})</b>', msg).group(1)
    c.post("/signup/phone-confirm", {"phone": phone, "verificationCode": vcode}, htmx=True)
    email = f"t{uuid.uuid4().hex[:8]}@example.com"
    data = {"email": email, "password": "yangpang1234!", "passwordConfirm": "yangpang1234!", "name": "초대친구", "phone": phone,
            "verificationCode": vcode, "agreeTerms": "true", "agreePrivacy": "true", "agreeAge": "true"}
    if invite is not None:
        data["inviteCode"] = invite
    code, body, h = c.post("/signup", data)
    return c, email, code, body, h, body if code == 200 else c.get("/signup")[1]


# ---------------- 출석체크 ----------------
anon = Client()
code, body, _ = anon.get("/attendance")
check("출석체크 화면 (비회원은 로그인 안내)", code == 200 and "로그인하고 출석하기" in body)
code, _, h = anon.post("/attendance")
check("비회원 출석은 로그인으로", code in (302, 403))
n = Client(); n.login("normal@example.com")
MID = q("select id from member where email='normal@example.com'")
TODAY = q("select (now() at time zone 'Asia/Seoul')::date")
q(f"delete from attendance where member_id={MID}")
q(f"insert into attendance(member_id, day, streak, reward, created_at) values ({MID}, date '{TODAY}' - 1, 6, 30, now())")
bal0 = int(q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={MID}"))
code, _, h = n.post("/attendance")
row = q(f"select streak || ',' || reward from attendance where member_id={MID} and day=date '{TODAY}'")
check("7일 연속 출석 → 30 + 보너스 300", row == "7,330", row)
check("양팡캐시 적립", int(q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={MID}")) == bal0 + 330)
n.post("/attendance")
check("하루 두 번은 안 됨", q(f"select count(*) from attendance where member_id={MID}") == "2"
      and int(q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={MID}")) == bal0 + 330)
code, body, _ = n.get("/attendance")
check("달력에 출석 표시", "오늘 출석 완료" in body and body.count("🟡") >= 2 and "7일 연속" in body)
q(f"delete from attendance where member_id={MID} and day < date '{TODAY}'")
code, body, _ = n.get("/events")
check("기획전 화면에 출석체크·친구초대 카드", 'href="/attendance"' in body and 'href="/my/invite"' in body)

# ---------------- 친구초대 ----------------
code, body, _ = n.get("/my/invite")
m = re.search(r'tracking-widest" >?([A-Z0-9]{8})<|>([A-Z0-9]{8})</p>', body)
CODE = q(f"select invite_code from member where id={MID}")
check("내 초대 코드·링크", code == 200 and bool(CODE) and f"/signup?invite={CODE}" in body)
c0, email0, code, body, h, page = signup("WRONG123")
check("없는 초대 코드는 가입 거절", code == 200 and "초대 코드를 찾을 수 없어요" in body and q(f"select count(*) from member where email='{email0}'") == "0")
code, body, _ = Client().get(f"/signup?invite={CODE}")
check("초대 링크로 가면 코드가 채워짐", f'value="{CODE}"' in body)
c1, email1, code, body, h, _ = signup(CODE)
NEW = q(f"select id from member where email='{email1}'")
check("초대 코드로 가입", code == 302 and bool(NEW) and q(f"select invited_by from member where id={NEW}") == MID)
check("가입한 친구 3,000원 바로 적립", q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={NEW} and entry_type='EARN_EVENT'") == "3000")
check("초대한 쪽은 첫 결제 전 대기", q(f"select status from invite_reward where invitee_id={NEW}") == "PENDING")
code, body, _ = n.get("/my/invite")
check("초대 목록에 가린 이름", "초*** " in body or "초**" in body)

# 친구의 첫 결제 → 초대한 쪽 보상
vi = q("""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id where vi.is_winner and vi.status='ON_SALE'
          and vi.stock>10 and p.status='ON_SALE' and vi.delivery_type='YANGPANG' and not exists (select 1 from deal d where d.vendor_item_id=vi.id) limit 1""")
c1.post("/my/addresses/new", {"recipient": "초대친구", "phone": "010-1234-5678", "zipcode": "06236", "address1": "서울 강남구 테헤란로 1",
                          "entranceType": "FREE", "makeDefault": "true"})
addr = q(f"select id from address where member_id={NEW} limit 1")
c1.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1}); c1.get("/checkout")
_, _, h = c1.post("/checkout/place", {"addressId": addr, "method": "YANGPAY", "agree": "true"})
no = re.search(r'Y\d{14}', h.get("Location") or "")
check("친구 주문 생성", no is not None, h.get("Location"))
before = int(q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={MID} and entry_type='EARN_EVENT'"))
c1.post("/checkout/pay/" + no.group(0), {"method": "YANGPAY", "result": "APPROVED"})
check("첫 결제 → 초대한 회원 3,000원·알림", q(f"select status from invite_reward where invitee_id={NEW}") == "REWARDED"
      and int(q(f"select coalesce(sum(amount),0) from cash_ledger where member_id={MID} and entry_type='EARN_EVENT'")) == before + 3000
      and q(f"select count(*) from notification where member_id={MID} and title='초대한 친구가 첫 주문을 했어요'") >= "1")

# 월 한도
a = Client(); a.login("admin@example.com")
a.post("/admin/policies/INVITE_MONTHLY_LIMIT", {"value": 1, "reason": "한도 검사용으로 1명"})
c2, email2, code, body, h, _ = signup(CODE)
NEW2 = q(f"select id from member where email='{email2}'")
c2.post("/my/addresses/new", {"recipient": "초대친구", "phone": "010-1234-5678", "zipcode": "06236", "address1": "서울 강남구 테헤란로 1",
                          "entranceType": "FREE", "makeDefault": "true"})
addr2 = q(f"select id from address where member_id={NEW2} limit 1")
c2.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1}); c2.get("/checkout")
_, _, h = c2.post("/checkout/place", {"addressId": addr2, "method": "YANGPAY", "agree": "true"})
c2.post("/checkout/pay/" + re.search(r'Y\d{14}', h.get("Location")).group(0), {"method": "YANGPAY", "result": "APPROVED"})
check("월 한도 넘으면 초대 보상 없음", q(f"select status from invite_reward where invitee_id={NEW2}") == "LIMITED")
a.post("/admin/policies/INVITE_MONTHLY_LIMIT", {"value": 10, "reason": "한도 검사 끝, 원래대로"})
check("운영 기준값 화면에 새 항목 4개", a.get("/admin/policies")[1].count('name="value"') == 21)
