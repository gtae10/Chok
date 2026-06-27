import mysql.connector
from mysql.connector import pooling
from contextlib import contextmanager
import config

_pool = None

def get_pool():
    global _pool
    if _pool is None:
        _pool = pooling.MySQLConnectionPool(
            pool_name="chok_pool",
            pool_size=5,
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