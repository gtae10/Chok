"""
fundamentals_dart.py — DART 정기보고서 주요계정으로 만든 재무 시점(PIT) 패널 (B단계 1회차 실험용)

대상: train_model.load_pit_price_history()의 유니버스(2020~현재, 그날 시총 상위)에 한 번이라도 든 종목.
수집: 종목 × 연도(2019~현재) × 보고서(1Q 11013, 반기 11012, 3Q 11014, 사업 11011)마다 OpenDART
  fnlttSinglAcnt 를 호출해 data/dart/fs_{ticker}_{year}_{reprt}.json 에 캐시한다 (있으면 재호출 없음).
  status 013(데이터 없음)도 캐시하되, 제출 기한이 아직 안 지난 보고서(기말+100일 이내)의 013은
  캐시하지 않는다(나중에 다시 받도록). 기말이 미래인 보고서는 호출하지 않는다.
  정기보고서 목록(list.json, pblntf_ty=A)은 data/dart/listA_{ticker}.json 에 캐시.

시점(avail_date): 같은 보고서(정정 접두어 "[기재정정]" 등을 뗀 보고서명이 같은 것)의 "최초" 접수일의
  다음 거래일(marcap 날짜 달력). 접수 시각을 모르므로 당일 장중 반영은 가정하지 않는다.
  ▶ 한계(정정공시): 값은 fnlttSinglAcnt 가 주는 최신(정정 반영) 값인데 avail_date 는 최초 접수일이다.
    정정으로 값이 바뀌었다면 최초 접수일~정정일 사이에는 당시 시장이 몰랐던 값을 쓰는 셈(약한 미래정보).
    반대로 정정일을 쓰면 원보고서 정보가 늦게 반영돼 신호가 늦어진다. 원값 복원은 원문 XBRL이 필요해
    여기선 하지 않고, 해당 행을 amended=True 로 표시만 한다 (민감도 확인: amended 행 제외 후 비교).
    TTM 계산에 쓰는 이전 분기 값도 같은 이유로 최신 값이다.

계정: CFS(연결) 우선, 없으면 OFS(별도).
  book_equity 자본총계 / total_assets 자산총계 (기말 시점값)
  ni_ttm 당기순이익, op_income_ttm 영업이익, revenue_ttm 매출액 (최근 4분기 단독값 합, 하나라도 없으면 NaN)
  분기 단독값: 1Q = 3개월값, 반기·3Q = 누적(thstrm_add_amount) − 직전 누적, 4Q = 사업보고서 연간 − 3Q 누적.
  ▶ 한계: fnlttSinglAcnt 는 지배기업 소유주지분 자본을 따로 주지 않아 book_equity 는 비지배지분 포함
    자본총계다. 순이익은 계정명에 "지배"가 든 행이 있으면 그것, 없으면 첫 당기순이익 행(보통 연결 전체).
    은행·보험 등은 매출액 계정이 없어 revenue_ttm 이 NaN 인 경우가 많다.
금융업(is_financial): FDR StockListing('KRX-DESC') Industry 에 FIN_KEYWORDS 가 들어가면 True.
  ▶ 한계: 현재 상장 목록이라 상장폐지 종목은 False 로 남는다.

실행: PYTHONUTF8=1 .venv/Scripts/python.exe fundamentals_dart.py   (받기 + 패널 요약)
"""
import glob
import json
import logging
import os
import re
import time
from datetime import date

import numpy as np
import pandas as pd
import requests

from experiment_dart_events import API, CACHE_DIR, api_key, corp_codes

log = logging.getLogger(__name__)

FIRST_YEAR = 2019
PIT_FIRST_YEAR = 2020
REPORTS = {"11013": 1, "11012": 2, "11014": 3, "11011": 4}  # 보고서 코드 → 회계 분기
ACCOUNTS = {"book_equity": "자본총계", "total_assets": "자산총계", "ni": "당기순이익",
            "op_income": "영업이익", "revenue": "매출액"}
