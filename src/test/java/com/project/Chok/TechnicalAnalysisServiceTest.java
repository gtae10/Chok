package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.PriceHistory;
import com.project.Chok.dto.TechnicalIndicatorResult;
import com.project.Chok.service.RiseProbabilityService;
import com.project.Chok.service.TechnicalAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalAnalysisServiceTest {

    private TechnicalAnalysisService service;

    @BeforeEach
    void setUp() {
        // 테스트 환경엔 학습된 모델 파일이 없으므로 자동으로 휴리스틱 확률 계산으로 폴백됨
        service = new TechnicalAnalysisService(new RiseProbabilityService(new AppProperties()));
    }

    // 테스트용 가격 데이터 생성 헬퍼
    private List<PriceHistory> createPriceHistory(int days, int basePrice, boolean uptrend) {
        List<PriceHistory> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            PriceHistory p = new PriceHistory();
            p.setTradeDate(LocalDate.now().minusDays(days - i));
            int price = uptrend ? basePrice + (i * 100) : basePrice - (i * 100);
            p.setOpenPrice(price);
            p.setHighPrice(price + 50);
            p.setLowPrice(price - 50);
            p.setClosePrice(price);
            p.setVolume(100000L);
            list.add(p);
        }
        return list;
    }

    @Test
    @DisplayName("데이터 부족 시 중립(50점) 반환")
    void insufficient_data_returns_neutral() {
        List<PriceHistory> shortData = createPriceHistory(10, 50000, true);

        TechnicalIndicatorResult result = service.analyze(shortData);

        assertThat(result.getTechnicalScore()).isEqualTo(50.0);
        assertThat(result.getTechnicalReason()).contains("부족");
    }

    @Test
    @DisplayName("상승 추세 데이터는 높은 점수를 받아야 한다")
    void uptrend_data_returns_high_score() {
        List<PriceHistory> uptrend = createPriceHistory(90, 10000, true);

        TechnicalIndicatorResult result = service.analyze(uptrend);

        assertThat(result.getTechnicalScore()).isGreaterThan(50.0);
        assertThat(result.getTechnicalReason()).contains("정배열");
    }

    @Test
    @DisplayName("하락 추세 데이터는 낮은 점수를 받아야 한다")
    void downtrend_data_returns_low_score() {
        List<PriceHistory> downtrend = createPriceHistory(90, 50000, false);

        TechnicalIndicatorResult result = service.analyze(downtrend);

        assertThat(result.getTechnicalScore()).isLessThan(50.0);
        assertThat(result.getTechnicalReason()).contains("역배열");
    }

    @Test
    @DisplayName("점수는 항상 0~100 사이여야 한다")
    void score_is_always_between_0_and_100() {
        List<PriceHistory> data = createPriceHistory(90, 50000, true);

        TechnicalIndicatorResult result = service.analyze(data);

        assertThat(result.getTechnicalScore())
                .isGreaterThanOrEqualTo(0.0)
                .isLessThanOrEqualTo(100.0);
    }

    @Test
    @DisplayName("이동평균이 정상적으로 계산되어야 한다")
    void moving_averages_are_calculated() {
        List<PriceHistory> data = createPriceHistory(90, 50000, true);

        TechnicalIndicatorResult result = service.analyze(data);

        assertThat(result.getMa5()).isNotNull().isPositive();
        assertThat(result.getMa20()).isNotNull().isPositive();
        assertThat(result.getMa60()).isNotNull().isPositive();
    }

    @Test
    @DisplayName("RSI는 0~100 사이여야 한다")
    void rsi_is_between_0_and_100() {
        List<PriceHistory> data = createPriceHistory(90, 50000, true);

        TechnicalIndicatorResult result = service.analyze(data);

        assertThat(result.getRsi())
                .isNotNull()
                .isGreaterThanOrEqualTo(0.0)
                .isLessThanOrEqualTo(100.0);
    }
}