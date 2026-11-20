"""양팡설치 · 성인 인증 · 음성 검색 버튼 검증."""
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


n = Client(); n.login("normal@example.com")
MID = q("select id from member where email='normal@example.com'")
ADDR = q(f"select id from address where member_id={MID} and is_default")

# ---------------- 양팡설치 ----------------
IVI, IPID = q("""select vi.id, p.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
                where vi.delivery_type='INSTALL' and vi.is_winner and vi.status='ON_SALE' and vi.stock>=5 and p.status='ON_SALE'
                  and not exists (select 1 from deal d where d.vendor_item_id=vi.id) limit 1""").split("|")
code, body, _ = n.get(f"/products/{IPID}")
check("설치 상품 상세: 양팡설치 · 설치일 지정", code == 200 and "양팡설치" in body and "설치일 지정 가능" in body)
n.post("/checkout/direct", {"vendorItemId": IVI, "quantity": 1})
code, body, _ = n.get("/checkout")
dates = re.findall(r'name="installDate" value="(\d{4}-\d{2}-\d{2})"', body)
check("주문서: 방문 설치일 고르기 (12일, 일요일 제외)", len(dates) == 12 and "양팡설치 (방문 설치·무료)" in body, str(dates[:3]))
import datetime
check("일요일 없음", all(datetime.date.fromisoformat(d).weekday() != 6 for d in dates))
n0 = q("select count(*) from orders")
_, _, h = n.post("/checkout/place", {"addressId": ADDR, "method": "YANGPAY", "agree": "true"})
check("설치일 없으면 주문 안 됨", q("select count(*) from orders") == n0)
_, _, h = n.post("/checkout/place", {"addressId": ADDR, "method": "YANGPAY", "agree": "true", "installDate": "2020-01-01"})
check("고를 수 없는 날짜 거절", q("select count(*) from orders") == n0)
pick = dates[2]
_, _, h = n.post("/checkout/place", {"addressId": ADDR, "method": "YANGPAY", "agree": "true", "installDate": pick})
no = re.search(r'Y\d{14}', h.get("Location") or "").group(0)
n.post("/checkout/pay/" + no, {"method": "YANGPAY", "result": "APPROVED"})
row = q(f"select o.install_date || ',' || oi.promised_date || ',' || o.shipping_fee from orders o join order_item oi on oi.order_id=o.id where o.order_no='{no}'")
check("주문에 설치일·도착 예정일=설치일·배송비 0", row == f"{pick},{pick},0", row)
code, body, _ = n.get(f"/my/orders/{no}")
check("주문 상세에 방문 설치일", "방문 설치일" in body)
# 설치일이 되면 설치 완료 (시뮬레이터) — 설치일을 오늘로 당겨 확인
q(f"""update order_item set promised_date = (now() at time zone 'Asia/Seoul')::date - 1, status_changed_at = now() - interval '1 day'
      where order_id=(select id from orders where order_no='{no}')""")
check("설치일 → 방문 설치 완료 (시뮬레이터)", wait(f"select status from order_item where order_id=(select id from orders where order_no='{no}')", "DELIVERED", 30))
check("설치 완료 알림·이력 문구", q(f"select count(*) from notification where member_id={MID} and title='방문 설치가 완료되었어요'") >= "1"
      and q(f"select count(*) from order_event e join orders o on o.id=e.order_id where o.order_no='{no}' and e.note='방문 설치 완료'") == "1")

# ---------------- 성인 인증 ----------------
APID, AVI = q("""select p.id, p.rep_vendor_item_id from product p where p.adult and p.status='ON_SALE' order by p.id limit 1""").split("|")
anon = Client()
code, body, _ = anon.get(f"/products/{APID}")
check("비회원: 19세 연령 확인 화면", code == 200 and "19세 이상만 볼 수 있는 상품" in body and "로그인하고 성인 인증하기" in body)
code, body, _ = anon.get("/search?q=" + __import__("urllib.parse").parse.quote("막걸리"))
check("비회원 목록: 19 이미지로 가림", "성인 인증 후 볼 수 있어요" in body)
code, body, _ = n.get(f"/products/{APID}")
check("인증한 회원(normal@)은 상세 보임", "19세 이상만 볼 수 있는" not in body and "바로 구매" in body)
p = Client(); p.login("plus@example.com")
PMID = q("select id from member where email='plus@example.com'")
q(f"update member set adult_verified_at = null where id={PMID}")
code, body, _ = p.get(f"/products/{APID}")
check("미인증 회원: 성인 인증 안내", "19세 이상만 볼 수 있는" in body and f"/adult-verify?continue=/products/{APID}" in body)
p.post("/checkout/direct", {"vendorItemId": AVI, "quantity": 1})
code, body, h = p.get("/checkout")
check("미인증이면 주문서·주문 막힘", code == 302 or "성인 인증 후 구매" in body)
code, body, _ = p.get(f"/adult-verify?continue=/products/{APID}")
check("성인 인증 화면 (가린 휴대폰)", code == 200 and "010-****-0003" in body)
p.post("/adult-verify/code", {"continue": f"/products/{APID}"})
body = p.get(f"/adult-verify?continue=/products/{APID}")[1]
vcode = re.search(r'tracking-widest">(\d{6})<', body).group(1)
today = q("select (now() at time zone 'Asia/Seoul')::date")
y = int(today[:4])
p.post("/adult-verify", {"code": vcode, "birth": f"{y - 18}-01-01", "continue": f"/products/{APID}"})
check("만 19세 미만 거절", q(f"select adult_verified_at is null from member where id={PMID}") == "t")
p.post("/adult-verify/code", {"continue": f"/products/{APID}"})
vcode = re.search(r'tracking-widest">(\d{6})<', p.get("/adult-verify")[1]).group(1)
code, _, h = p.post("/adult-verify", {"code": vcode, "birth": "1990-03-03", "continue": f"/products/{APID}"})
check("만 19세 이상 인증 → 원래 상품으로", code == 302 and h.get("Location", "").endswith(f"/products/{APID}")
      and q(f"select adult_verified_at is not null from member where id={PMID}") == "t")
code, body, _ = p.get(f"/products/{APID}")
check("인증 후 상세 보임", "19세 이상만 볼 수 있는" not in body)
code, body, _ = p.get("/checkout")
check("인증 후 주문서 열림", code == 200 and "19세 이상 상품이 있어요" not in body)
q(f"update member set adult_verified_at = now() - interval '366 days' where id={PMID}")
code, body, _ = p.get(f"/products/{APID}")
check("1년 지나면 다시 인증", "19세 이상만 볼 수 있는" in body)
q(f"update member set adult_verified_at = null where id={PMID}")
code, body, _ = p.get("/my/profile")
check("회원정보에 성인 인증 항목", "성인 인증" in body and "/adult-verify" in body)

# ---------------- 음성 검색 버튼 ----------------
code, body, _ = anon.get("/")
check("음성 검색 버튼(지원 브라우저에서만 보이게 숨김 상태)", body.count("data-voice-search hidden") == 2)
code, js, _ = anon.get("/js/app.js")
check("음성 인식 스크립트 (한국어)", "webkitSpeechRecognition" in js and 'rec.lang = "ko-KR"' in js)
