"""fundamentals_dart 자가점검 (네트워크 없음) - 실행: python test_fundamentals_dart.py"""
import numpy as np
import pandas as pd

from fundamentals_dart import build_panel, to_amount


def resp(rcept_no, end, ni, ni_add=None, fs="CFS", equity="1,000"):
    rows = [{"rcept_no": rcept_no, "fs_div": fs, "sj_div": "BS", "account_nm": "자본총계",
             "thstrm_dt": f"{end} 현재", "thstrm_amount": equity, "thstrm_add_amount": None},
            {"rcept_no": rcept_no, "fs_div": fs, "sj_div": "IS", "account_nm": "당기순이익(손실)",
             "thstrm_dt": "", "thstrm_amount": ni, "thstrm_add_amount": ni_add}]
    return {"status": "000", "list": rows}


if __name__ == "__main__":
    assert to_amount("1,234,567") == 1234567 and to_amount("-3,000") == -3000 and np.isnan(to_amount("-"))

    dates = pd.DatetimeIndex(pd.bdate_range("2023-01-02", "2024-12-31"))
    # 분기 단독 순이익 2023: 10, 20, 30, 40 / 2024 1Q: 50. 반기·3Q는 누적(add)으로, 사업보고서는 연간값으로 준다.
    fs = {"A": {(2023, "11013"): resp("20230515000001", "2023.03.31", "10"),
                (2023, "11012"): resp("20230814000001", "2023.06.30", "20", "30"),
                (2023, "11014"): resp("20231114000001", "2023.09.30", "30", "60"),
                (2023, "11011"): resp("20240312000001", "2023.12.31", "100"),
                (2024, "11013"): resp("20240515000001", "2024.03.31", "50")},
          # CFS 없음 → OFS. 013 응답은 행이 생기지 않아야 한다.
          "B": {(2023, "11011"): resp("20240313000001", "2023.12.31", "7", fs="OFS", equity="2,500"),
                (2023, "11013"): {"status": "013", "message": "조회된 데이타가 없습니다."}}}
    # A 사업보고서는 정정됨: 응답 rcept_no는 정정본, 최초 접수 2024-03-12(화)
    filings = {"A": [{"rcept_no": "20240312000001", "rcept_dt": "20240312", "report_nm": "사업보고서 (2023.12)"},
                     {"rcept_no": "20240401000009", "rcept_dt": "20240401", "report_nm": "[기재정정]사업보고서 (2023.12)"}]}
    for r in fs["A"][(2023, "11011")]["list"]:
        r["rcept_no"] = "20240401000009"
    p = build_panel(fs, filings, dates, fin={"B"})

    a = p[p.ticker == "A"].set_index("period_end")
    assert np.isnan(a.loc["2023-09-30", "ni_ttm"])          # 4분기가 다 없으면 NaN
    assert a.loc["2023-12-31", "ni_ttm"] == 100              # 10+20+30+(100-60)
    assert a.loc["2024-03-31", "ni_ttm"] == 140              # 20+30+40+50
    fy = a.loc["2023-12-31"]
    assert fy["avail_date"] == pd.Timestamp("2024-03-13") and fy["amended"]  # 최초 접수일 다음 거래일

    # 접수일 D(2023-05-15) 당일엔 안 보이고 다음 거래일부터 보인다 (asof 조인으로 확인)
    q1 = a.loc["2023-03-31", "avail_date"]
    assert q1 == pd.Timestamp("2023-05-16")
    view = pd.merge_asof(pd.DataFrame({"trade_date": pd.to_datetime(["2023-05-15", "2023-05-16"])}),
                         p[p.ticker == "A"].sort_values("avail_date"), left_on="trade_date", right_on="avail_date")
    assert view["period_end"].isna().tolist() == [True, False]

    b = p[p.ticker == "B"]
    assert len(b) == 1 and b.iloc[0]["book_equity"] == 2500 and b.iloc[0]["is_financial"] and not b.iloc[0]["amended"]
    assert not p.duplicated(["ticker", "avail_date"]).any()
    assert list(p.columns) == ["ticker", "avail_date", "period_end", "book_equity", "ni_ttm", "op_income_ttm",
                               "total_assets", "revenue_ttm", "is_financial", "amended", "bsns_year", "quarter", "currency"]
    print("fundamentals_dart self-check passed")
