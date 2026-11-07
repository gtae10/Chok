"""
experiment_dart_events.py — DART 공시 이벤트 뒤에 주가가 시장(유니버스 중앙값)보다 일관되게 움직이는지 검증

공시 종류는 제목(report_nm)으로 분류한다 (정정 공시 "[기재정정]" 등은 중복이라 제외):
  buyback 자사주 취득, cancel 주식 소각, treasury_sale 자사주 처분, rights_offering 유상증자,
  bonus_issue 무상증자, convertible CB/BW/EB 발행, supply_contract 단일판매·공급계약,
  contract_cancel 공급계약 해지, dividend 현금·현물배당, largest_holder_change 최대주주 변경

시점: 공시 시각을 모르므로(장중/장후) 매매 가능한 첫 시점 = 공시일 "다음" 거래일(D1) 시가.
  react   공시 전 마지막 종가 → D1 시가   (공시 반응, 매매 불가 - 확인용)
  post1   D1 시가 → D1 종가
  post5   D1 시가 → D1+4 종가
  post20  D1 시가 → D1+19 종가
  모두 같은 날짜 구간의 유니버스 중앙값을 뺀 초과수익률. D1에 그날 시총 상위 PIT_TOP_N인 종목만.

채택 기준(실행 전 고정): n >= 30, post5 또는 post20 평균 초과수익 |x| >= 0.5%, |t| >= 2,
  기간을 앞/뒤 절반으로 나눠도 부호가 같을 것. 이벤트 종류 × 기간 여러 개 동시 비교라 일부 통과는 우연일 수 있다.

키: 환경변수 DART_API_KEY, 없으면 ../src/main/resources/application.properties 의 chok.dart.api-key
API 응답은 data/dart/ 에 캐시한다 (재실행 시 호출 없음).
실행: python experiment_dart_events.py
결과: model/dart_events_experiment_result.json
"""
import io
import json
import logging
import os
import re
import time
import zipfile
import xml.etree.ElementTree as ET
from datetime import date

import numpy as np
import pandas as pd
import requests

from train_model import load_pit_price_history

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

API = "https://opendart.fss.or.kr/api"
CACHE_DIR = os.path.join("data", "dart")
PROPS = os.path.join("..", "src", "main", "resources", "application.properties")
START = "20210101"
PBLNTF_TYPES = ["B", "I"]  # B 주요사항보고(자사주·증자·사채), I 거래소공시(공급계약·배당 등)
RESULT_PATH = os.path.join("model", "dart_events_experiment_result.json")

# 순서 중요: 먼저 맞는 것으로 분류 (해지를 공급계약보다 먼저)
EVENTS = [
    ("contract_cancel", ["공급계약해지", "공급계약체결해지"]),
    ("supply_contract", ["단일판매ㆍ공급계약체결", "단일판매·공급계약체결"]),
    ("buyback", ["자기주식취득결정", "자기주식취득신탁계약체결결정"]),
    ("treasury_sale", ["자기주식처분결정"]),
    ("cancel", ["주식소각결정"]),
    ("bonus_issue", ["무상증자결정"]),
    ("rights_offering", ["유상증자결정"]),
    ("convertible", ["전환사채권발행결정", "신주인수권부사채권발행결정", "교환사채권발행결정"]),
    ("dividend", ["현금ㆍ현물배당결정", "현금·현물배당결정"]),
    ("largest_holder_change", ["최대주주변경"]),
]
HORIZONS = {"post1": 1, "post5": 5, "post20": 20}
MIN_N, MIN_EFFECT, MIN_T = 30, 0.005, 2.0


def api_key() -> str:
    key = os.environ.get("DART_API_KEY", "").strip()
    if not key and os.path.exists(PROPS):
        with open(PROPS, encoding="utf-8") as f:
            for line in f:
                if line.startswith("chok.dart.api-key="):
                    key = line.split("=", 1)[1].strip()
    if not key:
        raise SystemExit("DART API 키가 없습니다 (DART_API_KEY 또는 chok.dart.api-key)")
    return key


