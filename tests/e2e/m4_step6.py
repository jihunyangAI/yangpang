"""M4 ⑥ 검증: 정산(주 단위·금액·지급·늦은 확정), 판매자 점수(계산식·위너 반영), 제재(경고·판매 정지·해제), 판매자 통계, 운영자 정산·광고 화면."""
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


SID = q("select sm.seller_id from seller_member sm join member m on m.id=sm.member_id where m.email='seller@example.com'")
s = Client(); s.login("seller@example.com")
a = Client(); a.login("admin@example.com")

# ---------------- 정산 ----------------
# 이전 실행에서 억지로 지급 처리한 정산(원래 지급일 전)을 되돌린다
q("update settlement set status='SCHEDULED', paid_at=null, pay_date = period_end + 7 where period_end + 7 > (now() at time zone 'Asia/Seoul')::date")
time.sleep(7)  # 시뮬레이터가 한 번 돌며 다시 계산하도록
check("정산이 주 단위로 만들어짐", int(q(f"select count(*) from settlement where seller_id={SID}")) > 10)
check("양팡 직매입은 정산 없음", q("select count(*) from settlement st join seller x on x.id=st.seller_id where x.seller_type='DIRECT'") == "0")
bad = q("""select count(*) from settlement st where st.status='SCHEDULED' and (
             st.sales_amount <> (select coalesce(sum(unit_price*quantity),0) from order_item where settlement_id=st.id)
          or st.item_count <> (select count(*) from order_item where settlement_id=st.id)
          or st.payout <> st.sales_amount - st.commission - st.ad_cost)""")
check("지급 전 정산 금액 = 상품 합계, 지급액 = 판매 - 수수료 - 광고비", bad == "0", bad)
comm_bad = q("""select count(*) from settlement st where st.commission <> (select coalesce(sum(floor(unit_price*quantity*(10 + case when delivery_type='FULFILLMENT' then 5 else 0 end)/100.0)),0)
                from order_item where settlement_id=st.id)""")
check("수수료 10% (물류 대행 +5%)", comm_bad == "0", comm_bad)
check("구매확정 안 된 상품은 지급 전 정산에 없음", q("select count(*) from order_item oi join settlement st on st.id=oi.settlement_id where st.status='SCHEDULED' and oi.status <> 'CONFIRMED'") == "0")
check("이번 주 확정분은 아직 정산 전", q("""select count(*) from order_item where settlement_id is not null
       and confirmed_at >= date_trunc('week', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul'""") == "0")
ad_bad = q("""select count(*) from settlement st where st.status='SCHEDULED' and st.ad_cost <> (select coalesce(sum(d.cost),0) from ad_stat_daily d
              join ad_campaign c on c.id=d.campaign_id where c.seller_id=st.seller_id and d.day >= st.period_start and d.day < st.period_end)""")
check("광고비 = 그 주 광고비 합계", ad_bad == "0", ad_bad)

# 지난주에 구매확정된 상품 → 지난주 정산(지급 예정)에 들어감
LASTWK = q("select (date_trunc('week', now() at time zone 'Asia/Seoul') - interval '7 days')::date")


def buy_from_seller(n):
    """seller@의 상품을 normal@이 n번 주문 (결제 완료) → 주문 상품 id 목록"""
    c = Client(); c.login("normal@example.com")
    mid = q("select id from member where email='normal@example.com'")
    addr = q(f"select id from address where member_id={mid} and is_default")
    vi = q(f"""select vi.id from vendor_item vi join item i on i.id=vi.item_id join product p on p.id=i.product_id
             where vi.seller_id={SID} and vi.status='ON_SALE' and vi.stock>=5 and p.status='ON_SALE' and vi.delivery_type<>'GLOBAL'
               and vi.item_condition='NEW' and not exists (select 1 from deal d where d.vendor_item_id=vi.id) order by vi.id limit 1""")
    ids = []
    for _ in range(n):
        c.post("/checkout/direct", {"vendorItemId": vi, "quantity": 1}); c.get("/checkout")
        _, _, h = c.post("/checkout/place", {"addressId": addr, "method": "YANGPAY", "agree": "true"})
        no = re.search(r'Y\d{14}', h.get("Location") or "").group(0)
        c.post("/checkout/pay/" + no, {"method": "YANGPAY", "result": "APPROVED"})
        ids.append(q(f"select oi.id from order_item oi join orders o on o.id=oi.order_id where o.order_no='{no}'"))
    return ids


