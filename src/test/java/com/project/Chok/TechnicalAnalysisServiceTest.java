package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.config.PythonEnvironment;
import com.project.Chok.domain.PriceHistory;
import com.project.Chok.domain.ShortBalance;
import com.project.Chok.dto.TechnicalIndicatorResult;
import com.project.Chok.repository.ShortBalanceRepository;
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
    private ShortBalanceRepository shortBalanceRepository;

    @BeforeEach
    void setUp() {
        // 테스트 환경엔 학습된 모델 파일이 없으므로 자동으로 휴리스틱 확률 계산으로 폴백됨
        AppProperties appProperties = new AppProperties();
        shortBalanceRepository = org.mockito.Mockito.mock(ShortBalanceRepository.class);
        service = new TechnicalAnalysisService(new RiseProbabilityService(new PythonEnvironment(appProperties), appProperties),
                shortBalanceRepository);
    }

    @Test
    @DisplayName("공매도 특징은 마지막 가격일의 정확히 2거래일 전 행만 쓰고, 그날 행이 없으면 null")
    void short_ratio_uses_exact_lag_row_only() {
        List<PriceHistory> prices = createPriceHistory(5, 10000, true);
        prices.forEach(p -> p.setTicker("005930"));
        LocalDate lagDate = prices.get(prices.size() - 3).getTradeDate();
        ShortBalance row = new ShortBalance();
        row.setShortRatio(0.11);
        org.mockito.Mockito.when(shortBalanceRepository.findByTickerAndTradeDate("005930", lagDate))
                .thenReturn(java.util.Optional.of(row));

        assertThat(service.shortRatioFeature(prices)).isEqualTo(0.11);

        // 마지막 날이 하루 밀리면 2거래일 전 날짜도 바뀐다 → 그 날짜 행이 없으니 오래된 값을 쓰지 않고 null
        PriceHistory next = new PriceHistory();
        next.setTicker("005930");
        next.setTradeDate(prices.get(prices.size() - 1).getTradeDate().plusDays(1));
        prices.add(next);
        assertThat(service.shortRatioFeature(prices)).isNull();
    }

    @Test
    @DisplayName("공매도 조회 중 DB 오류가 나도 예외 없이 null")
    void short_ratio_db_error_returns_null() {
        List<PriceHistory> prices = createPriceHistory(5, 10000, true);
        prices.forEach(p -> p.setTicker("005930"));
        org.mockito.Mockito.when(shortBalanceRepository.findByTickerAndTradeDate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("table missing"));

        assertThat(service.shortRatioFeature(prices)).isNull();
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

    @Test
    @DisplayName("12-1개월 모멘텀: 252영업일 전 대비 21영업일 전 수익률(%), 이력 1년 미만이면 null")
    void momentum12m_skips_recent_month() {
        // 300일 상승 추세: 252일 전 = index 47(14,700원), 21일 전 = index 278(37,800원)
        List<PriceHistory> data = createPriceHistory(300, 10000, true);
        assertThat(service.momentum12m(data)).isEqualTo(157.14); // 37800/14700 - 1

        assertThat(service.momentum12m(createPriceHistory(252, 10000, true))).isNull();
        assertThat(service.momentum12m(createPriceHistory(253, 10000, true))).isNotNull();
    }

    @Test
    @DisplayName("60일 변동성: 일간수익률 표본표준편차(%), 이력 61일 미만이면 null")
    void volatility60_is_sample_std_of_daily_returns() {
        // 종가가 100, 110 번갈아 -> 수익률 +10%, -9.09% 반복 (60개 중 30개씩)
        List<PriceHistory> data = new java.util.ArrayList<>();
        for (int i = 0; i < 61; i++) {
            PriceHistory p = new PriceHistory();
            p.setClosePrice(i % 2 == 0 ? 100 : 110);
            data.add(p);
        }
        double up = 0.1, down = 100.0 / 110 - 1, mean = (up + down) / 2;
        double expected = Math.sqrt(60 * Math.pow(up - mean, 2) / 59) * 100; // 편차 크기가 모두 같음
        assertThat(service.volatility60(data)).isEqualTo(Math.round(expected * 100) / 100.0);
        assertThat(service.volatility60(data.subList(0, 60))).isNull();
    }
}