FLOWS = ["ni", "op_income", "revenue"]
FIN_KEYWORDS = ("은행", "보험", "증권", "금융", "지주")
STOP_STATUSES = {"010", "011", "012", "020", "901"}  # 키 문제·일일 한도 → 더 불러도 소용없음
SLEEP = 0.1
KRX_DESC_PATH = os.path.join(CACHE_DIR, "krx_desc.csv")
COLUMNS = ["ticker", "avail_date", "period_end", "book_equity", "ni_ttm", "op_income_ttm",
           "total_assets", "revenue_ttm", "is_financial", "amended", "bsns_year", "quarter", "currency"]


class DartStop(Exception):
    pass


def _get(endpoint: str, params: dict, stats: dict):
    """3회 재시도(1·2·4초). 실패하면 None. 예외 메시지에는 키가 든 URL이 섞일 수 있어 종류만 남긴다."""
    for attempt in range(3):
        stats["calls"] += 1
        try:
            j = requests.get(f"{API}/{endpoint}", params=params, timeout=30).json()
            time.sleep(SLEEP)
            if j.get("status") in STOP_STATUSES:
                raise DartStop(f"{j.get('status')} {j.get('message')}")
            if j.get("status") in ("000", "013"):
                return j
            err = f"status {j.get('status')}"
        except DartStop:
            raise
        except Exception as e:
            err = type(e).__name__
        time.sleep(2 ** attempt)
    stats["fails"] += 1
    log.warning("DART %s 실패 (%s): %s", endpoint, err, {k: v for k, v in params.items() if k != "crtfc_key"})
    return None


def _quarter_end(year: int, q: int) -> pd.Timestamp:
    return pd.Timestamp(year, q * 3, 1) + pd.offsets.MonthEnd(0)


def fetch_all(tickers, key: str) -> dict:
    os.makedirs(CACHE_DIR, exist_ok=True)
    codes = corp_codes(key)
    stats = {"calls": 0, "fails": 0, "no_corp": 0}
    today = pd.Timestamp(date.today())
    t0 = time.time()
    for i, t in enumerate(tickers, 1):
        if t not in codes:
            stats["no_corp"] += 1
            log.warning("DART 고유번호 없음: %s", t)
            continue
        base = {"crtfc_key": key, "corp_code": codes[t]}
        path = os.path.join(CACHE_DIR, f"listA_{t}.json")
        if not os.path.exists(path):
            rows, page, ok = [], 1, True
            while True:
                j = _get("list.json", {**base, "bgn_de": f"{FIRST_YEAR}0101", "end_de": today.strftime("%Y%m%d"),
                                       "pblntf_ty": "A", "page_no": page, "page_count": 100}, stats)
                if j is None:
                    ok = False
                    break
                if j["status"] == "013":
                    break
                rows += [{k: x[k] for k in ("rcept_no", "rcept_dt", "report_nm")} for x in j["list"]]
                if page >= int(j["total_page"]):
                    break
                page += 1
            if ok:
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(rows, f, ensure_ascii=False)
        for year in range(FIRST_YEAR, today.year + 1):
            for reprt, q in REPORTS.items():
                end = _quarter_end(year, q)
                path = os.path.join(CACHE_DIR, f"fs_{t}_{year}_{reprt}.json")
                if end >= today or os.path.exists(path):
                    continue
                j = _get("fnlttSinglAcnt.json", {**base, "bsns_year": str(year), "reprt_code": reprt}, stats)
                if j is None or (j["status"] == "013" and today <= end + pd.Timedelta(days=100)):
                    continue
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(j, f, ensure_ascii=False)
        if i % 10 == 0 or i == len(tickers):
            log.info("진행 %d/%d 종목 | 호출 %d, 실패 %d | %.0f초", i, len(tickers),
                     stats["calls"], stats["fails"], time.time() - t0)
    stats["seconds"] = round(time.time() - t0)
    return stats


def to_amount(s):
    if s is None:
        return np.nan
    s = str(s).replace(",", "").strip()
    return float(s) if re.fullmatch(r"-?\d+(\.\d+)?", s) else np.nan


