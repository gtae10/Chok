package com.project.Chok;

import com.project.Chok.domain.Recommendation;
import com.project.Chok.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        List<Recommendation> recs = new java.util.ArrayList<>(IntStream.rangeClosed(1, 10)
                .mapToObj(i -> rec("T" + i, (double) i, (double) i, 0.0, 0.0)).toList());
        recs.add(rec("UP", null, null, 0.05, 0.7));      // 증자 +5%, 재료성 호재
        recs.add(rec("BB", null, null, -0.05, -0.6));    // 소각 -5%, 재료성 악재(경계값 포함)
        recs.add(rec("SMALL", null, null, 0.01, 0.59));  // 경계 미만은 태그 없음

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
}
