"""M4 ③ 검증: 검색 광고(노출 위치·순위·과금·중복 클릭·위조·예산·멈춤·전환·셀러센터) + 파트너스(링크·쿠키·실적·자기 구매 제외·확정)."""
import html
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


def ad_links(body):
    """그리드 순서대로 (광고 여부, 주소)"""
    out = []
    for m in re.finditer(r'<a href="([^"]+)"(?: rel="sponsored")?\s+class="group flex flex-col', body):
        href = html.unescape(m.group(1))
        out.append((href.startswith("/ad/click"), href))
    return out


def buy(client, email, vendor_item_id):
    mid = q(f"select id from member where email='{email}'")
    client.post("/checkout/direct", {"vendorItemId": vendor_item_id, "quantity": 1}); client.get("/checkout")
    code, _, h = client.post("/checkout/place", {"addressId": q(f"select id from address where member_id={mid} and is_default"),
                                                 "method": "YANGPAY", "agree": "true"})
    no = re.search(r'Y\d{14}', h.get("Location") or "").group(0)
    client.post("/checkout/pay/" + no, {"method": "YANGPAY", "result": "APPROVED"})
    return no


KW = q("select keyword from ad_keyword group by keyword order by count(*) desc, keyword limit 1")
print("키워드:", KW)
c = Client(); c.login("normal@example.com")
imp_before = int(q("select coalesce(sum(impressions),0) from ad_stat_daily where day = (now() at time zone 'Asia/Seoul')::date"))
code, body, _ = c.get("/search?q=" + urllib.parse.quote(KW))
slots = ad_links(body)
ads = [h for a, h in slots if a]
check("검색 결과 200", code == 200)
check("1·2번째 칸이 광고", len(slots) >= 2 and slots[0][0] and (slots[1][0] or len(ads) == 1), f"광고 {len(ads)}개 / 칸 {len(slots)}개")
check("AD 표시", ">AD<" in body)
organic = sum(1 for a, _ in slots if not a)
check("광고 수 ≤ 2 + (일반-1)/10", len(ads) <= 2 + (organic - 1) // 10)
imp_after = int(q("select coalesce(sum(impressions),0) from ad_stat_daily where day = (now() at time zone 'Asia/Seoul')::date"))
check("노출 기록", imp_after - imp_before == len(ads), f"+{imp_after - imp_before}")

# 순위: 입찰가 x 품질 내림차순
kids = [int(re.search(r'k=(\d+)', h).group(1)) for h in ads]
ranks = [float(q(f"select k.bid * (0.5 + least(100, greatest(0, p.ranking_score)) / 100.0) from ad_keyword k join ad_campaign c on c.id=k.campaign_id join product p on p.id=c.product_id where k.id={k}")) for k in kids]
check("광고 순위 = 입찰가 x 품질 순", ranks == sorted(ranks, reverse=True), str([round(r) for r in ranks]))

# 클릭 → 과금 1번, 같은 링크 다시 → 과금 없음, 위조 → 과금 없음
first = ads[0]
cpc = int(re.search(r'c=(\d+)', first).group(1))
bid0 = int(q(f"select bid from ad_keyword where id={kids[0]}"))
check("클릭당 비용 ≤ 입찰가, ≥ 100", 100 <= cpc <= bid0, f"{cpc} / 입찰 {bid0}")
clicks0 = int(q("select count(*) from ad_click"))
code, _, h = c.get(first)
pid = q(f"select c.product_id from ad_keyword k join ad_campaign c on c.id=k.campaign_id where k.id={kids[0]}")
check("클릭 → 상품으로 이동", code == 302 and h.get("Location", "").endswith(f"/products/{pid}"))
check("클릭 과금 기록", int(q("select count(*) from ad_click")) == clicks0 + 1 and q("select cost from ad_click order by id desc limit 1") == str(cpc))
c.get(first)
check("같은 노출 다시 클릭 → 추가 과금 없음", int(q("select count(*) from ad_click")) == clicks0 + 1)
c.get(re.sub(r's=[^&]+', 's=forgedforgedforgedforgedforged12', first.replace(f"c={cpc}", "c=100")))
check("위조 링크 → 과금 없음", int(q("select count(*) from ad_click")) == clicks0 + 1)

# 전환: 광고 클릭 후 그 상품 구매
vi = q(f"select rep_vendor_item_id from product where id={pid}")
no = buy(c, "normal@example.com", vi)
check("광고 클릭 후 구매 → 전환", q(f"select count(*) from ad_click a join orders o on o.id=a.order_id where o.order_no='{no}'") == "1")
check("일별 성과에 전환 반영", int(q(f"select coalesce(sum(conversions),0) from ad_stat_daily where keyword_id={kids[0]} and day=(now() at time zone 'Asia/Seoul')::date")) >= 1)

# 예산 소진 → 노출 안 됨, 멈춤 → 노출 안 됨
camp = q(f"select campaign_id from ad_keyword where id={kids[0]}")
q(f"update ad_campaign set daily_budget=1000 where id={camp}")
q(f"update ad_stat_daily set cost = cost + 1000 where keyword_id={kids[0]} and day=(now() at time zone 'Asia/Seoul')::date")
code, body, _ = c.get("/search?q=" + urllib.parse.quote(KW))
check("하루 예산 소진 → 그 광고 안 보임", f"k={kids[0]}&" not in html.unescape(body))
q(f"update ad_campaign set daily_budget=50000 where id={camp}")
if len(kids) > 1:
    camp2 = q(f"select campaign_id from ad_keyword where id={kids[1]}")
    q(f"update ad_campaign set status='PAUSED' where id={camp2}")
    code, body, _ = c.get("/search?q=" + urllib.parse.quote(KW))
    check("멈춘 광고 안 보임", f"k={kids[1]}&" not in html.unescape(body))
    q(f"update ad_campaign set status='ACTIVE' where id={camp2}")

g = Client()
code, body, _ = g.get("/search?q=" + urllib.parse.quote(KW) + "&page=2")
check("2페이지에는 광고 없음", "/ad/click" not in body)

# 셀러센터: 광고 만들기 → 최고 입찰로 1위
s = Client(); s.login("seller@example.com")
code, body, _ = s.get("/seller/ads")
check("셀러센터 광고 화면", code == 200 and "검색 광고" in body)
SID = q("select sm.seller_id from seller_member sm join member m on m.id=sm.member_id where m.email='seller@example.com'")
myprod = q(f"""select p.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
               where vi.seller_id={SID} and p.status='ON_SALE' and not p.sold_out and vi.stock>0 and vi.status='ON_SALE' and vi.item_condition='NEW' limit 1""")
MYKW = q(f"select lower(c.name) from product p join category c on c.id=p.category_id where p.id={myprod}")
q(f"delete from ad_campaign where seller_id={SID} and product_id={myprod}")
s.post("/seller/ads", {"productId": myprod, "dailyBudget": "30000", "keywords": f"{MYKW} 9000\n즉석밥다이어리 300"})
check("관련 없는 키워드가 섞이면 거절", q(f"select count(*) from ad_campaign where seller_id={SID} and product_id={myprod}") == "0")
s.get("/seller/ads")
s.post("/seller/ads", {"productId": myprod, "dailyBudget": "30000", "keywords": f"{MYKW} 9000"})
newk = q(f"select k.id from ad_keyword k join ad_campaign c on c.id=k.campaign_id where c.seller_id={SID} and c.product_id={myprod} and k.keyword='{MYKW}'")
check("광고 만들기 (상품 소분류 키워드)", bool(newk), MYKW)
code, body, _ = g.get("/search?q=" + urllib.parse.quote(MYKW))
first_k = re.search(r'/ad/click\?k=(\d+)', html.unescape(body))
check("입찰가 9,000원 광고가 1위", first_k is not None and first_k.group(1) == newk)
other = q("select id from ad_keyword where campaign_id not in (select id from ad_campaign where seller_id=" + SID + ") limit 1")
s.post(f"/seller/ads/keywords/{other}/bid", {"bid": "5000"})
check("남의 키워드 입찰가는 못 바꿈", q(f"select bid from ad_keyword where id={other}") != "5000")
s.post("/seller/ads", {"productId": q(f"select id from product where id not in (select i.product_id from vendor_item vi join item i on i.id=vi.item_id where vi.seller_id={SID}) limit 1"),
                       "dailyBudget": "10000", "keywords": "남의상품 300"})
check("남의 상품은 광고 못 함", q("select count(*) from ad_keyword where keyword='남의상품'") == "0")

# ---------------- 파트너스 ----------------
ppid = q("select id from product where status='ON_SALE' and not sold_out and not adult and rep_delivery_type='YANGPANG' order by id desc limit 1")
code, body, _ = c.post("/partners/links", {"productId": ppid}, htmx=True)
m = re.search(r'/p/([A-Za-z0-9]+)"', body)
check("파트너스 링크 만들기", m is not None)
code_ = m.group(1)
c.post("/partners/links", {"productId": ppid}, htmx=True)
check("같은 상품은 같은 링크", q(f"select count(*) from affiliate_link where product_id={ppid} and member_id=(select id from member where email='normal@example.com')") == "1")

buyer = Client(); buyer.login("plus@example.com")
clicks_before = int(q(f"select clicks from affiliate_link where code='{code_}'"))
code, _, h = buyer.get(f"/p/{code_}")
check("링크 방문 → 상품으로 이동", code == 302 and h.get("Location", "").endswith(f"/products/{ppid}"))
check("클릭 수 +1", int(q(f"select clicks from affiliate_link where code='{code_}'")) == clicks_before + 1)
check("24시간 쿠키", any(ck.name == "yp_aff" and ck.value == code_ for ck in buyer.jar))
no = buy(buyer, "plus@example.com", q(f"select rep_vendor_item_id from product where id={ppid}"))
e = q(f"select e.status || ',' || e.amount || ',' || e.base_amount from affiliate_earning e join orders o on o.id=e.order_id where o.order_no='{no}'")
check("다른 회원 구매 → 예정 실적 3%", e.startswith("PENDING,") and int(e.split(",")[1]) == int(int(e.split(",")[2]) * 0.03), e)

c.get(f"/p/{code_}")
no_self = buy(c, "normal@example.com", q(f"select rep_vendor_item_id from product where id={ppid}"))
check("내 링크로 내가 산 건 실적 아님", q(f"select count(*) from affiliate_earning e join orders o on o.id=e.order_id where o.order_no='{no_self}'") == "0")

code, body, _ = c.get("/partners")
check("파트너스 화면", code == 200 and f"/p/{code_}" in body and "확정 예정" in body)

# 구매확정되면 확정 (시뮬레이터)
q(f"update order_item set status='CONFIRMED', confirmed_at=now() where order_id=(select id from orders where order_no='{no}')")
for _ in range(15):
    if q(f"select e.status from affiliate_earning e join orders o on o.id=e.order_id where o.order_no='{no}'") == "CONFIRMED":
        break
    time.sleep(1)
check("구매확정 → 실적 확정 (시뮬레이터)", q(f"select e.status from affiliate_earning e join orders o on o.id=e.order_id where o.order_no='{no}'") == "CONFIRMED")
check("시드 실적도 상태 정리됨", q("select count(*) from affiliate_earning where status <> 'PENDING'") != "0")
