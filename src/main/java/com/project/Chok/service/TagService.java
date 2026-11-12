package com.project.Chok.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.config.PythonEnvironment;
import com.project.Chok.domain.MarketIndicator;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.domain.TagSnapshot;
import com.project.Chok.domain.Stock;
import com.project.Chok.repository.MarketIndicatorRepository;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.repository.StockRepository;
import com.project.Chok.repository.TagSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 사실 기반 태그 (B1). 예측이 아니라 "이 종목은 지금 이런 상태다"를 표시하고, 그날 붙은 태그를
 * tag_snapshots에 남겨 태그별 실전 성과를 나중에 잴 수 있게 한다(C1).
 * 정의와 선정 근거는 docs/PROJECT_PLAN.md 4장이 정본 - 여기를 바꾸면 VERSION을 올리고 문서도 고친다.
 */
@Service
public class TagService {

    private static final Logger log = LoggerFactory.getLogger(TagService.class);

    public static final String VERSION = "v1";

    public static final String MOMENTUM_TOP20 = "MOMENTUM_TOP20";
    public static final String LOW_VOL20 = "LOW_VOL20";
    public static final String ISSUANCE_UP = "ISSUANCE_UP";
    public static final String BUYBACK = "BUYBACK";
    public static final String NEWS_POS = "NEWS_POS";
    public static final String NEWS_NEG = "NEWS_NEG";
    // 2026-10-02 추가(PROJECT_PLAN D14b). 이 태그의 실전 기록은 추가일부터 시작한다.
    public static final String BREAKOUT_52W = "BREAKOUT_52W";

    static final double TOP_SHARE = 0.2;
    static final double ISSUANCE_THRESHOLD = 0.02;       // log 기준 약 ±2% 주식수 변화
    // 0.6(감성 프롬프트의 재무 임팩트 경계)은 첫날 100종목 중 56개가 호재로 걸려 구분력이 없었다.
    // 0.8 = 개별 뉴스 1,718건 중 상위 0.6% (2026-09-30 실측, PROJECT_PLAN D13)
    static final double MATERIAL_NEWS_THRESHOLD = 0.8;

    private final RecommendationRepository recommendationRepository;
    private final TagSnapshotRepository tagSnapshotRepository;
    private final StockRepository stockRepository;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final PythonEnvironment pythonEnvironment;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public TagService(RecommendationRepository recommendationRepository,
                      TagSnapshotRepository tagSnapshotRepository,
                      StockRepository stockRepository,
                      MarketIndicatorRepository marketIndicatorRepository,
                      PythonEnvironment pythonEnvironment) {
        this.recommendationRepository = recommendationRepository;
        this.tagSnapshotRepository = tagSnapshotRepository;
        this.stockRepository = stockRepository;
        this.marketIndicatorRepository = marketIndicatorRepository;
        this.pythonEnvironment = pythonEnvironment;
    }

    /** 그날 전 종목(recs)을 기준으로 종목별 태그를 계산한다. 순위형 태그는 필터 전 전체로 경계를 잡는다. */
    public static Map<String, List<String>> compute(List<Recommendation> recs) {
        Double momentumCutoff = topShareCutoff(recs.stream().map(Recommendation::getMomentum12m).toList(), TOP_SHARE);
        // 저변동성 = 변동성 하위 20% -> 부호를 뒤집어 상위 20% 경계로 구한다
        Double negVolCutoff = topShareCutoff(recs.stream()
                .map(r -> r.getVol60() == null ? null : -r.getVol60()).toList(), TOP_SHARE);

        Map<String, List<String>> tags = new HashMap<>();
        for (Recommendation r : recs) {
            List<String> t = new ArrayList<>();
            if (momentumCutoff != null && r.getMomentum12m() != null && r.getMomentum12m() >= momentumCutoff) t.add(MOMENTUM_TOP20);
            if (negVolCutoff != null && r.getVol60() != null && -r.getVol60() >= negVolCutoff) t.add(LOW_VOL20);
            if (r.getIssuance252() != null && r.getIssuance252() >= ISSUANCE_THRESHOLD) t.add(ISSUANCE_UP);
            if (r.getIssuance252() != null && r.getIssuance252() <= -ISSUANCE_THRESHOLD) t.add(BUYBACK);
            if (r.getMaterialNewsScore() != null && r.getMaterialNewsScore() >= MATERIAL_NEWS_THRESHOLD) t.add(NEWS_POS);
            if (r.getMaterialNewsScore() != null && r.getMaterialNewsScore() <= -MATERIAL_NEWS_THRESHOLD) t.add(NEWS_NEG);
            if (r.getHigh52wGap() != null && r.getHigh52wGap() >= 0) t.add(BREAKOUT_52W);
            tags.put(r.getTicker(), t);
        }
        return tags;
    }

