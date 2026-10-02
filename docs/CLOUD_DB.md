# 클라우드 MySQL(Aiven)을 PC 간 이동용 보관함으로 쓰기

운영 DB는 **각 PC의 로컬 MySQL**이다. Aiven 무료 MySQL은 실행 중인 앱이 붙는 DB가 아니라,
덤프를 올려 두었다가 **다른 PC의 MySQL로 옮겨 받는 중간다리**로만 쓴다.

## 왜 앱을 Aiven에 직접 붙이지 않나

2026-10-02에 앱을 Aiven에 직접 붙여 봤다. 무료 플랜은 대륙(Asia Pacific)까지만 고를 수 있고 구체적 리전은 못 고른다.
왕복이 약 140ms(쿼리 1건 약 0.33초)라서, 종목마다 DB를 여러 번 왕복하는 파이썬 수집기가 120초 제한을 넘겼다.
분석은 약 42초로 문제없었다. 느린 쪽은 시세 수집이다.

## 규칙

1. **자동 수집·분석(스케줄러)은 한 컴퓨터에서만 켠다.**
2. **Aiven의 데이터는 옮기는 순간의 사본이다.** 받은 PC에서 그대로 이어서 쓰고, Aiven에는 다시 올릴 때만 덮어쓴다.
3. **태그 기록(`tag_snapshots`)은 분석을 돌린 날만 쌓이고 소급해서 채우지 않는다.** 두 PC에서 따로 돌리면 기록이 갈라지니 한 PC에서만 돌린다.
4. **`.env`와 비밀번호는 git에 올리지 않는다.**

## 1. Aiven 무료 MySQL 만들기 (한 번만)

1. https://aiven.io 가입 → 서비스 만들기 → **MySQL** → **Free plan**.
2. 서비스 개요에서 **Host, Port, User, Password, Database name**(기본 `defaultdb`)을 확인한다.
3. 무료 플랜 한도: 1GB, **오랫동안 활동이 없으면 서비스가 꺼질 수 있다**(콘솔에서 다시 켠다).

## 2. 이 PC의 DB를 Aiven에 올리기

```powershell
# 1) 덤프 (네이티브 MySQL)
$env:MYSQL_PWD = "<로컬 MySQL 비밀번호>"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" -uroot --single-transaction --no-tablespaces `
  --set-gtid-purged=OFF --default-character-set=utf8mb4 --routines --triggers chok > C:\Users\gamej\chok-dump\chok.sql

# 2) Aiven에 복원 (TLS 필수). 이미 데이터가 있으면 먼저 테이블을 비우거나 DB를 새로 만든다.
$env:MYSQL_PWD = "<Aiven 비밀번호>"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -h <Aiven host> -P <Aiven port> -u <Aiven user> `
  --ssl-mode=REQUIRED --default-character-set=utf8mb4 <DB_NAME> < C:\Users\gamej\chok-dump\chok.sql
```

- 덤프에는 `CREATE DATABASE`/`USE`가 없어 어떤 이름의 DB에도 복원된다.
- 복원 후 확인: `SELECT COUNT(*) FROM price_history;`(약 23만 행), `SELECT MAX(snap_date) FROM tag_snapshots;`

## 3. 다른 PC에서 받기

```powershell
# Aiven에서 덤프
$env:MYSQL_PWD = "<Aiven 비밀번호>"
& "<mysqldump.exe 경로>" -h <Aiven host> -P <Aiven port> -u <Aiven user> --ssl-mode=REQUIRED `
  --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 <DB_NAME> > chok.sql

# 그 PC의 로컬 MySQL에 복원 (DB가 없으면 먼저 CREATE DATABASE chok CHARACTER SET utf8mb4)
$env:MYSQL_PWD = "<그 PC 로컬 MySQL 비밀번호>"
& "<mysql.exe 경로>" -uroot --default-character-set=utf8mb4 chok < chok.sql
```

복원 후 행 수를 원본 PC와 비교한다. 이후 그 PC에서 평소처럼 `application.properties`의 로컬 DB로 실행한다.

## 4. 이전 기록 (2026-10-02)

- 로컬 → Aiven 덤프·복원 완료. 행 수 일치: `price_history` 232,852 / `recommendations` 2,754 / `tag_snapshots` 231.
- 앱을 Aiven에 직접 붙여 실행해 본 결과, 분석은 42초로 문제없었지만 시세 수집은 지연 때문에 120초를 넘겨서 중간다리 용도로 결정.
- `docker-compose.yml`의 `DB_*` 파라미터화는 남겨 두었다. 앱을 Aiven에 붙여 쓰려면 `.env`에 `DB_*`를 채우고 수집 타임아웃을 늘려야 한다.
