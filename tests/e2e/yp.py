"""양팡 테스트용 작은 HTTP 클라이언트 (쿠키·CSRF 처리, 한글·경로 인코딩 문제 없음)."""
import http.cookiejar
import re
import urllib.parse
import urllib.request

BASE = "http://localhost:8090"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect())
        self.csrf = None

    def get(self, path, headers=None):
        req = urllib.request.Request(BASE + path, headers=headers or {})
        try:
            r = self.op.open(req)
            body = r.read().decode("utf-8")
            m = re.search(r'name="csrf-token" content="([^"]*)"', body)
            if m:
                self.csrf = m.group(1)
            return r.status, body, r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace"), e.headers

    def post(self, path, data=None, htmx=False, form_token=None):
        data = dict(data or {})
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        if form_token:
            data["_csrf"] = form_token
        elif self.csrf:
            headers["X-CSRF-TOKEN"] = self.csrf
        if htmx:
            headers["HX-Request"] = "true"
        req = urllib.request.Request(BASE + path, data=urllib.parse.urlencode(data).encode(), headers=headers, method="POST")
        try:
            r = self.op.open(req)
            return r.status, r.read().decode("utf-8"), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace"), e.headers

    def login(self, email, extra=None):
        _, body, _ = self.get("/login")
        token = re.search(r'name="_csrf" value="([^"]*)"', body).group(1)
        data = {"email": email, "password": "yangpang1234!"}
        data.update(extra or {})
        code, _, headers = self.post("/login", data, form_token=token)
        self.get("/")  # 새 CSRF 토큰
        return code, headers.get("Location")