def corp_codes(key: str) -> dict:
    """종목코드(6자리) → DART 고유번호(8자리)."""
    path = os.path.join(CACHE_DIR, "CORPCODE.xml")
    if not os.path.exists(path):
        r = requests.get(f"{API}/corpCode.xml", params={"crtfc_key": key}, timeout=60)
        r.raise_for_status()
        zipfile.ZipFile(io.BytesIO(r.content)).extractall(CACHE_DIR)
    root = ET.parse(path).getroot()
    return {e.findtext("stock_code").strip(): e.findtext("corp_code")
            for e in root.iter("list") if (e.findtext("stock_code") or "").strip()}


def disclosures(key: str, ticker: str, corp_code: str) -> list:
    path = os.path.join(CACHE_DIR, f"list_{ticker}.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    rows = []
    for ty in PBLNTF_TYPES:
        page = 1
        while True:
            r = requests.get(f"{API}/list.json", timeout=30, params={
                "crtfc_key": key, "corp_code": corp_code, "bgn_de": START,
                "end_de": date.today().strftime("%Y%m%d"), "pblntf_ty": ty,
                "page_no": page, "page_count": 100})
            j = r.json()
            time.sleep(0.15)
            if j.get("status") == "013":  # 조회된 데이터 없음
                break
            if j.get("status") != "000":
                raise RuntimeError(f"DART 오류 {j.get('status')}: {j.get('message')}")
            rows += [{"rcept_dt": x["rcept_dt"], "report_nm": x["report_nm"]} for x in j["list"]]
            if page >= int(j["total_page"]):
                break
            page += 1
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False)
    return rows


def classify(report_nm: str):
    name = report_nm.replace(" ", "")
    if name.startswith("["):  # [기재정정] [첨부정정] 등
        return None
    for event, keys in EVENTS:
        if any(k in name for k in keys):
            return event
    return None


def tstat(x: pd.Series) -> float:
    return float(x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))) if len(x) > 2 and x.std() > 0 else 0.0


