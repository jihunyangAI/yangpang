# 화면·흐름 검사 (E2E)

실제로 떠 있는 앱에 HTTP 요청을 보내고 DB를 확인하는 검사 스크립트예요. 단위 테스트(`./gradlew test`)가 계산 규칙을 보고,
이 검사는 화면·로그인·주문·시뮬레이터가 실제로 맞물려 돌아가는지 봐요.

## 준비

- Python 3.10 이상. `post_install_adult_voice.py`·`m4_step7.py`는 Pillow가 필요해요 (`pip install pillow`)
- Docker로 PostgreSQL이 떠 있어야 해요 (`docker exec yangpang-postgres ...`로 DB를 확인해요)
- 앱 실행: `./gradlew :api:bootRun` → http://localhost:8090
- 가짜 데이터가 있어야 해요: `./gradlew :seed:bootRun` (앱을 끄고 실행)

## 실행

```bash
python tests/e2e/run_all.py           # 전체 (약 10분)
python tests/e2e/run_all.py m4_step6  # 이름으로 골라서
python tests/e2e/smoke.py             # 화면 120여 개 상태 코드만 빠르게
```

검사는 주문·회원·문의 등을 실제로 만들고, 운영 기준값은 바꿨다가 기본값으로 되돌려요.
`m3_carryover.py`는 캐시 소멸을 보려고 시뮬레이션 시계를 11일 앞으로 돌렸다 되돌려요. 그사이 시뮬레이터가 미래 날짜로 정산 등을 만들 수 있어
`run_all.py`는 이 검사를 맨 마지막에 돌려요 (따로 돌린 뒤 다른 검사를 하면 정산 검사가 실패할 수 있어요 → 가짜 데이터를 다시 만들고 돌리세요).
끝나면 가짜 데이터를 다시 만드는 것을 권장해요 (`./gradlew :seed:bootRun`).

## 파일

| 파일 | 보는 것 |
|---|---|
| `yp.py` | 검사용 작은 HTTP 클라이언트 (쿠키·CSRF·로그인) |
| `smoke.py` | 비회원·회원·판매자·운영자로 주요 화면을 열어 4xx/5xx가 없는지 |
| `scenarios_1_4_5.py` | 기획서 13장 검증 시나리오 1(비회원 → 장바구니 병합 → 주문)·4(배송 → 자동 구매확정 → 리뷰)·5(쿠폰·캐시 주문 중 1개 반품 → 안분 환불) |
| `m3_*.py` | M3: 기획전·리퍼, 알림, 캐시 소멸·찜 폴더, 시나리오 8(특가 재고 10개에 동시 주문 100건) — 값을 출력하는 형식 |
| `m4_step1.py` ~ `m4_step8.py` | M4 ①~⑧: 판매자 검수·문의, 추천, 광고·파트너스, 정기배송·선물, 직구·비즈, 정산·점수·제재, 이미지 검색, AI |
| `post_*.py` | M4 이후: 고객센터·계정 찾기, 운영 기준값, 출석·친구초대, 이메일(모의), 양팡설치·성인 인증·음성 검색 |
| `mock_claude.py` | AI 검사용 가짜 Claude API (요금 없음, 요청 본문을 `requests.jsonl`에 기록) |
| `run_all.py` | 위 검사를 순서대로 실행하고 OK/FAIL을 모아 보여 줌 |

## AI 기능 검사 (`m4_step8.py`)

- `python m4_step8.py off`: API 키 없이 띄운 앱에서 AI 기능이 숨겨지는지 (run_all이 이것만 돌려요)
- `python m4_step8.py on`: 가짜 Claude 서버로 켠 앱에서 요약·챗봇이 도는지
  ```bash
  python tests/e2e/mock_claude.py 8765 tests/e2e/requests.jsonl &      # 가짜 Claude API
  YANGPANG_AI_API_KEY=mock-key YANGPANG_AI_BASE_URL=http://127.0.0.1:8765 ./gradlew :api:bootRun
  python tests/e2e/m4_step8.py on
  ```
  실제 Claude로 확인하려면 앱을 띄우기 전에 `ANTHROPIC_API_KEY`만 설정하면 돼요 (실제 요금 발생).