oi, oi2 = buy_from_seller(2)
q(f"update order_item set status='CONFIRMED', confirmed_at = (date '{LASTWK}' + interval '2 days 10 hours') at time zone 'Asia/Seoul' where id={oi}")
check("지난주 확정 상품 → 지난주 정산", wait(f"select st.period_start from order_item oi join settlement st on st.id=oi.settlement_id where oi.id={oi}", LASTWK))
st = q(f"select settlement_id from order_item where id={oi}")
check("지난주 정산은 지급 예정·다시 계산됨", q(f"""select status = 'SCHEDULED' and sales_amount = (select sum(unit_price*quantity) from order_item where settlement_id={st})
        from settlement where id={st}""") == "t")

# 이미 지급한 주에 늦게 확정 → 지난주 정산으로
paid_wk = q(f"select period_start from settlement where seller_id={SID} and status='PAID' order by period_start desc limit 1")
q(f"update order_item set status='CONFIRMED', confirmed_at = (date '{paid_wk}' + interval '1 day') at time zone 'Asia/Seoul' where id={oi2}")
check("지급된 주에 늦게 확정 → 지난주 정산으로", wait(f"select st.period_start from order_item oi join settlement st on st.id=oi.settlement_id where oi.id={oi2}", LASTWK))

# 지급일이 되면 지급 완료 + 알림
q(f"update settlement set pay_date = (now() at time zone 'Asia/Seoul')::date where id={st}")
check("지급일 → 지급 완료 (모의)", wait(f"select status from settlement where id={st}", "PAID"))
check("판매자에게 지급 알림", q("""select count(*) from notification n join member m on m.id=n.member_id where m.email='seller@example.com'
                                 and n.title='정산금이 지급되었어요 (모의)' and n.created_at > now() - interval '1 minute'""") >= "1")

code, body, _ = s.get("/seller/settlements")
check("셀러센터 정산 화면", code == 200 and "이번 주 예상" in body and "지급 완료" in body)
code, body, _ = s.get(f"/seller/settlements/{st}")
check("정산 상세 (상품별)", code == 200 and q(f"select o.order_no from order_item oi join orders o on o.id=oi.order_id where oi.id={oi}") in body)
other = q(f"select id from settlement where seller_id <> {SID} limit 1")
code, _, _ = s.get(f"/seller/settlements/{other}")
check("남의 정산은 404", code == 404)

# ---------------- 판매자 통계 ----------------
code, body, _ = s.get("/seller/stats?days=30")
sales30 = q(f"""select coalesce(sum(oi.unit_price*oi.quantity),0) from order_item oi join orders o on o.id=oi.order_id
               where oi.seller_id={SID} and o.paid_at >= ((now() at time zone 'Asia/Seoul')::date - 29) at time zone 'Asia/Seoul'""")
check("판매 통계 화면 (30일 판매금액 일치)", code == 200 and f"{int(sales30):,}원" in body, sales30)
code, body, _ = s.get("/seller/stats?days=7")
check("판매 통계 7일", code == 200 and "많이 팔린 상품" in body)

# ---------------- 판매자 점수 ----------------
row = q(f"select score, coalesce(rating_avg,0), review_count, delivered, seller_fault_claims, inquiries, answered_48h from seller_score_daily where seller_id={SID} order by day desc limit 1").split("|")
sc, ravg, rn, dl, fc, iq, ans = float(row[0]), float(row[1]), int(row[2]), int(row[3]), int(row[4]), int(row[5]), int(row[6])
rating = 40 * max(0, min(1, ((ravg * rn + 20) / (rn + 5) - 1) / 4))
claim = 35 * max(0, 1 - fc / max(dl, 20) * 10)
inq = 25 if iq == 0 else 25 * min(ans, iq) / iq
check("점수 = 평점 40 + 반품 35 + 문의 25", abs(sc - round(rating + claim + inq, 1)) <= 0.15, f"{sc} vs {rating:.1f}+{claim:.1f}+{inq:.1f}")
check("seller.score에 반영 (아이템 위너용)", q(f"select score from seller where id={SID}") == str(round(sc)))
code, body, _ = s.get("/seller")
check("셀러센터 홈에 점수·구성", "판매자 점수" in body and "문의 48시간 답변" in body)

# 점수가 위너에 반영: 경쟁 오퍼가 있는 SKU에서 점수를 바꾸면 위너가 바뀔 수 있음 → 점수 다시 계산 버튼
code, _, h = a.post("/admin/sellers/recompute-scores")
check("운영자: 점수 지금 계산", code == 302 and q(f"select count(*) from seller_score_daily where seller_id={SID}") >= "1")

