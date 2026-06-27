# 촉 (Chok) - AI 기반 한국 주식 분석 대시보드

> "촉이 온다" — 기술적 지표와 AI 뉴스 감성 분석을 결합한 한국 주식 스크리닝 도구

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.0-green)
![Python](https://img.shields.io/badge/Python-3.x-blue)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)

---

## 프로젝트 소개

촉(Chok)은 KOSPI/KOSDAQ 시가총액 상위 100개 종목을 대상으로  
**기술적 분석(65%)** 과 **Claude AI 뉴스 감성 분석(35%)** 을 결합해  
종목별 추천 점수를 산출하는 웹 대시보드입니다.

---

## 주요 기능

- 시가총액 상위 100개 종목 자동 수집 (FinanceDataReader)
- 기술적 지표 계산: 이동평균(MA5/20/60), RSI, MACD, 볼린저밴드
- 네이버 금융 뉴스 크롤링 + Claude API 감성 분석
- 기술점수(65%) + 감성점수(35%) 가중 결합으로 최종 추천 등급 산출
- 추천 등급: STRONG_BUY / BUY / HOLD / SELL / STRONG_SELL
- 버튼 클릭으로 수동 실행 (비용 통제)

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3.4.0, Spring Data JPA |
| Frontend | Thymeleaf, HTML/CSS, JavaScript (SVG 차트) |
| Database | MySQL 8.0 |
| Data 수집 | Python, FinanceDataReader |
| AI 분석 | Claude API (claude-sonnet-4-6) |
| Build | Gradle |

---

## 시스템 아키텍처
[브라우저]

│

▼

[Spring Boot 백엔드]

├── ① 시세 수집 버튼 → Python 수집기 (ProcessBuilder)

│       └── FinanceDataReader → MySQL 적재

│

└── ② 분석 실행 버튼 → 병렬 분석 (ThreadPoolExecutor)

├── 기술적 지표 계산 (MA/RSI/MACD/볼린저밴드)

├── 네이버 금융 뉴스 크롤링 (jsoup)

└── Claude API 감성 분석

└── 최종 추천 점수 → MySQL 저장

---

## 점수 산출 방식
최종 점수 = 기술점수 × 0.65 + 감성점수 × 0.35
기술점수 (0~100):

이동평균 정배열/역배열 (35%)
RSI 과매수/과매도 (25%)
MACD 시그널 상/하회 (25%)
볼린저밴드 위치 (15%)

감성점수 (0~100):

Claude API로 뉴스 헤드라인 분석
POSITIVE(+) / NEUTRAL(0) / NEGATIVE(-) 분류


---

## 실행 방법

### 1. 사전 요구사항

- Java 17+
- Python 3.10+
- MySQL 8.0

### 2. DB 설정

```bash
mysql -u root -p < src/main/resources/db/schema.sql
```

### 3. 환경변수 설정

```bash
cp .env.example .env
# .env 파일에 실제 값 입력
```

### 4. Python 수집기 설정

```bash
cd python-collector
python -m venv .venv
.venv\Scripts\activate  # Windows
pip install -r requirements.txt
```

### 5. Spring Boot 실행

```bash
./gradlew bootRun
```

### 6. 접속
http://localhost:8080

① 시세 수집 → ② 분석 실행 버튼 순서로 클릭

---

## 프로젝트 구조
Chok/

├── src/main/java/com/project/Chok/

│   ├── config/          # 설정 클래스

│   ├── controller/      # REST API, 대시보드 라우팅

│   ├── domain/          # JPA 엔티티

│   ├── dto/             # 데이터 전달 객체

│   ├── repository/      # Spring Data JPA

│   └── service/         # 핵심 비즈니스 로직

├── src/main/resources/

│   ├── templates/       # Thymeleaf 템플릿

│   ├── static/          # CSS, JS

│   └── db/schema.sql    # DB 스키마

└── python-collector/    # Python 시세 수집기

├── collect.py

├── config.py

├── db.py

└── requirements.txt

---

## 성능 최적화

- Python 수집기: `ThreadPoolExecutor`로 병렬 수집 (100종목 기준 45초 → 4초)
- Spring 분석: `CompletableFuture`로 병렬 분석 처리

---

## 주의사항

> 본 프로젝트의 추천 점수와 등급은 참고용 보조 지표입니다.  
> 수익을 보장하지 않으며, 모든 투자 판단과 책임은 사용자 본인에게 있습니다.

---

## 향후 계획

- [ ] 미국 주식(S&P 500) 분석 추가
- [ ] Python 수집기 → FastAPI 서버로 마이그레이션
- [ ] 추천 히스토리 차트 시각화 고도화