def parse_report(j: dict):
    """fnlttSinglAcnt 응답 → {rcept_no, period_end, 계정: (당기값, 누적값)} 또는 None(013/계정 없음)."""
    items = j.get("list") or []
    for fs in ("CFS", "OFS"):
        rows = [x for x in items if x.get("fs_div") == fs]
        if rows:
            break
    else:
        return None
    out = {"rcept_no": rows[0]["rcept_no"], "period_end": pd.NaT, "currency": rows[0].get("currency") or "KRW"}
    for name, acct in ACCOUNTS.items():
        hits = [x for x in rows if x["account_nm"].split("(")[0].replace(" ", "") == acct]
        hits = [x for x in hits if "지배" in x["account_nm"]] or hits
        out[name] = (to_amount(hits[0]["thstrm_amount"]), to_amount(hits[0].get("thstrm_add_amount"))) if hits \
            else (np.nan, np.nan)
    m = next((re.search(r"(\d{4})\.(\d{2})\.(\d{2})", x.get("thstrm_dt") or "") for x in rows
              if x.get("sj_div") == "BS"), None)
    if m:
        out["period_end"] = pd.Timestamp(*map(int, m.groups()))
    return out


def standalone_quarters(reports: dict) -> pd.DataFrame:
    """reports: {(year, q): parse_report 결과}. 반환: index (year, q), FLOWS 별 분기 단독값."""
    rows = {}
    for (y, q), r in reports.items():
        rows[(y, q)] = {}
        for f in FLOWS:
            amt, add = r[f]
            cum = amt if q in (1, 4) else add
            prev = reports.get((y, q - 1))
            prev_cum = np.nan if prev is None else (prev[f][0] if q - 1 == 1 else prev[f][1])
            val = cum - prev_cum if q > 1 else cum
            if np.isnan(val) and q in (2, 3):
                val = amt  # 누적값/직전 누적이 없으면 보고서의 3개월값
            rows[(y, q)][f] = val
    return pd.DataFrame.from_dict(rows, orient="index")


def ttm(standalone: pd.DataFrame) -> pd.DataFrame:
    if standalone.empty:
        return standalone
    idx = pd.Index([y * 4 + q - 1 for y, q in standalone.index])
    s = standalone.set_axis(idx).sort_index()
    full = s.reindex(range(idx.min(), idx.max() + 1)).rolling(4, min_periods=4).sum()
    return full.loc[idx].set_axis(standalone.index)


def first_filing(rcept_no: str, filings: list):
    """(최초 접수일 YYYYMMDD, amended). 정정 접두어 "[...]"를 뗀 보고서명이 같은 공시를 같은 보고서로 본다."""
    norm = lambda nm: re.sub(r"^\[[^\]]*\]", "", nm).replace(" ", "")
    me = next((x for x in filings if x["rcept_no"] == rcept_no), None)
    if me is None:
        return rcept_no[:8], False
    same = [x for x in filings if norm(x["report_nm"]) == norm(me["report_nm"])]
    return min(x["rcept_dt"] for x in same), len(same) > 1 or me["report_nm"].startswith("[")


def next_trading_day(dates: pd.DatetimeIndex, d: pd.Timestamp) -> pd.Timestamp:
    i = dates.searchsorted(d, side="right")
    return dates[i] if i < len(dates) else d + pd.offsets.BDay(1)  # 달력 밖(최근) → 평일 기준


def trading_calendar() -> pd.DatetimeIndex:
    files = glob.glob(os.path.join("data", "marcap", "marcap-*.parquet"))
    return pd.DatetimeIndex(sorted(pd.concat([pd.read_parquet(p, columns=["Date"])["Date"] for p in files]).unique()))


def financial_tickers(fetch: bool) -> set:
    if fetch or not os.path.exists(KRX_DESC_PATH):
        try:
            import FinanceDataReader as fdr
            fdr.StockListing("KRX-DESC")[["Code", "Industry"]].to_csv(KRX_DESC_PATH, index=False, encoding="utf-8")
        except Exception as e:
            log.warning("KRX-DESC 받기 실패 (%s) - is_financial 은 캐시/False", type(e).__name__)
    if not os.path.exists(KRX_DESC_PATH):
        return set()
    desc = pd.read_csv(KRX_DESC_PATH, dtype=str).dropna()
    return set(desc.loc[desc["Industry"].apply(lambda s: any(k in s for k in FIN_KEYWORDS)), "Code"])


