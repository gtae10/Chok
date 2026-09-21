import time
import mysql.connector
from mysql.connector import pooling
from contextlib import contextmanager
import config

_pool = None

DEADLOCK_ERRNO = 1213  # "Deadlock found when trying to get lock; try restarting transaction"


def retry_on_deadlock(func):
    """10개 스레드가 동시에 price_history/stocks에 벌크 insert를 하다 보니
    InnoDB 데드락이 종종 발생한다 - MySQL이 권장하는 대로 트랜잭션을 재시도한다."""
    def wrapper(*args, **kwargs):
        attempts = 3
        for attempt in range(attempts):
            try:
                return func(*args, **kwargs)
            except mysql.connector.errors.InternalError as e:
                if e.errno == DEADLOCK_ERRNO and attempt < attempts - 1:
                    time.sleep(0.3 * (attempt + 1))
                    continue
                raise
    return wrapper

def get_pool():
    global _pool
    if _pool is None:
        _pool = pooling.MySQLConnectionPool(
            pool_name="chok_pool",
            pool_size=10,  # collect.py의 ThreadPoolExecutor(max_workers=10)와 맞춤 - 낮으면 동시 insert 시 pool exhausted
            host=config.DB_HOST,
            port=config.DB_PORT,
            database=config.DB_NAME,
            user=config.DB_USER,
            password=config.DB_PASSWORD,
            charset="utf8mb4"
        )
    return _pool

@contextmanager
def get_conn():
    conn = get_pool().get_connection()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

@retry_on_deadlock
def upsert_stock(ticker, name, market, market_cap, base_date):
    with get_conn() as conn:
        cur = conn.cursor()
        cur.execute(
            """INSERT INTO stocks (ticker, name, market, market_cap, base_date)
               VALUES (%s, %s, %s, %s, %s)
               ON DUPLICATE KEY UPDATE
                 name=VALUES(name), market=VALUES(market),
                 market_cap=VALUES(market_cap), base_date=VALUES(base_date)""",
            (ticker, name, market, market_cap, base_date)
        )
        cur.close()

@retry_on_deadlock
def upsert_market_indicator_rows(rows):
    if not rows:
        return
    with get_conn() as conn:
        cur = conn.cursor()
        cur.executemany(
            """INSERT INTO market_indicators (trade_date, kospi_close, kosdaq_close, usd_krw_close)
               VALUES (%(date)s, %(kospi_close)s, %(kosdaq_close)s, %(usd_krw_close)s)
               ON DUPLICATE KEY UPDATE
                 kospi_close=VALUES(kospi_close), kosdaq_close=VALUES(kosdaq_close),
                 usd_krw_close=VALUES(usd_krw_close)""",
            rows
        )
        cur.close()

@retry_on_deadlock
def insert_price_rows(rows):
    if not rows:
        return
    with get_conn() as conn:
        cur = conn.cursor()
        cur.executemany(
            """INSERT INTO price_history
               (ticker, trade_date, open_price, high_price, low_price, close_price, volume)
               VALUES (%(ticker)s, %(date)s, %(open)s, %(high)s, %(low)s, %(close)s, %(volume)s)
               ON DUPLICATE KEY UPDATE
                 open_price=VALUES(open_price), high_price=VALUES(high_price),
                 low_price=VALUES(low_price), close_price=VALUES(close_price),
                 volume=VALUES(volume)""",
            rows
        )
        cur.close()


def _test_retry_on_deadlock():
    calls = {"n": 0}

    @retry_on_deadlock
    def flaky():
        calls["n"] += 1
        if calls["n"] < 3:
            raise mysql.connector.errors.InternalError(errno=DEADLOCK_ERRNO, msg="deadlock")
        return "ok"

    assert flaky() == "ok"
    assert calls["n"] == 3

    @retry_on_deadlock
    def always_fails():
        raise mysql.connector.errors.InternalError(errno=DEADLOCK_ERRNO, msg="deadlock")

    try:
        always_fails()
        assert False, "should have raised after exhausting retries"
    except mysql.connector.errors.InternalError:
        pass

    @retry_on_deadlock
    def non_deadlock_error():
        raise mysql.connector.errors.InternalError(errno=1045, msg="access denied")

    try:
        non_deadlock_error()
        assert False, "non-deadlock errors must not be retried away"
    except mysql.connector.errors.InternalError as e:
        assert e.errno == 1045


if __name__ == "__main__":
    _test_retry_on_deadlock()
    print("db.py self-check passed")