import datetime
import time
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
import FinanceDataReader as fdr
import pandas as pd
import config
from db import upsert_stock, insert_price_rows, upsert_market_indicator_rows

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)


def get_top_n_stocks(n=None):
    n = n or config.TOP_N_STOCKS
    today = datetime.datetime.now().strftime("%Y%m%d")

    all_stocks = []
    for market in config.MARKETS:
        try:
            df = fdr.StockListing(market)
            if df is None or df.empty:
                logger.warning(f"{market} 종목 목록 없음")
                continue

            for _, row in df.iterrows():
                ticker = str(row.get("Code", "")).strip()
                name = str(row.get("Name", ticker)).strip()
                market_cap = int(row.get("Marcap", 0)) if pd.notna(row.get("Marcap", 0)) else 0
                if not ticker:
                    continue
                all_stocks.append({
                    "ticker": ticker,
                    "name": name,
                    "market": market,
                    "market_cap": market_cap,
                })
        except Exception as e:
            logger.error(f"{market} 종목 목록 조회 실패: {e}")
            continue

    all_stocks.sort(key=lambda x: x["market_cap"], reverse=True)
    top_stocks = all_stocks[:n]

    for s in top_stocks:
        upsert_stock(s["ticker"], s["name"], s["market"], s["market_cap"], today)

    logger.info(f"시가총액 상위 {len(top_stocks)}개 종목 확보 완료")
    return top_stocks


def fetch_price_history(stock):
    """단일 종목 가격 수집 (ThreadPoolExecutor에서 호출)"""
    ticker = stock["ticker"]
    name = stock["name"]
    days = config.PRICE_HISTORY_DAYS
    end_date = datetime.datetime.now().strftime("%Y-%m-%d")
    start_date = (datetime.datetime.now() - datetime.timedelta(days=days)).strftime("%Y-%m-%d")

    try:
        df = fdr.DataReader(ticker, start_date, end_date)
    except Exception as e:
        logger.error(f"{ticker} 가격 데이터 조회 실패: {e}")
        return ticker, False

    if df is None or df.empty:
        logger.warning(f"{ticker}: 가격 데이터 없음")
        return ticker, False

    rows = []
    for date, row in df.iterrows():
        rows.append({
            "ticker": ticker,
            "date": date.strftime("%Y-%m-%d"),
            "open": int(row.get("Open", 0)),
            "high": int(row.get("High", 0)),
            "low": int(row.get("Low", 0)),
            "close": int(row.get("Close", 0)),
            "volume": int(row.get("Volume", 0)),
        })

    if rows:
        insert_price_rows(rows)

    return ticker, True


# 종목별이 아닌, 그날 전체 시장에 공통으로 적용되는 거시 지표.
# FDR로 안정적으로 얻을 수 있는 것만 채택 (PER/PBR/배당수익률은 FDR 미제공, pykrx는
# KRX_ID/KRX_PW 로그인 필요, 네이버 시세 페이지는 Next.js 리뉴얼로 정적 스크래핑 불가해 제외).
MARKET_INDEX_SYMBOLS = {
    "kospi_close": "KS11",
    "kosdaq_close": "KQ11",
    "usd_krw_close": "USD/KRW",
}


def fetch_market_indicators():
    """KOSPI/KOSDAQ 지수, 원/달러 환율의 일별 종가를 수집해 market_indicators에 저장한다."""
    days = config.PRICE_HISTORY_DAYS
    end_date = datetime.datetime.now().strftime("%Y-%m-%d")
    start_date = (datetime.datetime.now() - datetime.timedelta(days=days)).strftime("%Y-%m-%d")

    series = {}
    for column, symbol in MARKET_INDEX_SYMBOLS.items():
        try:
            df = fdr.DataReader(symbol, start_date, end_date)
            series[column] = df["Close"]
        except Exception as e:
            logger.error(f"거시 지표 조회 실패 ({symbol}): {e}")

    if not series:
        logger.warning("거시 지표를 하나도 가져오지 못해 저장을 건너뜁니다.")
        return 0

    merged = pd.DataFrame(series)
    rows = [
        {"date": date.strftime("%Y-%m-%d"), **{
            col: (float(row[col]) if pd.notna(row.get(col)) else None) for col in series
        }}
        for date, row in merged.iterrows()
    ]

    upsert_market_indicator_rows(rows)
    logger.info(f"거시 지표 저장 완료: {len(rows)}일치 ({', '.join(series.keys())})")
    return len(rows)


def run():
    logger.info("=== 시세 수집 시작 ===")
    start = datetime.datetime.now()

    try:
        top_stocks = get_top_n_stocks()
        processed = 0
        total = len(top_stocks)

        # 병렬로 가격 데이터 수집 (최대 10개 동시)
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = {executor.submit(fetch_price_history, s): s for s in top_stocks}
            for i, future in enumerate(as_completed(futures), 1):
                ticker, success = future.result()
                if success:
                    processed += 1
                logger.info(f"[{i}/{total}] {ticker} 완료")

        try:
            fetch_market_indicators()
        except Exception as e:
            logger.error(f"거시 지표 수집 실패 (종목 시세는 정상 수집됨): {e}")

        elapsed = (datetime.datetime.now() - start).seconds
        logger.info(f"=== 시세 수집 완료: {processed}/{total} 종목 ({elapsed}초) ===")
        return processed

    except Exception as e:
        logger.exception("시세 수집 중 오류 발생")
        raise


if __name__ == "__main__":
    run()