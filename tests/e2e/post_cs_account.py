"""고객센터(FAQ·1:1 문의·운영자 답변) + 아이디·비밀번호 찾기(모의 인증) + 새 로고 검증."""
import re
import subprocess
import urllib.parse
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


def flash(c, path):
    return c.get(path)[1]


anon = Client()
code, body, _ = anon.get("/")
check("새 로고 (빨강 Yang·파랑 Pang)", 'class="yp-logo"' in body and 'class="yp-yang"' in body and 'class="yp-pang"' in body)
check("헤더 고객센터 링크", 'href="/cs"' in body)

# ---------------- FAQ ----------------
code, body, _ = anon.get("/cs")
check("고객센터 화면 (비회원)", code == 200 and body.count("<details") >= 18 and "자주 묻는 질문" in body)
code, body, _ = anon.get("/cs?category=CLAIM")
check("분류 필터", body.count("<details") == int(q("select count(*) from faq where category='CLAIM'")))
code, body, _ = anon.get("/cs?q=" + urllib.parse.quote("반품비"))
check("FAQ 검색", "반품비는 누가 내나요?" in body and "쿠폰은 어떻게" not in body)
fid = q("select id from faq order by id limit 1")
v0 = int(q(f"select view_count from faq where id={fid}"))
anon.post(f"/cs/faq/{fid}/view", htmx=True)
check("펼친 수 기록", int(q(f"select view_count from faq where id={fid}")) == v0 + 1)

# ---------------- 1:1 문의 ----------------
code, _, h = anon.get("/cs/inquiries")
check("비회원은 로그인으로", code == 302 and "/login" in h.get("Location", ""))
c = Client(); c.login("normal@example.com")
MID = q("select id from member where email='normal@example.com'")
q(f"delete from cs_inquiry where member_id={MID} and title like '테스트%'")
code, body, _ = c.get("/cs/inquiries")
check("내 문의 목록 (시드 2건)", code == 200 and "답변 완료" in body and "답변 대기" in body)
code, body, _ = c.get("/cs/inquiries/new")
myorder = q(f"select order_no from orders where member_id={MID} order by ordered_at desc limit 1")
check("문의 쓰기 화면 (내 주문 고르기)", code == 200 and myorder in body)
n0 = int(q(f"select count(*) from cs_inquiry where member_id={MID}"))
c.post("/cs/inquiries", {"category": "DELIVERY", "title": "테스트 짧음", "content": "짧아요"})
check("내용 10자 미만 거절", int(q(f"select count(*) from cs_inquiry where member_id={MID}")) == n0)
other = q(f"select order_no from orders where member_id <> {MID} limit 1")
c.post("/cs/inquiries", {"category": "DELIVERY", "orderNo": other, "title": "테스트 남의 주문", "content": "남의 주문으로 문의해 봅니다"})
check("남의 주문은 고를 수 없음", int(q(f"select count(*) from cs_inquiry where member_id={MID}")) == n0)
code, _, h = c.post("/cs/inquiries", {"category": "DELIVERY", "orderNo": myorder, "title": "테스트 배송 문의",
                                      "content": "언제 도착하는지 궁금해요. <b>굵게</b>"})
iid = q(f"select id from cs_inquiry where member_id={MID} and title='테스트 배송 문의'")
check("문의 접수 → 상세로", code == 302 and h.get("Location", "").endswith(f"/cs/inquiries/{iid}"))
code, body, _ = c.get(f"/cs/inquiries/{iid}")
check("상세: 주문 연결·내용 이스케이프", code == 200 and myorder in body and "&lt;b&gt;굵게" in body)
otherq = q(f"select id from cs_inquiry where member_id <> {MID} limit 1")
code, _, _ = c.get(f"/cs/inquiries/{otherq}")
check("남의 문의는 404", code == 404)
for k in range(5):
    c.post("/cs/inquiries", {"category": "ETC", "title": f"테스트 많이 {k}", "content": "답변 대기 문의를 계속 남겨 봅니다"})
check("답변 대기 5건까지", q(f"select count(*) from cs_inquiry where member_id={MID} and status='WAITING'") == "5")

a = Client(); a.login("admin@example.com")
code, body, _ = a.get("/admin/cs")
check("운영자 답변 대기 목록", code == 200 and "테스트 배송 문의" in body)
a.post(f"/admin/cs/{iid}/answer", {"answer": "내일 도착 예정이에요. 기다려 주셔서 감사합니다."})
check("답변 → 상태·알림", q(f"select status from cs_inquiry where id={iid}") == "ANSWERED"
      and q(f"select count(*) from notification where member_id={MID} and link='/cs/inquiries/{iid}'") == "1")
code, body, _ = c.get(f"/cs/inquiries/{iid}")
check("회원 화면에 답변", "YangPang 고객센터 답변" in body and "내일 도착 예정이에요" in body)
a.post(f"/admin/cs/{iid}/answer", {"answer": "다시 답변해 봅니다"})
check("이미 답변한 문의는 다시 못 함", "다시 답변" not in q(f"select answer from cs_inquiry where id={iid}"))
code, body, _ = c.get("/admin/cs")
check("회원은 운영자 화면 못 봄", code in (302, 403))

