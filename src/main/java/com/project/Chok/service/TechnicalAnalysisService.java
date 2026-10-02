package com.project.Chok.service;

import com.project.Chok.domain.PriceHistory;
import com.project.Chok.dto.TechnicalIndicatorResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TechnicalAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TechnicalAnalysisService.class);

    private static final int MA_SHORT = 5;
    private static final int MA_MID = 20;
    private static final int MA_LONG = 60;
    private static final int RSI_PERIOD = 14;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;
    private static final int BOLLINGER_PERIOD = 20;
    private static final double BOLLINGER_STD_MULT = 2.0;
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final int OBV_TREND_LOOKBACK = 5;
    private static final int MOMENTUM_PERIOD = 90; // 중기 모멘텀(누적수익률) 계산 기간, 영업일 기준
    private static final int MOMENTUM_12M_LOOKBACK = 252; // 12-1개월 모멘텀: 약 12개월 전부터
    private static final int MOMENTUM_12M_SKIP = 21;      // 최근 약 1개월은 뺀다(단기 반전 효과 제외)
    private static final int VOL_PERIOD = 60;

    private final RiseProbabilityService riseProbabilityService;

    public TechnicalAnalysisService(RiseProbabilityService riseProbabilityService) {
        this.riseProbabilityService = riseProbabilityService;
    }

    public TechnicalIndicatorResult analyze(List<PriceHistory> priceHistory) {
        int minRequired = Math.max(MA_LONG, MOMENTUM_PERIOD);
        if (priceHistory == null || priceHistory.size() < minRequired) {
            log.warn("가격 데이터 부족: 최소 {}일 필요, 현재 {}일",
                    minRequired, priceHistory == null ? 0 : priceHistory.size());
            return TechnicalIndicatorResult.insufficient();
        }

        List<Double> closes = new ArrayList<>();
        List<Long> volumes = new ArrayList<>();
        for (PriceHistory p : priceHistory) {
            closes.add(p.getClosePrice().doubleValue());
            volumes.add(p.getVolume() == null ? 0L : p.getVolume());
        }

        double currentPrice = closes.get(closes.size() - 1);

        // 이동평균
        double ma5  = sma(closes, MA_SHORT);
        double ma20 = sma(closes, MA_MID);
        double ma60 = sma(closes, MA_LONG);

        // RSI
        double rsi = rsi(closes, RSI_PERIOD);

        // MACD
        double[] macdResult = macd(closes);
        double macd       = macdResult[0];
        double macdSignal = macdResult[1];
        double macdHist   = macdResult[2];

        // 볼린저밴드
        double[] bb      = bollingerBands(closes, BOLLINGER_PERIOD, BOLLINGER_STD_MULT);
        double bbUpper   = bb[0];
        double bbLower   = bb[1];
        double bbPercentB = (bbUpper - bbLower) != 0
                ? (currentPrice - bbLower) / (bbUpper - bbLower)
                : 0.5;

        // 거래량 지표
        double avgVolume20 = avgVolume(volumes, VOLUME_AVG_PERIOD);
        double currentVolume = volumes.get(volumes.size() - 1);
        double volumeRatio = avgVolume20 > 0 ? currentVolume / avgVolume20 : 1.0;
        double[] obv = obvSeries(closes, volumes);
        String obvTrend = obvTrend(obv);

        // 중기 모멘텀 (90영업일 누적수익률) - 로지스틱 회귀 특징으로만 사용, technicalScore 가중치는 안 건드림
        double momentum90 = momentum(closes, MOMENTUM_PERIOD);

        // 점수화
        StringBuilder reason = new StringBuilder();
        double maScore     = scoreMa(currentPrice, ma5, ma20, ma60, reason);
        double rsiScore    = scoreRsi(rsi, reason);
        double macdScore   = scoreMacd(macd, macdSignal, reason);
        double bbScore     = scoreBb(bbPercentB, reason);
        double volumeScore = scoreVolume(closes, volumeRatio, obvTrend, reason);

        double finalScore = (maScore * 0.30) + (rsiScore * 0.20)
                          + (macdScore * 0.20) + (bbScore * 0.15) + (volumeScore * 0.15);
        finalScore = clamp(finalScore, 0, 100);

        // 상승확률: 학습된 모델이 있으면 그걸로, 없으면 위 점수들을 조합한 휴리스틱으로 대체
        double[] features = buildFeatureVector(currentPrice, ma5, ma20, ma60, rsi, macdHist, bbPercentB, volumeRatio, momentum90);
        Double modelProbability = riseProbabilityService.predict(features);
        double riseProbability;
        String probabilitySource;
        Integer probabilityHorizonDays;
        if (modelProbability != null) {
            riseProbability = clamp(modelProbability, 1, 99);
            probabilitySource = "MODEL";
            probabilityHorizonDays = riseProbabilityService.getModelForwardDays();
        } else {
            riseProbability = heuristicProbability(maScore, rsiScore, macdScore, bbScore, volumeScore);
            probabilitySource = "HEURISTIC";
            probabilityHorizonDays = null; // 휴리스틱은 특정 예측기간을 주장하지 않음
        }

        // "유력 구간" - 여러 기간별 모델 중 AUC 기준을 통과한 후보가 있으면 그중 확률이 가장 높은 기간.
        // 기존 단일 배포 확률/기간 표시는 그대로 두고, 이건 추가 정보로만 얹는다.
        RiseProbabilityService.NotableHorizon notable = riseProbabilityService.findNotableHorizon(features);
        Integer notableHorizonDays = notable == null ? null : notable.horizonDays();
        Double notableHorizonProbability = notable == null ? null : round2(notable.probability());
        String notableHorizonApproxDate = notable == null ? null : notable.approxDate().toString();

        // "유력 하락구간" - 위 유력 구간(상승)의 대칭 버전. 같은 AUC 기준 미달이면 마찬가지로 null.
        RiseProbabilityService.NotableHorizon notableFall = riseProbabilityService.findNotableFallHorizon(features);
        Integer notableFallHorizonDays = notableFall == null ? null : notableFall.horizonDays();
        Double notableFallHorizonProbability = notableFall == null ? null : round2(notableFall.probability());
        String notableFallHorizonApproxDate = notableFall == null ? null : notableFall.approxDate().toString();

        return new TechnicalIndicatorResult(
                round2(ma5), round2(ma20), round2(ma60),
                round2(rsi),
                round2(macd), round2(macdSignal), round2(macdHist),
                round2(bbUpper), round2(bbLower), round2(bbPercentB),
                round2(volumeRatio), obvTrend,
                round2(finalScore), round2(riseProbability), probabilitySource, probabilityHorizonDays,
                notableHorizonDays, notableHorizonProbability, notableHorizonApproxDate,
                notableFallHorizonDays, notableFallHorizonProbability, notableFallHorizonApproxDate,
                reason.toString().trim()
        );
    }

    // ── 특징 벡터 (RiseProbabilityService.FEATURE_NAMES 순서와 반드시 일치해야 함) ──

    private double[] buildFeatureVector(double close, double ma5, double ma20, double ma60,
                                         double rsi, double macdHist, double bbPercentB,
                                         double volumeRatio, double momentum90) {
        return new double[]{
                (close - ma5) / close,
                (ma5 - ma20) / ma20,
                (ma20 - ma60) / ma60,
                (rsi - 50) / 50,
                macdHist / close,
                bbPercentB,
                Math.log(Math.max(volumeRatio, 0.01)),
                momentum90
        };
    }

    /**
     * 12-1개월 모멘텀(%): 252영업일 전 종가 대비 21영업일 전 종가 수익률 — 최근 1개월 제외.
     * 화면 참고 지표 전용이고 점수·모델 특징에는 들어가지 않는다(README 검증 과정 11·12번:
     * 생존편향 제거 후에도 순위 상관이 남았지만 구간별로 흔들려 모델엔 미채택).
     * 정의는 python-collector/experiment_new_features.py 의 mom12_1 과 같다. 이력이 253일 미만이면 null.
     */
    public Double momentum12m(List<PriceHistory> priceHistory) {
        int n = priceHistory == null ? 0 : priceHistory.size();
        if (n <= MOMENTUM_12M_LOOKBACK) return null;
        double past = priceHistory.get(n - 1 - MOMENTUM_12M_LOOKBACK).getClosePrice().doubleValue();
        double recent = priceHistory.get(n - 1 - MOMENTUM_12M_SKIP).getClosePrice().doubleValue();
        return past > 0 ? round2((recent - past) / past * 100) : null;
    }

    /**
     * 52주 신고가 대비 위치(%) - 태그 BREAKOUT_52W 입력. 오늘 종가가 직전 252영업일(오늘 제외) 최고 종가보다
     * 얼마나 위/아래인지: 0 이상이면 신고가 돌파. python-collector/evaluate_entry.py의 BREAKOUT_52W와 같은 정의.
     * 이력이 253일 미만이면 null.
     */
    public Double high52wGap(List<PriceHistory> priceHistory) {
        int n = priceHistory == null ? 0 : priceHistory.size();
        if (n <= MOMENTUM_12M_LOOKBACK) return null;
        int prevHigh = 0;
        for (int i = n - 1 - MOMENTUM_12M_LOOKBACK; i < n - 1; i++) {
            prevHigh = Math.max(prevHigh, priceHistory.get(i).getClosePrice());
        }
        double today = priceHistory.get(n - 1).getClosePrice();
        return prevHigh > 0 ? round2((today - prevHigh) / prevHigh * 100) : null;
    }

    /**
     * 60영업일 일간수익률 표준편차(%) - 태그 LOW_VOL20 입력. experiment_new_features.py의 vol60과
     * 같은 정의(pandas pct_change().rolling(60).std(), 표본표준편차). 이력이 61일 미만이면 null.
     */
    public Double volatility60(List<PriceHistory> priceHistory) {
        int n = priceHistory == null ? 0 : priceHistory.size();
        if (n <= VOL_PERIOD) return null;
        double[] r = new double[VOL_PERIOD];
        for (int i = 0; i < VOL_PERIOD; i++) {
            double prev = priceHistory.get(n - VOL_PERIOD - 1 + i).getClosePrice();
            double cur = priceHistory.get(n - VOL_PERIOD + i).getClosePrice();
            if (prev <= 0) return null;
            r[i] = cur / prev - 1;
        }
        double mean = java.util.Arrays.stream(r).average().orElse(0);
        double ss = java.util.Arrays.stream(r).map(x -> (x - mean) * (x - mean)).sum();
        return round2(Math.sqrt(ss / (VOL_PERIOD - 1)) * 100);
    }

    /** N영업일 전 대비 현재까지의 누적수익률. 중기(수개월) 모멘텀을 나타내는 팩터. */
    private double momentum(List<Double> closes, int period) {
        int size = closes.size();
        if (size <= period) return 0.0;
        double past = closes.get(size - 1 - period);
        double current = closes.get(size - 1);
        return past != 0 ? (current - past) / past : 0.0;
    }

    // 모델이 없을 때: 기존 지표 점수들을 -1~1 신호로 정규화해 가중합 후 시그모이드로 확률화.
    // 통계적으로 학습된 값이 아니라 "지표 종합 추정치"라는 점을 호출부(reason/UI)에서 명시해야 함.
    private double heuristicProbability(double maScore, double rsiScore, double macdScore,
                                         double bbScore, double volumeScore) {
        double z = ((maScore - 50) / 50) * 0.30
                 + ((rsiScore - 50) / 50) * 0.20
                 + ((macdScore - 50) / 50) * 0.20
                 + ((bbScore - 50) / 50) * 0.15
                 + ((volumeScore - 50) / 50) * 0.15;
        double sigmoid = 1.0 / (1.0 + Math.exp(-2.2 * z));
        return sigmoid * 100.0;
    }

    // ── 이동평균 ──────────────────────────────────────

    private double sma(List<Double> closes, int period) {
        int size = closes.size();
        if (size < period) return closes.get(size - 1);
        double sum = 0;
        for (int i = size - period; i < size; i++) sum += closes.get(i);
        return sum / period;
    }

    private double scoreMa(double price, double ma5, double ma20, double ma60,
                            StringBuilder reason) {
        double score;
        if (ma5 > ma20 && ma20 > ma60) {
            score = 80;
            reason.append("이동평균 정배열(상승추세). ");
        } else if (ma5 < ma20 && ma20 < ma60) {
            score = 20;
            reason.append("이동평균 역배열(하락추세). ");
        } else if (ma5 > ma20) {
            score = 60;
            reason.append("단기 이평선이 중기 이평선 상회. ");
        } else {
            score = 40;
            reason.append("단기 이평선이 중기 이평선 하회. ");
        }
        score += price > ma5 ? 10 : -10;
        return clamp(score, 0, 100);
    }

    // ── RSI ──────────────────────────────────────────

    private double rsi(List<Double> closes, int period) {
        int size = closes.size();
        if (size < period + 1) return 50.0;
        double avgGain = 0, avgLoss = 0;
        for (int i = size - period; i < size; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;
        if (avgLoss == 0) return 100.0;
        return 100 - (100 / (1 + avgGain / avgLoss));
    }

    private double scoreRsi(double rsi, StringBuilder reason) {
        if (rsi <= 30) {
            reason.append(String.format("RSI %.1f 과매도(반등 가능). ", rsi));
            return 75;
        } else if (rsi >= 70) {
            reason.append(String.format("RSI %.1f 과매수(조정 위험). ", rsi));
            return 30;
        } else if (rsi >= 50) {
            reason.append(String.format("RSI %.1f 중립~강세. ", rsi));
            return 60;
        } else {
            reason.append(String.format("RSI %.1f 중립~약세. ", rsi));
            return 45;
        }
    }

    // ── MACD ─────────────────────────────────────────

    private double[] macd(List<Double> closes) {
        List<Double> emaFast = emaSeries(closes, MACD_FAST);
        List<Double> emaSlow = emaSeries(closes, MACD_SLOW);
        int size = closes.size();
        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            macdLine.add(emaFast.get(i) - emaSlow.get(i));
        }
        List<Double> signalLine = emaSeries(macdLine, MACD_SIGNAL_PERIOD);
        double macd   = macdLine.get(size - 1);
        double signal = signalLine.get(size - 1);
        return new double[]{macd, signal, macd - signal};
    }

    private List<Double> emaSeries(List<Double> values, int period) {
        List<Double> ema = new ArrayList<>();
        double multiplier = 2.0 / (period + 1);
        double prev = values.get(0);
        ema.add(prev);
        for (int i = 1; i < values.size(); i++) {
            double cur = (values.get(i) - prev) * multiplier + prev;
            ema.add(cur);
            prev = cur;
        }
        return ema;
    }

    private double scoreMacd(double macd, double signal, StringBuilder reason) {
        if (macd > signal) {
            reason.append("MACD 시그널선 상회(상승 모멘텀). ");
            return 75;
        } else {
            reason.append("MACD 시그널선 하회(하락 모멘텀). ");
            return 30;
        }
    }

    // ── 볼린저밴드 ────────────────────────────────────

    private double[] bollingerBands(List<Double> closes, int period, double mult) {
        double sma = sma(closes, period);
        int size = closes.size();
        double variance = 0;
        for (int i = size - period; i < size; i++) {
            variance += Math.pow(closes.get(i) - sma, 2);
        }
        double std = Math.sqrt(variance / period);
        return new double[]{sma + mult * std, sma - mult * std};
    }

    private double scoreBb(double percentB, StringBuilder reason) {
        if (percentB <= 0.2) {
            reason.append("볼린저밴드 하단 근접(저평가 가능). ");
            return 70;
        } else if (percentB >= 0.8) {
            reason.append("볼린저밴드 상단 근접(과열 가능). ");
            return 35;
        } else {
            reason.append("볼린저밴드 중간 구간. ");
            return 50;
        }
    }

    // ── 거래량 ─────────────────────────────────────────

    private double avgVolume(List<Long> volumes, int period) {
        int size = volumes.size();
        int p = Math.min(period, size);
        double sum = 0;
        for (int i = size - p; i < size; i++) sum += volumes.get(i);
        return sum / p;
    }

    /** OBV(On-Balance Volume): 상승일엔 거래량을 더하고 하락일엔 빼서 누적 - 가격 추세를 거래량으로 확인하는 지표 */
    private double[] obvSeries(List<Double> closes, List<Long> volumes) {
        int size = closes.size();
        double[] obv = new double[size];
        obv[0] = 0;
        for (int i = 1; i < size; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change > 0) obv[i] = obv[i - 1] + volumes.get(i);
            else if (change < 0) obv[i] = obv[i - 1] - volumes.get(i);
            else obv[i] = obv[i - 1];
        }
        return obv;
    }

    private String obvTrend(double[] obv) {
        int size = obv.length;
        int lookback = Math.min(OBV_TREND_LOOKBACK, size - 1);
        if (lookback <= 0) return "FLAT";
        double delta = obv[size - 1] - obv[size - 1 - lookback];
        double scale = Math.abs(obv[size - 1]) + 1.0;
        double relativeDelta = delta / scale;
        if (relativeDelta > 0.02) return "RISING";
        if (relativeDelta < -0.02) return "FALLING";
        return "FLAT";
    }

    private double scoreVolume(List<Double> closes, double volumeRatio, String obvTrend, StringBuilder reason) {
        int size = closes.size();
        boolean upDay = size >= 2 && closes.get(size - 1) > closes.get(size - 2);
        boolean downDay = size >= 2 && closes.get(size - 1) < closes.get(size - 2);

        double score;
        if (upDay && volumeRatio >= 1.5) {
            score = 80;
            reason.append(String.format("거래량 평균 대비 %.1f배 급증(상승 확인). ", volumeRatio));
        } else if (upDay && volumeRatio >= 1.0) {
            score = 62;
            reason.append("거래량 동반 상승. ");
        } else if (downDay && volumeRatio >= 1.5) {
            score = 20;
            reason.append(String.format("거래량 평균 대비 %.1f배 급증(하락 확인). ", volumeRatio));
        } else if (downDay && volumeRatio >= 1.0) {
            score = 38;
            reason.append("거래량 동반 하락. ");
        } else {
            score = 50;
            reason.append("거래량 평이. ");
        }

        if ("RISING".equals(obvTrend)) {
            score += 5;
            reason.append("OBV 상승추세(매집 신호). ");
        } else if ("FALLING".equals(obvTrend)) {
            score -= 5;
            reason.append("OBV 하락추세(분산 신호). ");
        }

        return clamp(score, 0, 100);
    }

    // ── 유틸 ─────────────────────────────────────────

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
