package com.project.Chok;

import com.project.Chok.domain.Recommendation;
import com.project.Chok.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TagServiceTest {

    private static Recommendation rec(String ticker, Double momentum, Double vol, Double issuance, Double news) {
        Recommendation r = new Recommendation();
        r.setTicker(ticker);
        r.setMomentum12m(momentum);
        r.setVol60(vol);
        r.setIssuance252(issuance);
        r.setMaterialNewsScore(news);
        return r;
    }

    @Test
    @DisplayName("순위형 태그는 그날 전 종목 기준 상·하위 20%, 절대형 태그는 고정 경계로 붙는다")
    void computesTagsAgainstWholeDay() {
        // 10종목: 모멘텀·변동성 1..10 -> 모멘텀 상위 20% = 9,10 / 저변동성 하위 20% = 1,2
        List<Recommendation> recs = new ArrayList<>(IntStream.rangeClosed(1, 10)
                .mapToObj(i -> rec("T" + i, (double) i, (double) i, 0.0, 0.0)).toList());
        recs.add(rec("UP", null, null, 0.05, 0.85));     // 증자 +5%, 재료성 호재
        recs.add(rec("BB", null, null, -0.05, -0.8));    // 소각 -5%, 재료성 악재(경계값 포함)
        recs.add(rec("SMALL", null, null, 0.01, 0.79));  // 경계 미만은 태그 없음

        Map<String, List<String>> tags = TagService.compute(recs);

        assertThat(tags.get("T10")).containsExactly(TagService.MOMENTUM_TOP20);
        assertThat(tags.get("T9")).containsExactly(TagService.MOMENTUM_TOP20);
        assertThat(tags.get("T1")).containsExactly(TagService.LOW_VOL20);
        assertThat(tags.get("T2")).containsExactly(TagService.LOW_VOL20);
        assertThat(tags.get("T5")).isEmpty();
        assertThat(tags.get("UP")).containsExactly(TagService.ISSUANCE_UP, TagService.NEWS_POS);
        assertThat(tags.get("BB")).containsExactly(TagService.BUYBACK, TagService.NEWS_NEG);
        assertThat(tags.get("SMALL")).isEmpty();
    }

    @Test
    @DisplayName("52주 신고가 위치가 0 이상이면 BREAKOUT_52W, 미만이거나 값이 없으면 안 붙는다")
    void tagsBreakoutAtOrAbovePriorHigh() {
        Recommendation up = rec("UP", null, null, null, null);
        up.setHigh52wGap(0.0);
        Recommendation below = rec("BELOW", null, null, null, null);
        below.setHigh52wGap(-0.01);
        Map<String, List<String>> tags = TagService.compute(List.of(up, below, rec("NONE", null, null, null, null)));
        assertThat(tags.get("UP")).containsExactly(TagService.BREAKOUT_52W);
        assertThat(tags.get("BELOW")).isEmpty();
        assertThat(tags.get("NONE")).isEmpty();
    }

    @Test
    @DisplayName("평일 수는 다음 날부터 to까지, 주말은 건너뛴다")
    void countsWeekdaysBetween() {
        // 2026-09-30(수) -> 2026-10-07(수): 목·금·화·수 = 4 (토·일, 10/5 대체공휴일 제외)
        assertThat(TagService.tradingDaysBetween(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 7))).isEqualTo(4);
        assertThat(TagService.tradingDaysBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1))).isZero();
    }

    @Test
    @DisplayName("직전 기록일 대비 새로 붙은 태그와 사라진 태그를 가려낸다")
    @SuppressWarnings("unchecked")
    void diffsTagsAgainstPreviousDay() {
        Map<String, List<String>> prev = Map.of("A", List.of("BUYBACK", "LOW_VOL20"), "B", List.of("NEWS_POS"));
        Map<String, List<String>> today = Map.of("A", List.of("BUYBACK"), "C", List.of("NEWS_NEG"));

        Map<String, Object> d = TagService.diff(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30), today, prev);

        assertThat((List<Map<String, String>>) d.get("added")).containsExactly(Map.of("ticker", "C", "tag", "NEWS_NEG"));
        assertThat((List<Map<String, String>>) d.get("removed")).containsExactlyInAnyOrder(
                Map.of("ticker", "A", "tag", "LOW_VOL20"), Map.of("ticker", "B", "tag", "NEWS_POS"));
    }

    private static List<LocalDate> weekdays(int n) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 1, 1); out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) out.add(d);
        }
        return out;
    }

    @Test
    @DisplayName("시장 상태: 199일이면 빈 결과, 200일이면 마지막 종가와 MA200(그날 포함)을 비교")
    void marketStateComparesCloseWithMa200() {
        List<Double> closes199 = new ArrayList<>(Collections.nCopies(199, 100.0));
        assertThat(TagService.marketState(weekdays(199), closes199)).isEmpty();

        List<Double> up = new ArrayList<>(closes199);
        up.add(300.0); // MA200 = (199*100 + 300)/200 = 101
        Map<String, Object> s = TagService.marketState(weekdays(200), up);
        assertThat(s.get("asOf")).isEqualTo(weekdays(200).get(199).toString());
        assertThat(s.get("ma200")).isEqualTo(101.0);
        assertThat(s.get("gapPct")).isEqualTo(197.03); // (300/101-1)*100
        assertThat(s.get("above")).isEqualTo(true);

        List<Double> flat = new ArrayList<>(Collections.nCopies(200, 100.0));
        assertThat(TagService.marketState(weekdays(200), flat).get("above")).isEqualTo(false); // 같으면 위가 아님
    }

    @Test
    @DisplayName("완성된 겹치지 않는 창: 매일 신호 30일, h=5 -> 5개 (마지막 5일은 결과 미완성)")
    void maturedWindowsCountsOnlyCompletedNonOverlapping() {
        List<LocalDate> days = weekdays(30);
        assertThat(TagService.maturedWindows(days, days, 5, 0)).isEqualTo(5);
        // T+1 진입(지연 1): index ≤ 23만 완성 -> 0,5,10,15,20 그대로 5
        assertThat(TagService.maturedWindows(days, days, 5, 1)).isEqualTo(5);
        // 거래일 26일이면 지연 0은 0..20(5개), 지연 1은 index 20이 20+1+5=26 >= 26이라 빠져 4개
        assertThat(TagService.maturedWindows(days.subList(0, 26), days.subList(0, 26), 5, 0)).isEqualTo(5);
        assertThat(TagService.maturedWindows(days.subList(0, 26), days.subList(0, 26), 5, 1)).isEqualTo(4);
        assertThat(TagService.addTradingDays(LocalDate.of(2026, 11, 13), 1)) // 금 -> 월
                .isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(TagService.addTradingDays(LocalDate.of(2026, 12, 24), 1)) // 12/25 휴장 건너뜀
                .isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    @DisplayName("성과 결과 파일: live·ksTrendLive를 그대로 싣고, 없거나 깨지면 셋 다 null")
    @SuppressWarnings("unchecked")
    void evaluationReadsResultFileOrNulls(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("ok.json");
        Files.writeString(ok, "{\"backtest\":{},\"liveAt\":\"2026-11-12T21:40:05+09:00\",\"live\":{\"20\":{\"BREAKOUT_52W\":{\"windows\":3,\"verdict\":\"창 부족\"}}},"
                + "\"ksTrendLive\":{\"start\":\"2026-11-11\",\"horizons\":{\"20\":{\"windows\":0,\"verdict\":\"판정 불가\"}}}}");
        Map<String, Object> e = TagService.evaluation(ok.toFile());
        assertThat(((Map<String, Map<String, Map<String, Object>>>) e.get("liveEvaluation")).get("20").get("BREAKOUT_52W"))
                .containsEntry("windows", 3).containsEntry("verdict", "창 부족");
        assertThat((Map<String, Object>) e.get("ksTrendLive")).containsEntry("start", "2026-11-11");
        assertThat(e.get("evaluatedAt")).isEqualTo("2026-11-12T21:40:05+09:00");

        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json");
        for (Path p : List.of(broken, dir.resolve("missing.json"))) {
            assertThat(TagService.evaluation(p.toFile())).containsOnlyKeys("evaluatedAt", "liveEvaluation", "ksTrendLive")
                    .allSatisfy((k, v) -> assertThat(v).isNull());
        }
    }
}
