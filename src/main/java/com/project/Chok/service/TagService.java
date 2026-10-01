package com.project.Chok.service;

import com.project.Chok.domain.Recommendation;
import com.project.Chok.domain.TagSnapshot;
import com.project.Chok.domain.Stock;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.repository.StockRepository;
import com.project.Chok.repository.TagSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
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

    static final double TOP_SHARE = 0.2;
    static final double ISSUANCE_THRESHOLD = 0.02;       // log 기준 약 ±2% 주식수 변화
    // 0.6(감성 프롬프트의 재무 임팩트 경계)은 첫날 100종목 중 56개가 호재로 걸려 구분력이 없었다.
    // 0.8 = 개별 뉴스 1,718건 중 상위 0.6% (2026-09-30 실측, PROJECT_PLAN D13)
    static final double MATERIAL_NEWS_THRESHOLD = 0.8;

    private final RecommendationRepository recommendationRepository;
    private final TagSnapshotRepository tagSnapshotRepository;
    private final StockRepository stockRepository;

    public TagService(RecommendationRepository recommendationRepository,
                      TagSnapshotRepository tagSnapshotRepository,
                      StockRepository stockRepository) {
        this.recommendationRepository = recommendationRepository;
        this.tagSnapshotRepository = tagSnapshotRepository;
        this.stockRepository = stockRepository;
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

    /** from 다음 날부터 to까지의 평일 수. 공휴일은 세지 않는 근사라 "약"으로만 쓴다. */
    public static int weekdaysBetween(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) n++;
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
        if (latest == null) return out;

        LocalDate first = tagSnapshotRepository.findFirstSnapDate();
        int elapsed = weekdaysBetween(first, latest);
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
