# 상품 사진 받기 (Pixabay)

카테고리마다 상품 사진 3~6장을 [Pixabay](https://pixabay.com)에서 받아 `api/src/main/resources/static/images/catalog/`에 저장하는 도구예요.
Pixabay Content License라 무료로 쓸 수 있고 출처 표시 의무도 없지만, 확인할 수 있게 `credits.csv`에 사진 id·작가·주소를 남겨요.
앱은 상품 카테고리 이름으로 `manifest.csv`를 찾아 사진을 보여 줘요 (사진이 없는 카테고리는 기존 그림).

## 준비

- Python 3.10 이상, Pillow (`pip install pillow`)
- Pixabay API 키: 가입·로그인 후 https://pixabay.com/api/docs/ 에 보이는 키를 환경 변수 `PIXABAY_API_KEY`에
  (Windows는 `setx PIXABAY_API_KEY "키"` — 새 창부터 보이지만 스크립트는 레지스트리에서도 읽어요). 키는 저장소에 넣지 마세요

## 순서

```bash
# 1) 후보 12장씩 + 모아보기 이미지(sheet_00.jpg ~). 작업 폴더는 저장소 밖 아무 곳
python tools/catalog-photos/fetch_pixabay.py candidates ../photos-work

# 2) 모아보기를 보고 selection.csv에 카테고리별로 쓸 후보 번호를 적는다 (사람 얼굴·풍경·큰 상표는 빼기)
#    결과가 나쁜 카테고리는 categories.csv의 검색어를 고치고 그 slug만 다시 받기
python tools/catalog-photos/fetch_pixabay.py candidates ../photos-work tshirt backpack   # → resheet_00.jpg

# 3) 고른 사진만 600px 정사각형 JPG로 저장 + manifest.csv·credits.csv
python tools/catalog-photos/fetch_pixabay.py download ../photos-work
```

- 요청 한도(분당 100회)에 걸리면 1분 기다렸다 이어서 받고, 이미 받은 카테고리는 건너뛰어요.
- 후보 주소는 24시간만 유효해서 3)은 사진 id로 다시 조회해요. 1) 뒤 하루 안에 3)을 하면 편해요.
- 사진을 바꾼 뒤 앱을 다시 띄우고, 이미지 검색 색 분포를 다시 계산하세요 (관리자 화면 "다시 계산" 또는 가짜 데이터 다시 만들기).
- 파일: `categories.csv`(카테고리 → 폴더 이름·영어 검색어), `selection.csv`(고른 후보 번호), `fetch_pixabay.py`.
  "내복"처럼 알맞은 사진이 없으면 스크립트의 `SHARED`로 다른 카테고리 사진을 같이 써요.
