"""테스트용 가짜 Claude Messages API (돈 들지 않음). 요청 본문을 requests.jsonl에 남긴다.
- 리뷰 요약 시스템 프롬프트 → JSON 요약
- 챗봇: 질문 → search_products 도구 호출 / 도구 결과 → 첫 상품을 추천하는 답
- 질문에 '거절테스트' → refusal, '오류테스트' → 500
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = sys.argv[2] if len(sys.argv) > 2 else "requests.jsonl"


def msg(content, stop, extra=None):
    m = {"id": "msg_mock", "type": "message", "role": "assistant", "model": "claude-opus-5-5", "content": content,
         "stop_reason": stop, "stop_sequence": None,
         "usage": {"input_tokens": 120, "output_tokens": 40, "cache_read_input_tokens": 100, "cache_creation_input_tokens": 0}}
    if extra:
        m.update(extra)
    return m


def text_of(content):
    if isinstance(content, str):
        return content
    return " ".join(b.get("text", "") for b in content if b.get("type") == "text")


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        if code >= 500:
            self.send_header("x-should-retry", "false")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps({"path": self.path, "beta": self.headers.get("anthropic-beta"), "body": body}, ensure_ascii=False) + "\n")
        system = " ".join(b["text"] for b in body.get("system", []))
        msgs = body["messages"]
        last = msgs[-1]
        if "리뷰를 요약" in system:
            return self.send(200, msg([{"type": "text", "text": '```json\n{"summary": "배송이 빠르고 품질이 좋다는 평이 많아요.", "pros": ["빠른 배송", "좋은 품질"], "cons": ["포장이 아쉬움"]}\n```'}], "end_turn"))
        content = last["content"]
        if isinstance(content, list) and any(b.get("type") == "tool_result" for b in content):
            result = next(b for b in content if b.get("type") == "tool_result")
            rc = result["content"] if isinstance(result["content"], str) else text_of(result["content"])
            m = re.search(r"\[(\d+)\] ([^·]+)", rc)
            answer = f"**{m.group(2).strip()}**을(를) 추천해요. /products/{m.group(1)} 에서 볼 수 있어요." if m else "찾은 상품이 없어요."
            return self.send(200, msg([{"type": "thinking", "thinking": "", "signature": "sig"}, {"type": "text", "text": answer}], "end_turn"))
        q = text_of(content)
        if "거절테스트" in q:
            return self.send(200, msg([], "refusal", {"stop_details": {"type": "refusal", "category": None, "explanation": "test"}}))
        if "오류테스트" in q:
            return self.send(500, {"type": "error", "error": {"type": "api_error", "message": "mock failure"}})
        if "주문" in q:
            return self.send(200, msg([{"type": "tool_use", "id": "toolu_2", "name": "my_recent_orders", "input": {}}], "tool_use"))
        word = q.split()[0] if q.split() else "상품"
        return self.send(200, msg([{"type": "text", "text": "찾아볼게요."},
                                   {"type": "tool_use", "id": "toolu_1", "name": "search_products", "input": {"query": word}}], "tool_use"))


HTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8765), H).serve_forever()
