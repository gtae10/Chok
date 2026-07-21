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

    private final RiseProbabilityService riseProbabilityService;

    public TechnicalAnalysisService(RiseProbabilityService riseProbabilityService) {
        this.riseProbabilityService = riseProbabilityService;
    }

    public TechnicalIndicatorResult analyze(List<PriceHistory> priceHistory) {
        if (priceHistory == null || priceHistory.size() < MA_LONG) {
            log.warn("가격 데이터 부족: 최소 {}일 필요, 현재 {}일",
                    MA_LONG, priceHistory == null ? 0 : priceHistory.size());
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
        double[] features = buildFeatureVector(currentPrice, ma5, ma20, ma60, rsi, macdHist, bbPercentB, volumeRatio);
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

        return new TechnicalIndicatorResult(
                round2(ma5), round2(ma20), round2(ma60),
                round2(rsi),
                round2(macd), round2(macdSignal), round2(macdHist),
                round2(bbUpper), round2(bbLower), round2(bbPercentB),
                round2(volumeRatio), obvTrend,
                round2(finalScore), round2(riseProbability), probabilitySource, probabilityHorizonDays,
                reason.toString().trim()
        );
    }

    // ── 특징 벡터 (RiseProbabilityService.FEATURE_NAMES 순서와 반드시 일치해야 함) ──

    private double[] buildFeatureVector(double close, double ma5, double ma20, double ma60,
                                         double rsi, double macdHist, double bbPercentB, double volumeRatio) {
        return new double[]{
                (close - ma5) / close,
                (ma5 - ma20) / ma20,
                (ma20 - ma60) / ma60,
                (rsi - 50) / 50,
                macdHist / close,
                bbPercentB,
                Math.log(Math.max(volumeRatio, 0.01))
        };
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
