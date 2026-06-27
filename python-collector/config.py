import os

DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_PORT = int(os.environ.get("DB_PORT", "3306"))
DB_NAME = os.environ.get("DB_NAME", "chok")
DB_USER = os.environ.get("DB_USER", "root")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "")

TOP_N_STOCKS = int(os.environ.get("TOP_N_STOCKS", "100"))
MARKETS = ["KOSPI", "KOSDAQ"]
PRICE_HISTORY_DAYS = 120
