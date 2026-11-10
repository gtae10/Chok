import datetime
import os
import time
from zoneinfo import ZoneInfo
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
import FinanceDataReader as fdr
import numpy as np
import pandas as pd
import config
from db import upsert_stock, insert_price_rows, upsert_market_indicator_rows, update_issuance_rows, upsert_short_rows, max_short_trade_date

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


KST = ZoneInfo("Asia/Seoul")
MARKET_CLOSE_CONFIRMED = datetime.time(16, 0)  # KRX 15:30 마감 + 여유


def confirmed_until(now=None) -> pd.Timestamp:
    """이 날짜 '미만'의 일봉만 확정 종가다. 장 마감 전에 수집하면 오늘 행이 장중 가격으로 저장되고,
    그걸로 분석·성과 진입가가 기록된다(2026-09-15 코웨이 진입가 102,000원 vs 확정 종가 100,900원).
    Docker 컨테이너가 UTC여도 한국 시각으로 판단한다."""
    now = now or datetime.datetime.now(KST)
    cutoff = now.date() + datetime.timedelta(days=1) if now.time() >= MARKET_CLOSE_CONFIRMED else now.date()
    return pd.Timestamp(cutoff)


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

    # 결측 한 칸이 int(NaN) 예외로 번져 100종목 수집 전체가 실패하지 않도록 - 종가 없는 날은 버리고
    # 나머지 결측은 0 (거래정지일 시가/고가/저가가 원래 0으로 들어오는 것과 같은 취급)
    df = df.dropna(subset=["Close"]).fillna(0)
    df = df[df.index < confirmed_until()]

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
    merged = merged[merged.index < confirmed_until()]  # 가격 일봉과 같은 확정 종가 규칙(D6) - 장중 지수 저장 방지
    rows = [
        {"date": date.strftime("%Y-%m-%d"), **{
            # 한 지수만 실패해도 upsert의 %(컬럼)s 자리가 비지 않게 전체 키를 채운다(없으면 None) - 그날 다른 지표까지 통째 누락 방지
            col: (float(row[col]) if col in series and pd.notna(row.get(col)) else None) for col in MARKET_INDEX_SYMBOLS
        }}
        for date, row in merged.iterrows()
    ]

    upsert_market_indicator_rows(rows)
    logger.info(f"거시 지표 저장 완료: {len(rows)}일치 ({', '.join(series.keys())})")
    return len(rows)


ISSUANCE_LOOKBACK = 252  # 영업일, 약 1년


def compute_issuance(marcap: pd.DataFrame, tickers) -> dict:
    """순발행 = log(N_t / N_t-252), N = 시가총액 / 수정종가지수. 태그 ISSUANCE_TOP20용.
    experiment_size_turnover_issuance.py의 issuance252와 같은 정의 - 상장주식수를 그대로 쓰면
    액면분할 때 수십 배로 튀므로, 분할에 연속인 수정종가(등락률 누적곱)로 나눈다.
    이력이 252영업일보다 짧은 종목은 빠진다(신규상장 등)."""
    m = marcap[marcap["Code"].isin(tickers)].sort_values(["Code", "Date"])
    out = {}
    for code, g in m.groupby("Code"):
        if len(g) <= ISSUANCE_LOOKBACK:
            continue
        cp = (1 + g["ChangesRatio"].fillna(0) / 100).cumprod()
        n = (g["Marcap"] / cp).to_numpy()
        if n[-1] > 0 and n[-1 - ISSUANCE_LOOKBACK] > 0:
            out[code] = float(np.log(n[-1] / n[-1 - ISSUANCE_LOOKBACK]))
    return out


def update_issuance(tickers):
    from train_model import ensure_marcap_files, MARCAP_DIR  # 학습용 marcap 캐시를 같이 쓴다
    year = datetime.datetime.now().year
    ensure_marcap_files([year - 1, year])
    cols = ["Date", "Code", "Marcap", "ChangesRatio"]
    marcap = pd.concat([pd.read_parquet(f"{MARCAP_DIR}/marcap-{y}.parquet", columns=cols)
                        for y in (year - 1, year)], ignore_index=True)
    values = compute_issuance(marcap, set(tickers))
    update_issuance_rows(values)
    logger.info(f"순발행 갱신: {len(values)}/{len(tickers)} 종목 (marcap 기준일 {marcap['Date'].max():%Y-%m-%d})")


SHORT_MAX_BACKFILL_DAYS = 60  # 달력일. 마지막 저장일 다음날부터 메우되, 아무리 오래 비어도 이만큼만 거슬러 간다
SHORT_STALE_DAYS = 7  # 최신 저장일이 이보다 오래되면 KRX 로그인/pykrx 문제로 보고 ERROR