    /**
     * 상위 share(예: 0.2) 경계값 - 이 값 이상이면 상위권. 값이 있는 종목끼리만 센다.
     * 필터(매수만 보기 등) 전에 그날 전 종목으로 계산해야 "상위 20%"의 기준이 흔들리지 않는다.
     */
    public static Double topShareCutoff(List<Double> values, double share) {
        List<Double> sorted = values.stream().filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder()).toList();
        if (sorted.isEmpty()) return null;
        int k = Math.max(1, (int) Math.ceil(sorted.size() * share));
        return sorted.get(k - 1);
    }

    /** 분석이 끝난 날짜의 태그를 계산해 tag_snapshots에 그날치를 통째로 다시 쓴다(같은 날 재분석 대비). */
    @Transactional
    public int snapshot(LocalDate date) {
        List<Recommendation> recs = recommendationRepository.findByRecDateOrderByFinalScoreDesc(date);
        tagSnapshotRepository.deleteBySnapDate(date);
        List<TagSnapshot> rows = new ArrayList<>();
        compute(recs).forEach((ticker, tags) ->
                tags.forEach(tag -> rows.add(new TagSnapshot(date, ticker, tag, VERSION))));
        tagSnapshotRepository.saveAll(rows);
        log.info("태그 스냅샷 저장: {} 날짜, {}종목, {}건", date, recs.size(), rows.size());
        return rows.size();
    }

    /** 대시보드용 - 그날 저장된 태그 (종목 -> 태그 목록). 아직 스냅샷이 없는 날짜면 빈 맵. */
    public Map<String, List<String>> tagsOn(LocalDate date) {
        return tagSnapshotRepository.findBySnapDate(date).stream()
                .collect(Collectors.groupingBy(TagSnapshot::getTicker,
                        Collectors.mapping(TagSnapshot::getTag, Collectors.toList())));
    }

    /** 종목 상세용 - 가장 최근 스냅샷 날짜의 이 종목 태그. */
    public List<String> latestTagsFor(String ticker) {
        return tagSnapshotRepository.findLatestByTicker(ticker).stream().map(TagSnapshot::getTag).toList();
    }

    // 판정에 필요한 겹치지 않는 창 개수 - python-collector/evaluate_tags.py MIN_WINDOWS와 같은 값
    static final int MIN_WINDOWS = 6;
    static final int[] HORIZONS = {5, 20, 50};

    /** from 다음 날부터 to까지의 거래일 수 (주말·KRX 휴장일 제외, RecommendationService.isTradingDay). */
    public static int tradingDaysBetween(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (RecommendationService.isTradingDay(d)) n++;
        }
        return n;
    }

    /** 직전 기록일 대비 새로 붙은 / 사라진 (종목, 태그). */
    public static Map<String, Object> diff(LocalDate date, LocalDate prevDate,
                                    Map<String, List<String>> today, Map<String, List<String>> prev) {
        List<Map<String, String>> added = new ArrayList<>();
        List<Map<String, String>> removed = new ArrayList<>();
        today.forEach((ticker, tags) -> tags.stream()
                .filter(t -> !prev.getOrDefault(ticker, List.of()).contains(t))
                .forEach(t -> added.add(Map.of("ticker", ticker, "tag", t))));
        prev.forEach((ticker, tags) -> tags.stream()
                .filter(t -> !today.getOrDefault(ticker, List.of()).contains(t))
                .forEach(t -> removed.add(Map.of("ticker", ticker, "tag", t))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("prevDate", prevDate.toString());
        out.put("added", added);
        out.put("removed", removed);
        return out;
    }

    /**
     * 화면용 요약: 기록 진행도(판정까지 얼마나 남았나) + 태그별 부여 건수 + 직전 기록일 대비 변화.
     * 기록이 아직 없으면 빈 맵.
     */
    public Map<String, Object> board() {
        LocalDate latest = tagSnapshotRepository.findLatestSnapDate();
        Map<String, Object> out = new LinkedHashMap<>();
        // 후보는 태그 기록과 무관(KS_TREND는 시장 지표) - 기록이 없어도 내보내고, 계산 실패가 보드 전체를 깨지 않게 한다
        List<MarketIndicator> kospi = marketIndicatorRepository.findByKospiCloseNotNullOrderByTradeDate();
        out.put("calendarAsOf", kospi.isEmpty() ? null : kospi.get(kospi.size() - 1).getTradeDate().toString());
        try {
            out.put("candidates", candidates(kospi));
        } catch (Exception e) {
            log.error("검증 대기 후보 계산 실패", e);
            out.put("candidates", null);
        }
        out.putAll(evaluation(new File(pythonEnvironment.modelDir(), "tag_evaluation_result.json")));
        if (latest == null) return out;

        LocalDate first = tagSnapshotRepository.findFirstSnapDate();
        int elapsed = tradingDaysBetween(first, latest);
        out.put("firstDate", first.toString());
        out.put("latestDate", latest.toString());
        out.put("recordDays", tagSnapshotRepository.countSnapDates());
        out.put("elapsedDays", elapsed);
        out.put("minWindows", MIN_WINDOWS);
        out.put("horizons", Arrays.stream(HORIZONS)
                .mapToObj(h -> Map.of("days", h, "needDays", MIN_WINDOWS * h)).toList());

        Map<String, List<String>> today = tagsOn(latest);
        Map<String, Long> todayCount = today.values().stream().flatMap(List::stream)
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        List<Map<String, Object>> tags = new ArrayList<>();
        for (Object[] row : tagSnapshotRepository.countByTag()) {
            String tag = (String) row[0];
            tags.add(Map.of("tag", tag, "todayCount", todayCount.getOrDefault(tag, 0L), "totalRows", row[1]));
        }
        out.put("tags", tags);

        LocalDate prevDate = tagSnapshotRepository.findSnapDateBefore(latest);
        out.put("changes", prevDate == null ? null : withNames(diff(latest, prevDate, today, tagsOn(prevDate))));
        return out;
    }

    /**
     * evaluate_tags.py 결과(model/tag_evaluation_result.json)의 실전 성과를 그대로 싣는다:
     * liveEvaluation = JSON "live"(기간 -> 태그 -> windows·windowMean·windowsExpectedSign·verdict ...),
     * ksTrendLive = JSON "ksTrendLive", evaluatedAt = JSON "liveAt"(live 집계 성공 시각, +09:00 ISO). 없거나 못 읽으면 셋 다 null.
     */
    public static Map<String, Object> evaluation(File file) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluatedAt", null);
        out.put("liveEvaluation", null);
        out.put("ksTrendLive", null);
        if (!file.exists()) return out;  // 첫 집계 전 - 정상이라 WARN을 남기지 않는다
        try {
            Map<?, ?> raw = objectMapper.readValue(file, Map.class);
            // 파일 시각이 아니라 live 집계가 성공한 시각 - 실패한 실행이 오래된 값을 최신처럼 보이게 하지 않도록
            out.put("evaluatedAt", raw.get("liveAt"));
            out.put("liveEvaluation", raw.get("live"));
            out.put("ksTrendLive", raw.get("ksTrendLive"));
        } catch (IOException | RuntimeException e) {
            log.warn("태그 성과 결과 파일 읽기 실패 ({}): {}", file.getAbsolutePath(), e.getMessage());
        }
        return out;
    }

    // 검증 대기 후보 (PROJECT_PLAN 7장) - 매수 신호가 아니라 실전 기록이 판정 창 수를 채우는 중인 항목
    public static final LocalDate KS_TREND_LIVE_START = LocalDate.of(2026, 11, 11);
    static final int MA_DAYS = 200;

    /** 후보별 실전 진행도. 거래일 달력 = market_indicators 날짜(KOSPI 종가 있는 날). */
    private List<Map<String, Object>> candidates(List<MarketIndicator> kospi) {
        List<LocalDate> days = kospi.stream().map(MarketIndicator::getTradeDate).toList();
        List<Double> closes = kospi.stream().map(MarketIndicator::getKospiClose).toList();
        // 거래일 달력에 없는 날(휴장일에 돌린 분석)의 스냅샷은 신호일로 세지 않는다
        Set<LocalDate> calendar = new HashSet<>(days);
        List<LocalDate> breakout = tagSnapshotRepository.findSnapDatesByTag(BREAKOUT_52W).stream()
                .filter(calendar::contains).toList();
        List<LocalDate> trend = new ArrayList<>();
        for (int i = MA_DAYS - 1; i < days.size(); i++) {
            if (!days.get(i).isBefore(KS_TREND_LIVE_START) && closes.get(i) > ma(closes, i)) trend.add(days.get(i));
        }
        return List.of(
                // 태그는 신호일 종가 기준 성과(지연 0), KS_TREND는 T+1 종가 진입(지연 1) - 사전 등록(D16·D17)
                candidate(BREAKOUT_52W, breakout.isEmpty() ? null : breakout.get(0), breakout, days, 0, HORIZONS),
                candidate("KS_TREND", KS_TREND_LIVE_START, trend, days, 1, 20, 50));
    }

    private static Map<String, Object> candidate(String key, LocalDate liveStart, List<LocalDate> signals,
                                                 List<LocalDate> days, int entryLag, int... horizons) {
        LocalDate lastDay = days.isEmpty() ? LocalDate.now() : days.get(days.size() - 1);
        List<Map<String, Object>> hs = new ArrayList<>();
        for (int h : horizons) {
            int windows = maturedWindows(signals, days, h, entryLag);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("days", h);
            m.put("windows", windows);
            m.put("etaDate", windows >= MIN_WINDOWS ? null : addTradingDays(lastDay, (MIN_WINDOWS - windows) * h).toString());
            hs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("liveStart", liveStart == null ? null : liveStart.toString());
        out.put("needWindows", MIN_WINDOWS);
        out.put("horizons", hs);
        return out;
    }

    /** 시장 상태: KOSPI 종가와 MA200(그날 포함 최근 200거래일 평균). 200일 미만이면 빈 맵. */
    public Map<String, Object> marketState() {
        List<MarketIndicator> kospi = marketIndicatorRepository.findByKospiCloseNotNullOrderByTradeDate();
        return marketState(kospi.stream().map(MarketIndicator::getTradeDate).toList(),
                kospi.stream().map(MarketIndicator::getKospiClose).toList());
    }

    public static Map<String, Object> marketState(List<LocalDate> dates, List<Double> closes) {
        Map<String, Object> out = new LinkedHashMap<>();
        int last = closes.size() - 1;
        if (closes.size() < MA_DAYS) return out;
        double close = closes.get(last);
        double ma200 = ma(closes, last);
        out.put("asOf", dates.get(last).toString());
        out.put("close", close);
        out.put("ma200", ma200);
        out.put("gapPct", Math.round((close / ma200 - 1) * 10000) / 100.0);
        out.put("above", close > ma200);
        return out;
    }

    private static double ma(List<Double> closes, int end) {
        double sum = 0;
        for (int i = end - MA_DAYS + 1; i <= end; i++) sum += closes.get(i);
        return sum / MA_DAYS;
    }

    /**
     * 판정에 쓸 수 있는 겹치지 않는 창 수 - evaluate_tags.py non_overlapping_dates와 같은 탐욕 선택.
     * 신호일+entryLag(진입 지연, T+1 진입이면 1)+h거래일이 달력에 이미 있는(결과가 나온) 날만 센다.
     */
    public static int maturedWindows(List<LocalDate> signalDates, List<LocalDate> tradingDays, int h, int entryLag) {
        Map<LocalDate, Integer> pos = new HashMap<>();
        for (int i = 0; i < tradingDays.size(); i++) pos.put(tradingDays.get(i), i);
        int picked = 0;
        Integer lastPicked = null;
        for (LocalDate d : new TreeSet<>(signalDates)) {
            Integer i = pos.get(d);
            if (i == null || i + entryLag + h >= tradingDays.size()) continue;
            if (lastPicked == null || i - lastPicked >= h) {
                picked++;
                lastPicked = i;
            }
        }
        return picked;
    }

    /** from 이후 n번째 거래일 (주말·KRX 휴장일 제외, 예상일 표시용). */
    public static LocalDate addTradingDays(LocalDate from, int n) {
        LocalDate d = from;
        while (n > 0) {
            d = d.plusDays(1);
            if (RecommendationService.isTradingDay(d)) n--;
        }
        return d;
    }

    /** 변화 목록에 종목명을 붙인다 - 순위에서 빠진 종목은 오늘 추천 목록에 없어 화면이 이름을 알 수 없다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withNames(Map<String, Object> changes) {
        Map<String, String> names = new HashMap<>();
        List<Map<String, String>> added = (List<Map<String, String>>) changes.get("added");
        List<Map<String, String>> removed = (List<Map<String, String>>) changes.get("removed");
        Set<String> tickers = new HashSet<>();
        added.forEach(x -> tickers.add(x.get("ticker")));
        removed.forEach(x -> tickers.add(x.get("ticker")));
        for (Stock st : stockRepository.findAllById(tickers)) names.put(st.getTicker(), st.getName());
        for (String key : List.of("added", "removed")) {
            changes.put(key, ((List<Map<String, String>>) changes.get(key)).stream().map(x -> {
                Map<String, String> m = new LinkedHashMap<>(x);
                m.put("name", names.getOrDefault(x.get("ticker"), x.get("ticker")));
                return m;
            }).toList());
        }
        return changes;
    }
}
