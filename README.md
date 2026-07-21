최종 README 전체 텍스트 그대로 줄게 — 복사해서 Chok/README.md에 붙여넣으면 돼.

markdown
# 촉 (Chok) — AI 기반 한국 주식 분석 대시보드

> "촉이 온다" — 기술적 지표 + 거래량 + AI 뉴스 감성 분석을 결합한 한국 주식 스크리닝 도구

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.0-green)
![Python](https://img.shields.io/badge/Python-3.x-blue)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)
![scikit--learn](https://img.shields.io/badge/scikit--learn-ML-yellow)

---

## 프로젝트 소개

촉(Chok)은 KOSPI/KOSDAQ 시가총액 상위 100개 종목을 대상으로
**기술적 분석(거래량 포함) + Claude AI 뉴스 감성 분석**을 결합해
종목별 추천 점수와 상승확률을 산출하는 웹 대시보드다.

단순히 지표를 계산해서 보여주는 데서 그치지 않고, **"상승확률"이 실제로 통계적
근거가 있는 값인지 로지스틱 회귀 + walk-forward 검증으로 직접 확인하는 과정**까지
프로젝트에 포함되어 있다 (자세한 내용은 [상승확률 예측 — 검증 과정](#상승확률-예측--검증-과정) 참고).

---

## 주요 기능

- 시가총액 상위 100개 종목 자동 수집 (FinanceDataReader)
- 기술적 지표 계산: 이동평균(MA5/20/60), RSI, MACD, 볼린저밴드, **거래량비율 + OBV**
- 네이버 금융 뉴스 크롤링 + Claude API 감성 분석
- 기술점수 + 감성점수 가중 결합으로 최종 추천 등급 산출 (STRONG_BUY ~ STRONG_SELL)
- **상승확률**: 로지스틱 회귀 모델(학습됐을 때) 또는 지표 종합 추정치(휴리스틱) — 어느 쪽
  근거인지, 학습 모델이면 몇 영업일 기준 예측인지("N영업일 기준") 화면에 항상 표시
- 대시보드 종목명/티커 검색 + 등급 필터 + **컬럼 헤더 클릭 정렬**
- 분석 실행은 **비동기 + 병렬 처리**로 진행률을 실시간 폴링하며 표시 (브라우저 타임아웃 없음)
- 평일 정규장 마감 후 **자동 수집+분석 스케줄러** (선택적 활성화)
- 종목 상세 페이지: 가격 차트(호버 툴팁 + 기간 선택) + 추천 점수 시계열 차트 + 뉴스 목록
- 버튼 클릭으로 수동 실행도 가능 (API 비용 통제)

---

## 기술 스택


|
 분류 
|
 기술 
|
|
---
|
---
|
|
 Backend 
|
 Java 17, Spring Boot 3.4.0, Spring Data JPA 
|
|
 Frontend 
|
 Thymeleaf, HTML/CSS, Vanilla JavaScript (SVG 차트, 프레임워크 없음) 
|
|
 Database 
|
 MySQL 8.0 
|
|
 데이터 수집 
|
 Python, FinanceDataReader 
|
|
 모델링 
|
 Python, scikit-learn (로지스틱 회귀) 
|
|
 AI 분석 
|
 Claude API (claude-sonnet-4-6) 
|
|
 Build 
|
 Gradle 
|

---

## 시스템 아키텍처

[브라우저]
│
▼
[Spring Boot 백엔드]
│
├── ① 시세 수집 버튼 → Python 수집기 (ProcessBuilder)
│ └── FinanceDataReader → MySQL 적재
│
├── ② 분석 실행 버튼 → 즉시 응답 + 백그라운드 실행 (AnalysisStatus 폴링)
│ └── 스레드풀 병렬 처리 (종목 단위 동시 분석)
│ ├── 기술적 지표 계산 (MA/RSI/MACD/볼린저밴드/거래량/OBV)
│ ├── 상승확률 계산 (학습모델 있으면 사용, 없으면 휴리스틱)
│ ├── 네이버 금융 뉴스 크롤링 (jsoup)
│ └── Claude API 감성 분석
│ └── 최종 추천 점수 → MySQL 저장
│
└── 평일 18:30 자동 스케줄러 (선택) → 위 ①②를 자동 실행

[python-collector/train_model.py] (별도 실행)
└── MySQL 가격이력 → 특징 계산 → 로지스틱 회귀 학습
→ walk-forward 검증 → model/rise_model.json 저장
→ Spring이 재시작 없이 자동으로 다시 읽어들임


---

## 점수 산출 방식

최종 점수 = 기술점수 × 0.65 + 감성점수 × 0.35


**기술점수 (0~100)**:
- 이동평균 정배열/역배열 (30%)
- RSI 과매수/과매도 (20%)
- MACD 시그널 상/하회 (20%)
- 볼린저밴드 위치 (15%)
- **거래량비율 + OBV 추세 (15%)** — 상승/하락을 거래량이 얼마나 "확인"해주는지 반영

**감성점수 (0~100)**:
- Claude API로 뉴스 헤드라인 분석
- POSITIVE(+) / NEUTRAL(0) / NEGATIVE(-) 분류

**상승확률 (0~100%)**:
- 학습된 로지스틱 회귀 모델이 있으면 그걸로 계산 (화면에 "학습" 뱃지)
- 없으면 위 지표 점수들을 조합한 추정치로 대체 (화면에 "추정" 뱃지)
- 어느 쪽이든 "통계 검증된 예측이 아닌 참고용 지표"임을 화면에 항상 명시

---

## 상승확률 예측 — 검증 과정

상승확률을 그냥 지표 조합으로 보여줄 수도 있었지만, **실제로 예측력이 있는지 검증하는
과정 자체**를 만들었다. 이 프로젝트에서 가장 공들인 부분이자, 결과적으로 "화려하지 않지만
정직한 결론"에 도달한 부분이다.

### 검증 단계

1. **로지스틱 회귀 학습** — 종가/거래량 기반 7개 특징(이동평균 괴리, RSI, MACD 히스토그램,
   볼린저 %B, 로그거래량비율)으로 N영업일 뒤 상승 여부를 학습
2. **Ablation study** — 거래량 특징 포함/제외 모델의 홀드아웃 AUC를 비교해 거래량 지표가
   실제로 기여하는지 확인 → 유의미한 차이 없음
3. **Walk-forward 검증** — 단일 train/test 분할이 아니라 시간순 5개 구간으로 나눠 반복
   검증. 첫 단일 split에서 "거래량 포함이 더 낫다"고 보였던 차이가, 폴드 평균으로 보니
   표준편차 안에 묻히는 우연이었음을 확인
4. **예측 기간 스윕 중 데이터 누수 발견 및 수정** — 3~100영업일 기간을 스윕하던 중 기간이
   길수록 AUC가 비정상적으로 계속 상승하는 패턴을 발견. 원인은 폴드 경계 부근 학습 샘플의
   라벨이 검증 구간의 미래 가격을 참조하고 있던 것(overlapping-label leakage) — 각 샘플이
   실제로 참조하는 미래 날짜(`target_date`)를 추적해 검증 구간을 침범하는 학습 샘플을
   제거하는 **purge 로직**을 추가해 수정
5. **평균 → 중앙값 라벨 전환** — purge 이후에도 기간이 길어질수록 베이스라인 정확도가
   계속 올라가는 패턴이 남아있었음. 원인은 "시장대비 상대수익률" 라벨을 **평균**으로 계산해서
   생긴 통계적 쏠림(주식 수익률은 오른쪽 꼬리가 긴 분포라, 소수 대박 종목이 평균을 끌어올려
   기간이 길수록 "평균 이상"이 점점 소수가 됨) — **중앙값** 기준으로 바꾸자 베이스라인이
   모든 기간에서 0.50~0.51로 평평해짐 (합성 데이터로도 이 효과를 재현/검증함)
6. **실전 백테스트 + 무작위 베이스라인 비교** — 매달 기술점수 상위 5종목을 매수해 30일
   보유하는 시뮬레이션을 v1(거래량 제외)/v2(거래량 포함)/무작위 선택(50회 반복 평균) 셋을
   같은 기간에 비교

### 최종 결론

| 검증 | 결과 |
|---|---|
| 단기(3~20영업일) 예측 | AUC 0.46~0.49 — 랜덤 수준, 신호 없음 |
| 중장기(50~60영업일) 예측 | AUC 0.55~0.57 — 약한 신호 있으나 폴드 수가 적어 아직 불확실 |
| 거래량 지표 단독 기여도 | ablation/backtest 모두에서 유의미한 개선 확인 안 됨 |
| 상위 5종목 매수 전략 (v1/v2) | **무작위 선택과 통계적으로 구분되지 않음** (승률·평균수익률 모두 무작위 분포 범위 안) |

지금 있는 지표/데이터 조합으로는, 적어도 단기~중기 구간에서 시장을 이기는 뚜렷한 우위를
찾지 못했다. 그래서 상승확률은 기본적으로 휴리스틱(지표 종합 추정치)으로 서빙되고, 학습
모델이 존재할 때만("학습" 뱃지 + 예측기간 표시) 대체 사용된다. 이 결론 자체를 화면과
README에 숨기지 않고 그대로 드러내는 걸 원칙으로 삼았다 — "안 되는 것도 데이터로
검증해서 확인했다"는 과정이 이 프로젝트의 핵심이다.

python-collector/train_model.py 상단의 다음 값을 직접 수정해 재실험 가능:
LABEL_MODE "relative"(중앙값 기준, 기본값) | "absolute"
FORWARD_DAYS 실제 배포 모델이 쓸 예측 기간(영업일)
SWEEP_HORIZONS 비교해볼 기간 후보 목록
python-collector/backtest.py 로 v1/v2/무작위 베이스라인 수익률 비교 가능


---

## 실행 방법

### 1. 사전 요구사항

- Java 17+
- Python 3.10+
- MySQL 8.0

### 2. DB 준비

```sql
CREATE DATABASE chok;
```
테이블은 `ddl-auto=update`로 애플리케이션 최초 실행 시 JPA 엔티티 기준으로 자동 생성된다
(별도 스키마 스크립트 실행 불필요).

### 3. 설정 파일

`src/main/resources/application.properties.example`을 복사해서
`application.properties`로 만들고 실제 값(DB 비밀번호, Claude API 키, 로컬 파이썬 경로 등)을
채운다. 이 파일은 `.gitignore`에 등록되어 있어 git에 커밋되지 않는다.

### 4. Python 수집기 설정

```powershell
cd python-collector
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

### 5. Spring Boot 실행

```powershell
.\gradlew bootRun
```

### 6. 접속

http://localhost:8080

**① 시세 수집 → ② 분석 실행** 버튼 순서로 클릭.
`chok.scheduler.enabled=true`로 설정하면 평일 18:30에 자동 실행된다.

---

## 프로젝트 구조

Chok/
├── src/main/java/com/project/Chok/
│ ├── config/ AppProperties (chok.* 설정 바인딩)
│ ├── controller/ REST API, 대시보드 라우팅
│ ├── domain/ Stock, PriceHistory, TechnicalScore, NewsSentiment, Recommendation
│ ├── dto/ 데이터 전달 객체
│ ├── repository/ Spring Data JPA
│ └── service/
│ ├── TechnicalAnalysisService 기술적 지표 + 거래량 지표 계산
│ ├── RiseProbabilityService 학습모델 로드 및 상승확률 계산
│ ├── SentimentAnalysisService Claude API 뉴스 감성 분석
│ ├── NewsCollectorService 네이버 금융 뉴스 크롤링
│ ├── DataCollectionService Python 수집기 실행 (ProcessBuilder)
│ ├── ModelTrainingService Python 학습 스크립트 실행
│ ├── RecommendationService 종합 점수/추천등급 산출 (병렬 처리)
│ ├── AnalysisStatus 분석 진행상태 (비동기 폴링용)
│ └── AnalysisScheduler 자동 수집+분석 / 모델 재학습 스케줄
├── src/main/resources/
│ ├── application.properties (git 미포함 — 실제 값)
│ ├── application.properties.example (git 포함 — 템플릿)
│ ├── templates/ dashboard.html, stock-detail.html
│ └── static/ css, js
└── python-collector/
├── collect.py 시세 수집 (FinanceDataReader, 종목당 1년치 백필)
├── train_model.py 상승확률 로지스틱 회귀 학습 (walk-forward 검증 포함)
├── backtest.py v1(거래량 제외) vs v2(거래량 포함) vs 무작위 선택 백테스트 비교
├── config.py / db.py
└── requirements.txt


---

## 성능 최적화

- Python 수집기: 종목당 시세 수집을 짧은 시간 내 완료하도록 구성 (100종목 기준 수 초대)
- Spring 분석: 순차 for-loop 대신 스레드풀(`ExecutorService`, 기본 동시 5개)로 종목별 분석을
  병렬 처리해 전체 소요 시간을 단축
- `② 분석 실행` API는 동기 대기 대신 즉시 응답 + 백그라운드 실행 구조로 전환해, 오래 걸리는
  작업으로 인한 브라우저 타임아웃 문제를 제거

---

## 알려진 제한사항 / TODO

- 뉴스 감성분석은 Claude API 크레딧이 있어야 실제로 동작 (`chok.anthropic.api-key`)
- 상승확률/기술점수 기반 종목 선정은 백테스트 결과 무작위 선택 대비 뚜렷한 우위를
  확인하지 못함 (자세한 내용은 [상승확률 예측 — 검증 과정](#상승확률-예측--검증-과정) 참고).
  현재는 이 결론을 화면에 정직하게 노출하는 방식으로 운영 중
- "1일" 기간 버튼은 일별 시세 특성상 안내 문구만 표시 (의도된 동작)
- [ ] 모멘텀 팩터(60~90일 누적수익률) 등 특징 추가 실험
- [ ] 백테스트 리밸런싱 주기를 월간 → 주간으로 바꿔 표본 수 확보
- [ ] 미국 주식(S&P 500) 분석 추가
- [ ] Python 수집기 → FastAPI 서버로 마이그레이션
- [ ] 추천 히스토리 차트 시각화 고도화

---

## 주의사항

> 본 프로젝트의 추천 점수와 등급은 참고용 보조 지표다.
> 수익을 보장하지 않으며, 모든 투자 판단과 책임은 사용자 본인에게 있다.