# ---------------- 제재 ----------------
code, body, _ = a.get(f"/admin/sellers/{SID}")
check("운영자 판매자 상세", code == 200 and "판매자 점수" in body and "판매 정지" in body)
w0 = int(q(f"select count(*) from seller_sanction where seller_id={SID}"))
a.post(f"/admin/sellers/{SID}/warn", {"reason": "짧음"})
check("사유 5자 미만이면 거절", int(q(f"select count(*) from seller_sanction where seller_id={SID}")) == w0)
a.post(f"/admin/sellers/{SID}/warn", {"reason": "상품 상세 정보가 실제와 다르다는 신고 (테스트)"})
check("경고 기록·판매자 알림", int(q(f"select count(*) from seller_sanction where seller_id={SID} and type='WARNING'")) >= 1
      and q("select count(*) from notification n join member m on m.id=n.member_id where m.email='seller@example.com' and n.title='판매자 경고를 받았어요'") >= "1")
DIRECT = q("select id from seller where seller_type='DIRECT' limit 1")
a.post(f"/admin/sellers/{DIRECT}/suspend", {"reason": "직매입 정지 시도 (테스트)"})
check("양팡 직매입은 정지 불가", q(f"select status from seller where id={DIRECT}") == "ACTIVE")

on_sale = int(q(f"select count(*) from vendor_item where seller_id={SID} and status='ON_SALE'"))
winners0 = int(q(f"select count(*) from vendor_item where seller_id={SID} and is_winner"))
a.post(f"/admin/sellers/{SID}/suspend", {"reason": "반복된 허위 상품 정보 (테스트)"})
check("판매 정지 → 판매자 상태", q(f"select status from seller where id={SID}") == "SUSPENDED")
check("판매 중 오퍼 모두 판매중지·기록", q(f"select count(*) from vendor_item where seller_id={SID} and status='ON_SALE'") == "0"
      and q(f"select cardinality(offer_ids) from seller_sanction where seller_id={SID} and type='SUSPEND' order by id desc limit 1") == str(on_sale), str(on_sale))
only = q(f"""select p.id from product p where exists (select 1 from item i join vendor_item vi on vi.item_id=i.id where i.product_id=p.id and vi.seller_id={SID})
             and not exists (select 1 from item i join vendor_item vi on vi.item_id=i.id where i.product_id=p.id and vi.seller_id<>{SID}) limit 1""")
check("혼자 팔던 상품은 품절 처리", q(f"select sold_out from product where id={only}") == "t")
code, body, _ = s.get("/seller")
check("판매자 화면: 판매 정지 안내", code == 200 and "판매 정지 중이에요" in body)
vi = q(f"select id from vendor_item where seller_id={SID} limit 1")
s.get("/seller/offers")
before = q(f"select sale_price from vendor_item where id={vi}")
s.post(f"/seller/offers/{vi}", {"listPrice": "99000", "salePrice": "1000", "stock": "5", "status": "ON_SALE"})
check("정지 중 가격·판매 상태 변경 막음", q(f"select sale_price || status from vendor_item where id={vi}") == before + "STOPPED")
code, _, _ = s.get("/seller/orders")
check("정지 중에도 주문 처리 화면은 열림", code == 200)

a.post(f"/admin/sellers/{SID}/lift", {"reason": "소명 자료 확인 후 해제 (테스트)"})
check("해제 → 판매 중, 멈췄던 오퍼 복구", q(f"select status from seller where id={SID}") == "ACTIVE"
      and int(q(f"select count(*) from vendor_item where seller_id={SID} and status='ON_SALE'")) == on_sale)
check("해제 후 위너 다시 계산", int(q(f"select count(*) from vendor_item where seller_id={SID} and is_winner")) == winners0,
      q(f"select count(*) from vendor_item where seller_id={SID} and is_winner") + f" / {winners0}")
check("제재 이력 3종", q(f"select string_agg(distinct type, ',' order by type) from seller_sanction where seller_id={SID}") == "LIFT,SUSPEND,WARNING")

# ---------------- 운영자 화면 ----------------
code, body, _ = a.get("/admin/settlements")
check("운영자 정산 현황", code == 200 and "양팡 수익" in body and "판매자별 누적" in body)
code, body, _ = a.get("/admin/ads?days=7")
cost7 = q("select coalesce(sum(cost),0) from ad_stat_daily where day > (now() at time zone 'Asia/Seoul')::date - 7")
check("운영자 광고 현황 (7일 광고비 일치)", code == 200 and f"{int(cost7):,}원" in body, cost7)
code, _, _ = s.get("/admin/ads")
check("판매자는 운영자 화면 못 봄", code in (302, 403))