def build_panel(fs_by_ticker: dict, filings_by_ticker: dict, dates: pd.DatetimeIndex, fin: set) -> pd.DataFrame:
    """fs_by_ticker: {ticker: {(year, reprt): 응답 json}}. 네트워크 없이 패널을 만든다 (테스트도 이걸 쓴다)."""
    out = []
    for t, raw in fs_by_ticker.items():
        reports = {}
        for (y, reprt), j in raw.items():
            r = parse_report(j) if j.get("status") == "000" else None
            if r:
                reports[(y, REPORTS[reprt])] = r
        if not reports:
            continue
        tt = ttm(standalone_quarters(reports))
        for (y, q), r in reports.items():
            first, amended = first_filing(r["rcept_no"], filings_by_ticker.get(t, []))
            out.append({"ticker": t, "avail_date": next_trading_day(dates, pd.Timestamp(first)),
                        "period_end": r["period_end"] if pd.notna(r["period_end"]) else _quarter_end(y, q),
                        "book_equity": r["book_equity"][0], "total_assets": r["total_assets"][0],
                        **{f"{f}_ttm": tt.at[(y, q), f] for f in FLOWS},
                        "is_financial": t in fin, "amended": bool(amended),
                        "bsns_year": y, "quarter": q, "currency": r["currency"]})
    df = pd.DataFrame(out, columns=COLUMNS)
    df["avail_date"] = pd.to_datetime(df["avail_date"])
    df["period_end"] = pd.to_datetime(df["period_end"])
    df[["is_financial", "amended"]] = df[["is_financial", "amended"]].astype(bool)
    df = df.sort_values(["ticker", "avail_date", "period_end"])
    return df.drop_duplicates(["ticker", "avail_date"], keep="last").reset_index(drop=True)


def universe_tickers() -> list:
    import train_model as tm
    tm.PIT_YEARS_BACK = date.today().year - PIT_FIRST_YEAR
    tm.ensure_marcap_files([FIRST_YEAR])  # 2019 공시의 다음 거래일 달력용
    _, universe = tm.load_pit_price_history()
    return sorted(universe["ticker"].unique())


def load_fundamentals_panel(fetch: bool = False) -> pd.DataFrame:
    """계약: 컬럼 COLUMNS, 같은 (ticker, avail_date) 중복 없음(나중 보고서 우선).
    fetch=False 면 data/dart 캐시만 읽는다 (네트워크 없음, 단 KRX-DESC 캐시가 없으면 한 번 받음)."""
    if fetch:
        stats = fetch_all(universe_tickers(), api_key())
        log.info("수집 결과: %s", stats)
    fs, filings = {}, {}
    for p in glob.glob(os.path.join(CACHE_DIR, "fs_*_*_*.json")):
        _, t, y, reprt = os.path.basename(p)[:-5].split("_")
        with open(p, encoding="utf-8") as f:
            fs.setdefault(t, {})[(int(y), reprt)] = json.load(f)
    for t in fs:
        p = os.path.join(CACHE_DIR, f"listA_{t}.json")
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                filings[t] = json.load(f)
    return build_panel(fs, filings, trading_calendar(), financial_tickers(fetch))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    try:
        panel = load_fundamentals_panel(fetch=True)
    except DartStop as e:
        raise SystemExit(f"DART 중단 ({e}). 일일 한도(020)면 내일 다시 실행하면 캐시 다음부터 이어서 받습니다.")
    print(f"행 {len(panel)}, 종목 {panel['ticker'].nunique()}, "
          f"avail_date {panel['avail_date'].min():%Y-%m-%d} ~ {panel['avail_date'].max():%Y-%m-%d}, "
          f"period_end {panel['period_end'].min():%Y-%m-%d} ~ {panel['period_end'].max():%Y-%m-%d}")
    print("결측률:", panel[COLUMNS[3:8]].isna().mean().round(3).to_dict())
    print(f"금융업 종목 {panel.loc[panel['is_financial'], 'ticker'].nunique()}, amended 비율 {panel['amended'].mean():.3f}")
