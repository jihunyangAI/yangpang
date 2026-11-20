"""M4 ⑧ 검증. 사용법: python m4_step8.py on   (가짜 Claude API로 켠 상태)
                      python m4_step8.py off  (API 키 없이 꺼진 상태)"""
import json
import os
import re
import subprocess
import sys
from yp import Client

MODE = sys.argv[1] if len(sys.argv) > 1 else "on"
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "requests.jsonl")


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def check(name, cond, extra=""):
    print(("OK  " if cond else "FAIL"), name, extra)


def requests_log():
    if not os.path.exists(LOG):
        return []
    with open(LOG, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


PID = q("select id from product where review_count >= 5 and status='ON_SALE' order by id limit 1")
FEW = q("select id from product where review_count between 1 and 4 and status='ON_SALE' order by id limit 1")
c = Client(); c.login("normal@example.com")

if MODE == "off":
    code, body, _ = c.get(f"/products/{PID}")
    check("키 없으면 리뷰 요약 칸 없음", code == 200 and "review-summary" not in body)
    check("키 없으면 챗봇 없음", 'id="chat-widget"' not in body)
    for path in (f"/products/{PID}/review-summary", "/chat"):
        code, _, _ = c.get(path)
        check(f"키 없으면 {path} 404", code == 404)
    sys.exit()

# ---------------- 켜진 상태 (가짜 API) ----------------
q(f"delete from review_summary where product_id={PID}")
code, body, _ = c.get(f"/products/{PID}")
check("상세에 AI 리뷰 요약 자리 (리뷰 5개 이상)", f"/products/{PID}/review-summary" in body)
code, body, _ = c.get(f"/products/{FEW}")
check("리뷰 4개 이하는 요약 없음", f"/products/{FEW}/review-summary" not in body)
check("챗봇 위젯", 'id="chat-widget"' in body)

n0 = len(requests_log())
code, body, _ = c.get(f"/products/{PID}/review-summary", headers={"HX-Request": "true"})
check("리뷰 요약 생성·표시 (코드 블록 JSON도 읽음)", code == 200 and "배송이 빠르고" in body and "빠른 배송" in body and "포장이 아쉬움" in body)
check("요약 DB 캐시", q(f"select review_count from review_summary where product_id={PID}") == q(f"select count(*) from review where product_id={PID} and status='VISIBLE'"))
c.get(f"/products/{PID}/review-summary", headers={"HX-Request": "true"})
check("두 번째는 캐시에서 (API 호출 없음)", len(requests_log()) == n0 + 1)
q(f"update review_summary set created_at = now() - interval '8 days' where product_id={PID}")
c.get(f"/products/{PID}/review-summary", headers={"HX-Request": "true"})
check("7일 지나면 다시 만듦", len(requests_log()) == n0 + 2)
q(f"update review_summary set review_count = review_count - 5 where product_id={PID}")
c.get(f"/products/{PID}/review-summary", headers={"HX-Request": "true"})
check("리뷰가 5개 늘면 다시 만듦", len(requests_log()) == n0 + 3)

req = requests_log()[-1]["body"]
check("모델 claude-opus-5-5", req["model"] == "claude-opus-5-5")
check("노력 low", req.get("output_config", {}).get("effort") == "low")
check("대체 모델 fallbacks=default + 베타 헤더", req.get("fallbacks") == "default" and "server-side-fallback-2026-07-01" in (requests_log()[-1]["beta"] or ""))
check("시스템 프롬프트 캐시 지점 + 자동 캐싱", req["system"][0].get("cache_control", {}).get("type") == "ephemeral" and req.get("cache_control", {}).get("type") == "ephemeral")
check("리뷰 본문이 사용자 메시지로", "★" in req["messages"][0]["content"] if isinstance(req["messages"][0]["content"], str) else False)
check("max_tokens 넉넉히 (8000)", req["max_tokens"] == 8000)

# 챗봇
code, body, _ = c.get("/chat", headers={"HX-Request": "true"})
check("챗봇 패널 열기", code == 200 and "쇼핑 도우미" in body)
n1 = len(requests_log())
code, body, _ = c.post("/chat", {"message": "요가매트 추천해 줘"}, htmx=True)
check("질문 → 도구 호출 → 답 (API 2번)", code == 200 and len(requests_log()) == n1 + 2, str(len(requests_log()) - n1))
reqs = requests_log()[n1:]
tools = [t["name"] for t in reqs[0]["body"]["tools"]]
check("도구 4개 정의", tools == ["search_products", "get_product", "my_recent_orders", "shop_policy"], str(tools))
tr = [b for b in reqs[1]["body"]["messages"][-1]["content"] if b.get("type") == "tool_result"]
check("도구 결과를 한 메시지로 돌려줌", len(tr) == 1 and tr[0]["tool_use_id"] == "toolu_1" and "/products/" in tr[0]["content"])
check("도구 호출 응답을 그대로 다시 보냄", any(b.get("type") == "tool_use" for b in reqs[1]["body"]["messages"][-2]["content"]))
m = re.search(r'<a href="(/products/\d+)"', body)
check("답의 상품 경로가 링크로", m is not None and "<b>" in body)
pid_in = m.group(1).split("/")[-1] if m else "0"
check("추천 상품은 실제 검색 결과", q(f"select lower(name) like '%요가매트%' or exists (select 1 from category c where c.id=category_id and c.name like '%요가매트%') from product where id={pid_in}") == "t")

n2 = len(requests_log())
c.post("/chat", {"message": "내 주문 어디쯤 왔어?"}, htmx=True)
tr2 = [b for b in requests_log()[n2 + 1]["body"]["messages"][-1]["content"] if b.get("type") == "tool_result"][0]["content"]
mine = q("select order_no from orders where member_id=(select id from member where email='normal@example.com') order by ordered_at desc limit 1")
check("내 주문 도구는 로그인 회원 것", mine in tr2)
hist = requests_log()[n2]["body"]["messages"]
check("대화가 이어짐 (이전 질문·도구 기록 포함)", len(hist) == 5 and hist[0]["content"] == "요가매트 추천해 줘", str(len(hist)))

anon = Client(); anon.get("/")
n3 = len(requests_log())
anon.post("/chat", {"message": "내 주문 확인해 줘"}, htmx=True)
tr3 = [b for b in requests_log()[n3 + 1]["body"]["messages"][-1]["content"] if b.get("type") == "tool_result"][0]["content"]
check("비회원이면 주문 대신 로그인 안내", "로그인하지 않았어요" in tr3 and mine not in tr3)

code, body, _ = c.post("/chat", {"message": "거절테스트"}, htmx=True)
check("거절(refusal)이면 정중히 안내", "도와드리기 어려워요" in body)
n4 = len(requests_log())
code, body, _ = c.post("/chat", {"message": "오류테스트"}, htmx=True)
check("API 오류면 안내 (500 아님)", code == 200 and "AI가 답할 수 없어요" in body)
code, body, _ = c.post("/chat", {"message": "요가매트 또 보여 줘"}, htmx=True)
last_hist = requests_log()[-2]["body"]["messages"]
check("오류 난 질문은 대화에서 빠짐", all("오류테스트" not in json.dumps(m_, ensure_ascii=False) for m_ in last_hist))
code, body, _ = c.post("/chat", {"message": "<script>alert(1)</script> 매트"}, htmx=True)
check("질문은 이스케이프 (스크립트 안 됨)", "<script>alert" not in body and "&lt;script&gt;" in body)
code, body, _ = c.post("/chat/reset", htmx=True)
check("새 대화", code == 200 and "요가매트" not in body)
