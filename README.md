# YangPang (양팡)

쿠팡형 종합 쇼핑몰의 구조와 기능을 연구하기 위한 **연구용 데모**입니다. 실제 판매나 결제는 없고, 모든 데이터는 가짜입니다.

- 기획서: [`yangpang.md`](yangpang.md)
- 레퍼런스 분석: [`reference/coupang.md`](reference/coupang.md)

## 필요한 것

| 도구 | 버전 |
|---|---|
| JDK | 25 (Eclipse Temurin) |
| Docker Desktop | PostgreSQL·Meilisearch 실행용 (앱을 실행하면 자동으로 컨테이너가 뜸) |

Gradle과 Node.js(Tailwind 빌드용)는 Gradle이 알아서 내려받으므로 따로 설치하지 않아도 됩니다.

## 실행

```bash
# 1) 가짜 데이터 만들기 (PostgreSQL 컨테이너가 없으면 자동으로 띄움, 기존 데이터는 지우고 다시 생성)
./gradlew :seed:bootRun

# 2) 웹 앱 실행
./gradlew :api:bootRun
```

브라우저에서 **http://localhost:8090** 을 엽니다. (8080은 다른 로컬 프로젝트가 쓰고 있어 8090 사용)

### 테스트 계정

| 계정 | 역할 | 비밀번호 |
|---|---|---|
| `normal@example.com` | 일반 회원 (주문 이력 있음) | `yangpang1234!` |
| `plus@example.com` | 양팡플러스 회원 | `yangpang1234!` |
| `biz@example.com` | 비즈 회원 | `yangpang1234!` |
| `seller@example.com` | 판매자 (누리마켓, 셀러센터 `/seller`) | `yangpang1234!` |
| `admin@example.com` | 운영자 | `yangpang1234!` |

로그인 화면의 "테스트 계정으로 바로 로그인" 버튼으로도 들어갈 수 있습니다. 가짜 회원 100명(`user0001@example.com` ~)도 비밀번호가 같습니다.

### 둘러볼 곳

| 주소 | 내용 |
|---|---|
| `/` | 홈 (배너, 최근 본 상품, 많이 팔린 상품) |
| 검색창 | 자동완성·최근·인기 검색어. 오타(`청소가`)·동의어(`에프`, `햇반`)도 찾음 (Meilisearch) |
| `/category/1`, `/search?q=티셔츠` | 상품 목록 (정렬·필터, 모바일 무한 스크롤) |
| `/products/447` | 상품 상세 (옵션, 쿠폰 받기, 찜, 리뷰·문의) |
| `/coupons` | 쿠폰존 |
| `/cart` → 구매하기 | 장바구니(비회원도 가능) → 주문서(쿠폰 자동 적용·양팡캐시) → **모의 결제** → 주문 완료 |
| `/my` | 마이양팡: 주문목록·배송 진행, 취소·반품·교환, 리뷰, 찜·최근 본·자주 산, 쿠폰함, 양팡캐시, 배송지, 회원정보 |
| `/deals` | 오늘의 특가 (매일 07:00 교체, 한정 수량·남은 시간). 관리자 `/admin/deals`에서 직접 등록·즉시 교체 |
| `/ranking`, `/my/notifications` | 양팡 랭킹(관리자 `/admin/ranking`에서 가중치 조정), 알림함(헤더 종 아이콘) |
| `/events`, `/fresh`, `/brands`, `/refurb` | 기획전(전용 쿠폰), 양팡프레시(새벽배송), 브랜드관, 리퍼마켓(반품 검수 상품). 관리자 `/admin/exhibitions` |
| `/plus` → `/my/plus` | 양팡플러스 가입(첫 달 무료)·아낀 금액·모의 청구 내역·2단계 해지 |
| `/seller` | 셀러센터: 입점 신청, 새 상품 등록(카테고리 필수 속성), 기존 상품에 판매 추가, 위너 현황, 발주 확인·송장 등록 |
| `/products/6` | 판매자가 여럿인 상품 → "다른 판매자 보기" |
| `/signup` | 회원가입 (모의 인증, 가입 축하 쿠폰 지급) |
| `/admin` | 관리자: 상품·카테고리, 주문, 취소·반품·교환, 상품문의, 쿠폰, 전시(배너), 판매자 입점 승인·위너 재계산, **시뮬레이터** |