def main():
    os.makedirs(CACHE_DIR, exist_ok=True)
    key = api_key()

    price_df, universe = load_pit_price_history(extra_cols=("Open", "Close"))
    price_df = price_df[(price_df["Open"] > 0) & (price_df["Close"] > 0)].copy()
    price_df["adj_open"] = price_df["close_price"] * price_df["Open"] / price_df["Close"]
    close = price_df.pivot(index="trade_date", columns="ticker", values="close_price").sort_index()
    opn = price_df.pivot(index="trade_date", columns="ticker", values="adj_open").reindex(close.index)
    in_univ = (universe.assign(v=True).pivot(index="trade_date", columns="ticker", values="v")
               .reindex(index=close.index, columns=close.columns).fillna(False).astype(bool))
    dates = close.index

    codes = corp_codes(key)
    tickers = [t for t in close.columns if t in codes]
    log.info("유니버스 종목 %d개 중 DART 고유번호 매칭 %d개", close.shape[1], len(tickers))

    events = []
    for i, t in enumerate(tickers, 1):
        for row in disclosures(key, t, codes[t]):
            ev = classify(row["report_nm"])
            if ev:
                events.append((t, pd.Timestamp(row["rcept_dt"]), ev))
        if i % 50 == 0:
            log.info("  공시 수집 %d/%d", i, len(tickers))
    ev_df = pd.DataFrame(events, columns=["ticker", "rcept_dt", "event"]).drop_duplicates()
    log.info("분류된 공시 %d건", len(ev_df))
    # 대조군: 유니버스 안 무작위 (종목, 날짜) 2000개 - 이벤트가 아닌데도 +가 나오면 계산 편향
    stacked = in_univ.stack()
    rnd = stacked[stacked].sample(2000, random_state=42).index
    ev_df = pd.concat([ev_df, pd.DataFrame({"ticker": [t for _, t in rnd], "rcept_dt": [d for d, _ in rnd],
                                            "event": "_placebo"})], ignore_index=True)

    # 같은 구간, D1 유니버스 종목들의 평균 (벤치마크). 중앙값을 쓰면 수익률 분포가 오른쪽으로
    # 치우쳐 있어 "평균 초과수익"이 아무 종목에서나 +로 나온다 (실측: 20일 약 +1~2%).
    def univ_median(num, den, d_num, d_den, d1):
        r = (num.loc[d_num] / den.loc[d_den] - 1).clip(-1, 1)
        return r[in_univ.loc[d1]].mean()

    records = []
    for t, rd, ev in ev_df.itertuples(index=False):
        i1 = dates.searchsorted(rd, side="right")      # 공시일 다음 거래일
        i0 = dates.searchsorted(rd, side="left") - 1   # 공시 전 마지막 거래일
        if i0 < 0 or i1 >= len(dates) or not in_univ.iat[i1, close.columns.get_loc(t)]:
            continue
        d0, d1 = dates[i0], dates[i1]
        rec = {"ticker": t, "event": ev, "date": d1}
        r = opn.at[d1, t] / close.at[d0, t] - 1
        rec["react"] = r - univ_median(opn, close, d1, d0, d1)
        for name, h in HORIZONS.items():
            j = i1 + h - 1
            if j >= len(dates):
                rec[name] = np.nan
                continue
            dj = dates[j]
            r = close.at[dj, t] / opn.at[d1, t] - 1
            rec[name] = r - univ_median(close, opn, dj, d1, d1)
        records.append(rec)
    res = pd.DataFrame(records)
    for c in ["react", *HORIZONS]:
        res.loc[res[c].abs() > 1.0, c] = np.nan  # 분할 등 이상치

    mid = res["date"].median() if len(res) else None
    summary, passed = {}, []
    log.info("%-22s %4s | %8s | %8s %8s %8s | t(5) t(20) | 앞/뒤 절반 post5", "공시", "n",
             "반응", "1일", "5일", "20일")
    for ev, g in res.groupby("event"):
        s = {"n": int(len(g))}
        for c in ["react", *HORIZONS]:
            x = g[c].dropna()
            s[c] = {"mean": round(float(x.mean()), 4) if len(x) else None,
                    "median": round(float(x.median()), 4) if len(x) else None,
                    "hit": round(float((x > 0).mean()), 3) if len(x) else None,
                    "t": round(tstat(x), 2), "n": int(len(x))}
        halves = {c: [g.loc[g["date"] <= mid, c].mean(), g.loc[g["date"] > mid, c].mean()] for c in HORIZONS}
        s["halves"] = {c: [round(float(v), 4) if pd.notna(v) else None for v in hv] for c, hv in halves.items()}
        summary[ev] = s
        for c in ("post5", "post20"):
            m, tv, hv = s[c]["mean"], s[c]["t"], halves[c]
            if (s[c]["n"] >= MIN_N and m is not None and abs(m) >= MIN_EFFECT and abs(tv) >= MIN_T
                    and all(pd.notna(v) and np.sign(v) == np.sign(m) for v in hv)):
                passed.append(f"{ev}:{c}")
        fmt = lambda c: f"{s[c]['mean'] * 100:+.2f}%" if s[c]["mean"] is not None else "-"
        log.info("%-22s %4d | %8s | %8s %8s %8s | %5.1f %5.1f | %s", ev, s["n"], fmt("react"), fmt("post1"),
                 fmt("post5"), fmt("post20"), s["post5"]["t"], s["post20"]["t"],
                 [f"{v * 100:+.2f}%" if v is not None else "-" for v in s["halves"]["post5"]])

    log.info("채택 기준(n>=%d, |초과수익|>=%.1f%%, |t|>=%.0f, 앞뒤 부호 일치) 통과: %s",
             MIN_N, MIN_EFFECT * 100, MIN_T, passed)
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "passed": passed}, f, ensure_ascii=False, indent=2)
    log.info("결과 저장: %s", RESULT_PATH)


if __name__ == "__main__":
    main()