a.post("/admin/faq", {"category": "ETC", "question": "테스트 FAQ 질문입니다", "answer": "테스트 답변", "sortOrder": "99"})
nf = q("select id from faq where question='테스트 FAQ 질문입니다'")
check("운영자 FAQ 추가", bool(nf))
a.post("/admin/faq", {"id": nf, "category": "ETC", "question": "테스트 FAQ 질문 (고침)", "answer": "고친 답변", "sortOrder": "99"})
check("FAQ 고치기", q(f"select question from faq where id={nf}") == "테스트 FAQ 질문 (고침)")
a.post(f"/admin/faq/{nf}/delete")
check("FAQ 지우기", q(f"select count(*) from faq where id={nf}") == "0")
q(f"delete from cs_inquiry where member_id={MID} and title like '테스트%'")

# ---------------- 아이디·비밀번호 찾기 ----------------
code, body, _ = anon.get("/login")
check("로그인 화면 링크 살아 있음", 'href="/find-account"' in body and anon.get("/find-account")[0] == 200)
code, _, h = c.get("/find-account")
check("로그인 상태면 회원정보로", code == 302 and h.get("Location", "").endswith("/my/profile"))

f = Client(); f.get("/find-account")
f.post("/find-account/code", {"mode": "id", "name": "김테스트", "phone": "01000000001"})
body = f.get("/find-account?tab=id")[1]
m = re.search(r'인증번호 <b class="tracking-widest">(\d{6})</b>', body)
check("모의 인증번호 화면 표시", m is not None)
f.post("/find-account/verify", {"code": "000000" if m.group(1) != "000000" else "111111"})
check("틀린 인증번호 거절", "맞지 않거나" in f.get("/find-account?tab=id")[1])
code, body, _ = f.post("/find-account/verify", {"code": m.group(1)})
check("아이디 찾기 → 가린 이메일", code == 200 and "no****@example.com" in body and "normal@example.com" not in body, re.findall(r'[\w*]+@example\.com', body)[:2])

g = Client(); g.get("/find-account?tab=pw")
g.post("/find-account/code", {"mode": "pw", "email": "normal@example.com", "phone": "010-9999-9999"})
code_ = re.search(r'tracking-widest">(\d{6})<', g.get("/find-account?tab=pw")[1]).group(1)
g.post("/find-account/verify", {"code": code_})
check("휴대폰이 다르면 재설정 불가", "일치하는 회원이 없어요" in g.get("/find-account?tab=pw")[1])
code, _, h = g.get("/find-account/reset")
check("인증 없이 재설정 화면 못 감", code == 302)

r = Client(); r.get("/find-account?tab=pw")
r.post("/find-account/code", {"mode": "pw", "email": "plus@example.com", "phone": "010-0000-0003"})
code_ = re.search(r'tracking-widest">(\d{6})<', r.get("/find-account?tab=pw")[1]).group(1)
code, _, h = r.post("/find-account/verify", {"code": code_})
check("인증 → 새 비밀번호 화면", code == 302 and h.get("Location", "").endswith("/find-account/reset") and "pl***@example.com" in r.get("/find-account/reset")[1])
r.post("/find-account/reset", {"password": "short", "passwordConfirm": "short"})
check("약한 비밀번호 거절", "8~20자" in r.get("/find-account/reset")[1])
code, _, h = r.post("/find-account/reset", {"password": "NewPass123!", "passwordConfirm": "NewPass123!"})
check("비밀번호 변경 → 로그인으로", code == 302 and h.get("Location", "").endswith("/login"))
x = Client(); _, loc = x.login("plus@example.com", {"password": "NewPass123!"})
check("새 비밀번호로 로그인", loc is not None and "error" not in loc, loc)
# 원래 비밀번호로 되돌리기 (다른 검사용)
r2 = Client(); r2.get("/find-account?tab=pw")
r2.post("/find-account/code", {"mode": "pw", "email": "plus@example.com", "phone": "010-0000-0003"})
r2.post("/find-account/verify", {"code": re.search(r'tracking-widest">(\d{6})<', r2.get("/find-account?tab=pw")[1]).group(1)})
r2.post("/find-account/reset", {"password": "yangpang1234!", "passwordConfirm": "yangpang1234!"})
y = Client(); _, loc = y.login("plus@example.com")
check("원래 비밀번호로 되돌림", loc is not None and "error" not in loc)

s = Client(); s.get("/find-account")
for _ in range(5):
    s.post("/find-account/code", {"mode": "id", "name": "누구", "phone": "01011112222"})
s.post("/find-account/code", {"mode": "id", "name": "누구", "phone": "01011112222"})
check("인증번호 10분에 5번까지", "너무 많이 요청" in s.get("/find-account?tab=id")[1])
