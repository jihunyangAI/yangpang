"""M3 ⑦ 이월 항목 확인"""
import re, subprocess, time, urllib.parse
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


def text(h):
    return re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', h)).strip()


def headings(html):
    return re.findall(r'<h2 class="text-lg font-bold"[^>]*>(?:<span>)?([^<]+)', html)


# --- 홈 섹션 편집 ---
g = Client()
print("홈 섹션(비회원):", headings(g.get("/")[1]))
a = Client(); a.login("admin@example.com")
print("관리자 홈 구성:", a.get("/admin/home")[0])
form = {}
for k, t, v, o in [l.split('|') for l in q("select kind, title, visible, sort_order from home_section").splitlines()]:
    form[f"title_{k}"] = t; form[f"order_{k}"] = o
    if v == 't' and k != 'FRESH': form[f"visible_{k}"] = "true"
form["title_RANKING"] = "지금 뜨는 랭킹"
a.post("/admin/home", form)
a.post("/admin/home/move", {"kind": "RANKING", "dir": "up"}); a.post("/admin/home/move", {"kind": "RANKING", "dir": "up"})
print("프레시 숨김·랭킹 제목 변경·두 칸 위로 →", headings(g.get("/")[1]))
form["visible_FRESH"] = "true"; form["title_RANKING"] = "양팡 랭킹 TOP 10"
a.post("/admin/home", form)

# --- 동의어 ---
def search_count(word):
    return len(set(re.findall(r'href="/products/(\d+)"', g.get("/search?q=" + urllib.parse.quote(word))[1])))
name = q("select name from product where name like '%텐트%' limit 1")
print("동의어 전 '야영천막':", search_count("야영천막"))
a.get("/admin/synonyms")
code, _, h = a.post("/admin/synonyms", {"words": "텐트, 야영천막 , 텐트"})
print("동의어 추가:", q("select words from search_synonym order by id desc limit 1"))
time.sleep(3)
print("동의어 후 '야영천막':", search_count("야영천막"), "| '텐트':", search_count("텐트"))
a.post("/admin/synonyms", {"words": "혼자"})
print("단어 1개만 → 거절:", q("select count(*) from search_synonym where words='혼자'"))
sid = q("select id from search_synonym where words like '%야영천막%'")
a.post(f"/admin/synonyms/{sid}/delete"); time.sleep(3)
print("삭제 후 '야영천막':", search_count("야영천막"))

# --- 캐시 소멸 ---
c = Client(); c.login("normal@example.com")
cash = c.get("/my/cash")[1]
print("캐시 화면:", re.findall(r'30일 안에 소멸 예정 [0-9,]+원', cash), "| 소멸 내역:", "유효기간 만료" in cash or "소멸" in cash)
print("소멸 알림:", q("select title||' / '||body from notification where member_id=1 and title like '%소멸%'"))
bal_before = q("select sum(amount) from cash_ledger where member_id=1")
a.get("/admin/simulator"); a.post("/admin/simulator/jump", {"hours": 24 * 11}); time.sleep(8)
print("11일 뒤:", q("select string_agg(amount::text, ', ' order by id) from cash_ledger where member_id=1 and entry_type='EXPIRE'"),
      "| 잔액", bal_before, "→", q("select sum(amount) from cash_ledger where member_id=1"))
a.post("/admin/simulator/reset")

# --- 찜 폴더 ---
c.get("/my/wishlist")
code, _, h = c.post("/my/wishlist/folders", {"name": "캠핑 준비"})
fid = h.get("Location").split("folder=")[-1]
pid = q("select product_id from wishlist where member_id=1 order by product_id limit 1")
code, body, _ = c.post(f"/my/wishlist/{pid}/folder", {"folderId": fid}, htmx=True)
print("폴더 만들기·옮기기:", text(body), "| 폴더 화면 상품:", len(set(re.findall(r'href="/products/(\d+)"', c.get(f"/my/wishlist?folder={fid}")[1]))))
c.post("/my/wishlist/folders", {"name": "캠핑 준비"})
print("같은 이름 폴더 → 거절:", q("select count(*) from wishlist_folder where member_id=1"))
other = Client(); other.login("plus@example.com")
code, body, _ = other.post(f"/my/wishlist/{pid}/folder", {"folderId": fid}, htmx=True)
print("남의 폴더로 옮기기:", text(body))
c.post(f"/my/wishlist/folders/{fid}/delete")
print("폴더 삭제 후 찜 유지:", q(f"select count(*)||' folder='||coalesce(max(folder_id)::text,'없음') from wishlist where member_id=1 and product_id={pid}"))