| `/search/image`, 검색창 🎤 | 이미지로 찾기(색 분포 비교), 음성 검색(크롬·엣지) |
| `/global`, `/subscriptions/new`, 주문서 "선물하기" | 양팡직구(통관부호), 정기배송(5% 할인·자동 주문), 선물하기(받는 분이 링크에서 배송지 입력) |
| 책상·의자 상품, "전통주·와인" | 양팡설치(주문서에서 방문 설치일), 19세 이상 상품(성인 인증 모의) |
| `/attendance`, `/my/invite`, `/my/emails` | 출석체크, 친구초대, 메일함(이메일 알림 모의) |
| `/cs`, `/find-account` | 고객센터(FAQ·1:1 문의), 아이디·비밀번호 찾기 |
| `/partners`, 검색 결과의 AD | 파트너스 링크·실적, 검색 광고 (판매자는 `/seller/ads`) |
| `/seller/stats`, `/seller/settlements` | 판매 통계, 주 단위 정산·판매자 점수 |
| `/admin/policies` | 운영 기준값 (배송비·반품 기간·수수료율·적립 금액 등 21개) |
| `/admin/sellers/{id}`, `/admin/settlements`, `/admin/ads` | 판매자 점수·제재, 정산 현황, 광고 현황 |

**상품 사진**: 카테고리별 실제 사진(Pixabay 무료 사진 474장, `api/src/main/resources/static/images/catalog/`, 출처는 같은 폴더 `credits.csv`). 다시 받으려면 `tools/catalog-photos/README.md`.

**AI 리뷰 요약·쇼핑 도우미 챗봇**: 앱을 띄우기 전에 `ANTHROPIC_API_KEY` 환경 변수를 설정하면 켜집니다(실제 요금 발생). 없으면 화면에서 숨겨집니다.

**배송을 보고 싶다면**: 주문한 뒤 `/admin/simulator`에서 "+1일"을 누르거나 배속을 60x로 올리면 배송 단계·구매확정·반품 회수가 진행됩니다. "실제 시각으로 되돌리기"로 원래대로.

## 개발 메모

- `domain` 모듈을 고친 뒤에는 앱을 **완전히 다시 시작**해야 합니다 (개발용 자동 재시작은 `api` 모듈만 다시 읽음). 템플릿·CSS·JS는 저장하면 바로 반영됩니다.
- Thymeleaf 화면 데이터 이름으로 `title`, `content`는 쓰지 않습니다 (공통 레이아웃 매개변수와 겹침 → `pageTitle` 사용).
- 목록을 반복하며 조각을 끼울 때 `th:each`와 `th:replace`를 같은 태그에 쓰지 않습니다 (`th:block`으로 감싸기).
- 앱을 여러 번 띄웠다가 제대로 끄지 않으면 예전 앱이 남아 시뮬레이터가 여러 개 돌고 PC가 느려집니다. 다시 띄우기 전에 이전 `bootRun`을 끄세요.
- Meilisearch가 꺼져 있어도 검색은 PostgreSQL로 동작합니다 (오타·동의어 기능만 빠짐). 색인은 앱 시작 때 전체, 이후 상품이 바뀔 때마다 자동 갱신.
- 모듈: `domain`(엔티티·로직·마이그레이션) / `simulator`(배송 진행 스케줄) / `api`(웹) / `seed`(가짜 데이터)

## 자주 쓰는 명령

| 명령 | 설명 |
|---|---|
| `./gradlew test` | 단위 테스트 (계산 규칙) |
| `python tests/e2e/run_all.py` | 화면·흐름 검사 19개 (앱이 떠 있어야 함, `tests/e2e/README.md`) |
| `./gradlew :seed:bootRun --args="--seed.random-seed=42"` | 다른 시드로 가짜 데이터 재생성 |
| `./gradlew :api:buildCss` | Tailwind CSS만 다시 빌드 |
| `docker compose up -d` / `docker compose down` | PostgreSQL 직접 켜기/끄기 |
| `docker compose down -v` | DB 데이터까지 삭제 |

## 모듈 구조

```
domain/   엔티티, 리포지토리, 핵심 로직, DB 마이그레이션(Flyway: domain/src/main/resources/db/migration)
api/      웹 앱 (Spring Boot + Thymeleaf + htmx + Tailwind), 화면: api/src/main/resources/templates
simulator/ 시뮬레이터 (배송 진행·정산·정기배송·판매자 점수 등을 시뮬레이션 시계로)
seed/     가짜 데이터 생성기 (Datafaker + 한국형 사전)
tests/e2e/ 화면·흐름 검사 스크립트 (Python)
tools/catalog-photos/ 상품 사진 받는 도구 (Pixabay)
```