def short_fetch_dates(last_saved, today):
    """공매도 잔고를 받아야 할 평일 목록: last_saved 다음날 ~ today, 최대 SHORT_MAX_BACKFILL_DAYS일 전까지.
    KRX는 T+2에 공개하므로 최근 이틀은 빈 응답이 정상이고, 다음 실행 때 다시 시도된다."""
    floor = today - datetime.timedelta(days=SHORT_MAX_BACKFILL_DAYS)
    start = max(last_saved + datetime.timedelta(days=1), floor) if last_saved else floor
    days = (today - start).days + 1
    return [d for d in (start + datetime.timedelta(days=i) for i in range(days)) if d.weekday() < 5]


def fetch_short_rows_for_date(stock, day, tickers):
    """하루치 전 종목 공매도 잔고 비중(%) - 날짜당 시장별 요청 1번. 0은 '공매도 없음'이라 그대로 둔다."""
    rows = []
    for market in ("KOSPI", "KOSDAQ"):
        df = stock.get_shorting_balance_by_ticker(day.strftime("%Y%m%d"), market)
        if df is None or df.empty or "비중" not in df.columns:
            continue  # 휴장일이거나 아직 미공개(T+2)
        ratios = df["비중"].dropna()
        rows += [(t, day.isoformat(), round(float(v), 4)) for t, v in ratios.items() if t in tickers]
    return rows


def _drop_pykrx_root_logs(record):
    """pykrx는 조회 실패(미공개 날짜 포함) 때 root에 logging.info(args, kwargs)를 잘못된 포맷으로 남겨
    '--- Logging error ---' 트레이스가 찍힌다. 실패 여부는 우리가 직접 판단하므로 그 기록만 버린다."""
    return "pykrx" not in record.pathname


def fetch_short_balance(tickers):
    """마지막 저장일 다음날부터 오늘까지 날짜별로 KRX(pykrx) 공매도 잔고 비중을 받아 short_balance에 저장한다.
    tickers(상위 N 유니버스)만 남긴다. 어느 날짜에서 실패하면 그 뒤는 받지 않는다 - 중간이 비면
    MAX(trade_date)가 앞서 나가 그 구멍이 다시 안 메워지기 때문."""
    if not (os.environ.get("KRX_ID") and os.environ.get("KRX_PW")):
        logger.warning("KRX_ID / KRX_PW 환경변수가 없어 공매도 잔고 수집을 건너뜁니다.")
        return 0
    from pykrx import stock  # 로그인 정보가 있을 때만 불러온다
    logging.getLogger().addFilter(_drop_pykrx_root_logs)

    today = datetime.datetime.now(KST).date()
    dates = short_fetch_dates(max_short_trade_date(), today)
    wanted, total, saved_dates = set(tickers), 0, []
    for day in dates:
        try:
            rows = fetch_short_rows_for_date(stock, day, wanted)
        except Exception as e:
            logger.error(f"{day} 공매도 잔고 조회 실패, 이후 날짜는 다음 실행에서 다시 받습니다: {e}")
            break
        if rows:
            upsert_short_rows(rows)
            total += len(rows)
            saved_dates.append(day)
    report_short_freshness(today, total, saved_dates, dates)
    return total


def report_short_freshness(today, total, saved_dates, tried_dates):
    latest = max_short_trade_date()
    if latest is None or (today - latest).days > SHORT_STALE_DAYS:
        logger.error(f"공매도 잔고가 오래됨: 최신 저장일 {latest} (오늘 {today}). KRX 로그인/pykrx 문제가 의심됩니다.")
        return
    covered = f"{saved_dates[0]} ~ {saved_dates[-1]}, {len(saved_dates)}일" if saved_dates else "새 날짜 없음"
    tried = f"{tried_dates[0]} ~ {tried_dates[-1]}" if tried_dates else "없음"
    logger.info(f"공매도 잔고 저장 완료: {total}행 ({covered}, 조회 범위 {tried}, 최신 저장일 {latest})")


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
            update_issuance([s["ticker"] for s in top_stocks])
        except Exception as e:
            logger.error(f"순발행 갱신 실패 (종목 시세는 정상 수집됨, 이전 값 유지): {e}")

        try:
            fetch_market_indicators()
        except Exception as e:
            logger.error(f"거시 지표 수집 실패 (종목 시세는 정상 수집됨): {e}")

        try:
            fetch_short_balance([s["ticker"] for s in top_stocks])
        except Exception:
            logger.exception("공매도 잔고 수집 실패 (종목 시세는 정상 수집됨)")

        elapsed = (datetime.datetime.now() - start).seconds
        logger.info(f"=== 시세 수집 완료: {processed}/{total} 종목 ({elapsed}초) ===")
        return processed

    except Exception as e:
        logger.exception("시세 수집 중 오류 발생")
        raise


if __name__ == "__main__":
    run()