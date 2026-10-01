# 클라우드 DB로 여러 컴퓨터에서 쓰기 (Docker + Aiven 무료 MySQL)

목표: 집 PC·노트북·다른 곳 어디서 열어도 **같은 DB(같은 태그 기록)** 를 보고, 실행 환경은 Docker로 맞춘다.

- Docker = 실행 환경(Java·Python·앱)을 같게 만든다. 데이터는 공유하지 않는다.
- 클라우드 DB = 데이터를 한 곳에 둔다. 두 컴퓨터의 앱 컨테이너가 모두 이 DB를 본다.
- 이 프로젝트 DB는 약 41MB(2026-10-01), 무료 1GB 안에 충분히 들어간다.

## 0. 규칙 (중요)

1. **자동 수집·분석(스케줄러)은 한 컴퓨터에서만 켠다.** 두 곳에서 동시에 돌면 수집이 겹치고 데드락도 늘어난다.
   기본값은 꺼짐(`CHOK_SCHEDULER_ENABLED=false`). 켜는 컴퓨터의 `.env`에만 `CHOK_SCHEDULER_ENABLED=true`를 적는다.
2. **로컬 DB로 되돌려 쓰지 않는다.** 태그 기록(`tag_snapshots`)은 분석을 돌린 날만 쌓이고 소급해서 채우지 않는다.
   DB가 둘로 갈라지면 검증용 기록이 끊긴다.
3. **`.env`는 git에 올리지 않는다** (`.gitignore`에 `.env`가 있다). 비밀번호·API 키는 이 파일에만 둔다.

## 1. Aiven에서 무료 MySQL 만들기 (직접)

1. https://aiven.io 에 가입 (무료 플랜은 카드 불필요).
2. 서비스 만들기 → **MySQL** → **Free plan** → 가능하면 한국과 가까운 리전(서울·도쿄 등)을 고른다.
3. 만들어지면 서비스 개요에서 **Host, Port, User, Password, Database name**을 확인한다.
   - Database name은 기본 `defaultdb`다. 그대로 써도 되고, 새로 `chok`를 만들어도 된다. 아래 `DB_NAME`을 같게 맞추면 된다.
4. 무료 플랜 한도: 1GB 디스크, 연결 최대 76개, **오랫동안 활동이 없으면 서비스가 꺼질 수 있다**(사전 알림 후 콘솔에서 다시 켠다).

## 2. 지금 DB 옮겨 넣기 (한 번만)

덤프는 이 PC에서 이미 만들어 두었다: `C:\Users\gamej\chok-dump\chok.sql` (저장소 밖, 2026-10-01 23:02 기준 19.8MB).
**옮기기 직전에 다시 뜨는 것을 권한다** (그 사이 쌓인 기록을 놓치지 않으려고).

```powershell
# 1) 덤프 (이 PC, 네이티브 MySQL)
$env:MYSQL_PWD = "<로컬 MySQL 비밀번호>"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" -uroot --single-transaction --no-tablespaces `
  --set-gtid-purged=OFF --default-character-set=utf8mb4 --routines --triggers chok > C:\Users\gamej\chok-dump\chok.sql

# 2) 클라우드 DB에 복원 (TLS 필수)
$env:MYSQL_PWD = "<Aiven 비밀번호>"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -h <Aiven host> -P <Aiven port> -u <Aiven user> `
  --ssl-mode=REQUIRED --default-character-set=utf8mb4 <DB_NAME> < C:\Users\gamej\chok-dump\chok.sql
```

- 덤프에는 `CREATE DATABASE`/`USE`가 없어서 어떤 이름의 DB에도 복원된다.
- 10개 테이블 모두 기본키가 있어 Aiven의 "기본키 필수" 설정에 걸리지 않는다.
- 복원 후 확인: `SELECT COUNT(*) FROM price_history;` (약 23만 행), `SELECT MAX(snap_date) FROM tag_snapshots;`

## 3. `.env` 만들기 (각 컴퓨터마다, 프로젝트 폴더에)

```
DB_HOST=<Aiven host>
DB_PORT=<Aiven port>
DB_NAME=<DB 이름>
DB_USER=<Aiven user>
DB_PASSWORD=<Aiven 비밀번호>
DB_SSL_MODE=REQUIRED

CHOK_OPENAI_API_KEY=<OpenAI 키>
# 스케줄러를 켜는 한 컴퓨터에만:
# CHOK_SCHEDULER_ENABLED=true
```

## 4. 실행

```powershell
docker compose up --build        # app 컨테이너만 뜬다 -> http://localhost:8080
```

- mysql 서비스는 `local-db` 프로필이라 뜨지 않는다. (혼자 실험용 로컬 DB가 필요하면 `docker compose --profile local-db up`, `.env`에는 `DB_*`를 적지 않는다.)
- 컨테이너 안의 python-collector는 컨테이너 환경변수의 `DB_*`를 그대로 읽는다 → 수집기도 같은 클라우드 DB를 본다.

## 5. 도커 없이 이 PC에서 `bootRun`으로 쓸 때

`application.properties`는 로컬 DB를 가리킨다. 클라우드를 쓰려면 같은 PowerShell 창에서 환경변수를 주고 실행한다.

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://<host>:<port>/<DB_NAME>?sslMode=REQUIRED&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$env:SPRING_DATASOURCE_USERNAME = "<user>"; $env:SPRING_DATASOURCE_PASSWORD = "<password>"
$env:DB_HOST = "<host>"; $env:DB_PORT = "<port>"; $env:DB_NAME = "<DB_NAME>"   # python-collector용
.\gradlew bootRun
```

(`DB_USER`/`DB_PASSWORD`는 `DataCollectionService`가 Spring 설정에서 파이썬에 넘겨 준다. `DB_HOST/PORT/NAME`은 넘기지 않으므로 위처럼 직접 준다.)

## 6. 확인 목록 (처음 한 번)

- [ ] 복원 후 행 수가 로컬과 맞는다 (`price_history`, `recommendations`, `tag_snapshots`).
- [ ] `docker compose up` 후 `http://localhost:8080`에 오늘 데이터가 보인다.
- [ ] 분석 한 번 실행했을 때 걸리는 시간을 로컬(약 1분)과 비교한다 — 리전이 멀면 느려질 수 있다.
- [ ] 두 번째 컴퓨터에서 같은 `.env`로 띄워 같은 화면이 보인다.
- [ ] 스케줄러를 켠 컴퓨터가 한 대뿐이다.

## 7. 되돌리기

로컬 MySQL(네이티브)은 그대로 남아 있다. 문제가 있으면 `.env`를 지우거나 `DB_*`를 비우고 기존처럼 실행하면 된다.
다만 클라우드에서 쌓은 기록은 로컬에 없으니, 되돌리기 전에 클라우드에서 덤프를 떠 둔다.
