"""디자인 개편 뒤 화면 점검: 역할별로 주요 화면을 열어 상태 코드와 오류를 본다."""
import re
import subprocess
from yp import Client


def q(sql):
    return subprocess.run(["docker", "exec", "yangpang-postgres", "psql", "-U", "yangpang", "-d", "yangpang", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip()


pid = q("select id from product where status='ON_SALE' order by id limit 1")
order_no = q("select order_no from orders where member_id=1 order by id desc limit 1")
slug = q("select slug from exhibition limit 1")
brand = q("select id from brand limit 1")
cat = q("select id from category where parent_id is null limit 1")
gift = q("select token from gift order by id desc limit 1")
subvi = q("select vendor_item_id from subscription where member_id<>1 order by id desc limit 1")

public = ["/", "/categories", f"/category/{cat}", "/search?q=tent", "/search", f"/products/{pid}", "/cart", "/deals",
          "/ranking", "/fresh", "/brands", f"/brands/{brand}", "/refurb", "/events", f"/events/{slug}", "/coupons",
          "/plus", "/login", "/signup", "/search/suggest?q=a", "/nope-404", *([f"/gift/{gift}"] if gift else []), "/global", "/search?delivery=GLOBAL", "/search/image", "/search/image?color=%EB%B8%94%EB%9E%99",
          "/cs", "/cs?category=CLAIM", "/cs?q=%EB%B0%98%ED%92%88", "/attendance"]
member = ["/my", "/my/orders", f"/my/orders/{order_no}", "/my/wishlist", "/my/recent", "/my/coupons", "/my/cash",
          "/my/notifications", "/my/reviews", "/my/addresses", "/my/plus", "/my/claims", "/partners", "/search?q=%EC%A6%89%EC%84%9D%EB%B0%A5",
          "/my/subscriptions", "/cs/inquiries", "/cs/inquiries/new", "/my/invite", "/my/emails", "/adult-verify", f"/subscriptions/new?vendorItemId={subvi}&quantity=1&cycle=2", "/checkout?gift=true"]
admin = ["/admin", "/admin/products", "/admin/orders", "/admin/banners", "/admin/coupons",
         "/admin/simulator", "/admin/sellers", "/admin/deals", "/admin/exhibitions", "/admin/ranking",
         "/admin/synonyms", "/admin/home", "/admin/categories", "/admin/claims", "/admin/inquiries",
         "/admin/settlements", "/admin/policies", "/admin/emails", "/admin/cs", "/admin/cs?status=ANSWERED", "/admin/faq", "/admin/ads", "/admin/ads?days=30", "/admin/sellers/4", "/admin/sellers/1"]
seller = ["/seller", "/seller/settlements", "/seller/stats", "/seller/stats?days=7", "/seller/stats?days=90", "/seller/ads", "/seller/ads?days=30", "/seller/ads?days=1", "/seller/claims", "/seller/claims?tab=PROGRESS", "/seller/claims?tab=DONE", "/seller/inquiries", "/seller/inquiries?all=true", "/seller/offers", "/seller/orders", "/seller/catalog", "/seller/products/new"]

bad = []
total = 0
for who, email, paths in [("비회원", None, public), ("회원", "normal@example.com", public + member),
                          ("운영자", "admin@example.com", admin), ("판매자", "seller@example.com", seller)]:
    c = Client()
    if email:
        c.login(email)
    for p in paths:
        code, body = c.get(p)[:2]
        total += 1
        ok = code in (200, 302) or (p == "/nope-404" and code == 404)
        if not ok or "Whitelabel" in body or "TemplateInputException" in body:
            bad.append((who, p, code))
print("열어 본 화면", total, "· 문제", len(bad))
for b in bad:
    print("  ", b)
