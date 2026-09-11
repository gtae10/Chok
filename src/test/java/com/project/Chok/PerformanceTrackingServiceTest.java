package com.project.Chok;

import com.project.Chok.domain.PerformanceSnapshot;
import com.project.Chok.domain.PriceHistory;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.dto.PerformanceSummaryResponse;
import com.project.Chok.dto.RecommendationResponse;
import com.project.Chok.repository.PerformanceSnapshotRepository;
import com.project.Chok.repository.PriceHistoryRepository;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.service.PerformanceTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerformanceTrackingServiceTest {

    @Mock private RecommendationRepository recommendationRepository;
    @Mock private PriceHistoryRepository priceHistoryRepository;
    @Mock private PerformanceSnapshotRepository performanceSnapshotRepository;

    private PerformanceTrackingService service;

    @BeforeEach
    void setUp() {
        service = new PerformanceTrackingService(
                recommendationRepository, priceHistoryRepository, performanceSnapshotRepository);
    }

    private Recommendation rec(String ticker, String name, double finalScore) {
        Recommendation r = new Recommendation();
        r.setTicker(ticker);
        r.setName(name);
        r.setMarket("KOSPI");
        r.setTechnicalScore(50.0);
        r.setSentimentScore(0.1);
        r.setFinalScore(finalScore);
        r.setRiseProbability(60.0);
        return r;
    }

    private PriceHistory price(int closePrice) {
        PriceHistory p = new PriceHistory();
        p.setClosePrice(closePrice);
        return p;
    }

    @Test
    @DisplayName("추천이 없는 날짜는 스냅샷 저장 자체를 생략한다")
    void saveSnapshot_skips_when_no_recommendations() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        when(recommendationRepository.findByRecDateOrderByFinalScoreDesc(date)).thenReturn(List.of());

        service.saveSnapshot(date);

        verify(performanceSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("상위 10개까지만 스냅샷으로 저장한다")
    void saveSnapshot_limits_to_top_10() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        List<Recommendation> recs = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> rec("00000" + i, "종목" + i, 100.0 - i))
                .toList();
        when(recommendationRepository.findByRecDateOrderByFinalScoreDesc(date)).thenReturn(recs);
        when(priceHistoryRepository.findByTickerAndTradeDate(any(), eq(date)))
                .thenReturn(Optional.of(price(10000)));
        when(performanceSnapshotRepository.findByTickerAndSnapshotDate(any(), eq(date)))
                .thenReturn(Optional.empty());

        service.saveSnapshot(date);

        verify(performanceSnapshotRepository, times(10)).save(any());
    }

    @Test
    @DisplayName("같은 종목+날짜에 이미 스냅샷이 있으면 새로 만들지 않고 기존 엔티티를 갱신한다")
    void saveSnapshot_upserts_existing_entity() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        Recommendation r = rec("005930", "삼성전자", 88.0);
        when(recommendationRepository.findByRecDateOrderByFinalScoreDesc(date)).thenReturn(List.of(r));
        when(priceHistoryRepository.findByTickerAndTradeDate("005930", date))
                .thenReturn(Optional.of(price(70000)));

        PerformanceSnapshot existing = new PerformanceSnapshot();
        existing.setId(42L);
        when(performanceSnapshotRepository.findByTickerAndSnapshotDate("005930", date))
                .thenReturn(Optional.of(existing));

        service.saveSnapshot(date);

        ArgumentCaptor<PerformanceSnapshot> captor = ArgumentCaptor.forClass(PerformanceSnapshot.class);
        verify(performanceSnapshotRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getEntryPrice()).isEqualTo(70000);
        assertThat(captor.getValue().getRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("해당일 종가가 없는 종목은 진입가를 정할 수 없으므로 스냅샷 저장을 건너뛴다")
    void saveSnapshot_skips_ticker_without_price_that_day() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        Recommendation r = rec("005930", "삼성전자", 88.0);
        when(recommendationRepository.findByRecDateOrderByFinalScoreDesc(date)).thenReturn(List.of(r));
        when(priceHistoryRepository.findByTickerAndTradeDate("005930", date)).thenReturn(Optional.empty());

        service.saveSnapshot(date);

        verify(performanceSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("순위는 종합점수 내림차순 입력 순서 그대로 1부터 매겨진다")
    void saveSnapshot_assigns_rank_in_order() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        List<Recommendation> recs = List.of(
                rec("A", "가", 90.0), rec("B", "나", 80.0), rec("C", "다", 70.0));
        when(recommendationRepository.findByRecDateOrderByFinalScoreDesc(date)).thenReturn(recs);
        when(priceHistoryRepository.findByTickerAndTradeDate(any(), eq(date)))
                .thenReturn(Optional.of(price(1000)));
        when(performanceSnapshotRepository.findByTickerAndSnapshotDate(any(), eq(date)))
                .thenReturn(Optional.empty());

        service.saveSnapshot(date);

        ArgumentCaptor<PerformanceSnapshot> captor = ArgumentCaptor.forClass(PerformanceSnapshot.class);
        verify(performanceSnapshotRepository, times(3)).save(captor.capture());
        List<PerformanceSnapshot> saved = captor.getAllValues();
        assertThat(saved.get(0).getTicker()).isEqualTo("A");
        assertThat(saved.get(0).getRank()).isEqualTo(1);
        assertThat(saved.get(1).getRank()).isEqualTo(2);
        assertThat(saved.get(2).getRank()).isEqualTo(3);
    }

    private PerformanceSnapshot snapshot(String ticker, String name, LocalDate date, int rank, int entryPrice) {
        PerformanceSnapshot s = new PerformanceSnapshot();
        s.setTicker(ticker);
        s.setName(name);
        s.setSnapshotDate(date);
        s.setRank(rank);
        s.setTechnicalScore(50.0);
        s.setSentimentScore(0.1);
        s.setFinalScore(80.0);
        s.setRiseProbability(60.0);
        s.setEntryPrice(entryPrice);
        return s;
    }

    @Test
    @DisplayName("현재가가 진입가보다 오르면 양의 수익률을 계산한다")
    void getPerformanceSummary_computes_positive_return() {
        LocalDate date = LocalDate.now().minusDays(5);
        PerformanceSnapshot s = snapshot("005930", "삼성전자", date, 1, 10000);
        when(performanceSnapshotRepository.findAllOrderBySnapshotDateDescRankAsc()).thenReturn(List.of(s));
        when(priceHistoryRepository.findLatestByTicker("005930")).thenReturn(Optional.of(price(11000)));

        PerformanceSummaryResponse summary = service.getPerformanceSummary();

        assertThat(summary.getItems()).hasSize(1);
        assertThat(summary.getItems().get(0).getReturnRate()).isEqualTo(0.1); // (11000-10000)/10000
        assertThat(summary.getItems().get(0).getHoldingDays()).isEqualTo(5);
        assertThat(summary.getWinRate()).isEqualTo(100.0);
        assertThat(summary.getAvgReturnRate()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("현재가가 진입가보다 내리면 음의 수익률을 계산한다")
    void getPerformanceSummary_computes_negative_return() {
        LocalDate date = LocalDate.now().minusDays(2);
        PerformanceSnapshot s = snapshot("005930", "삼성전자", date, 1, 10000);
        when(performanceSnapshotRepository.findAllOrderBySnapshotDateDescRankAsc()).thenReturn(List.of(s));
        when(priceHistoryRepository.findLatestByTicker("005930")).thenReturn(Optional.of(price(9000)));

        PerformanceSummaryResponse summary = service.getPerformanceSummary();

        assertThat(summary.getItems().get(0).getReturnRate()).isEqualTo(-0.1);
        assertThat(summary.getWinRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("현재가를 알 수 없는 종목은 수익률 null이고 승률/평균수익률 집계에서 제외된다")
    void getPerformanceSummary_excludes_missing_price_from_aggregates() {
        LocalDate date = LocalDate.now().minusDays(1);
        PerformanceSnapshot winner = snapshot("005930", "삼성전자", date, 1, 10000);
        PerformanceSnapshot unknown = snapshot("000660", "SK하이닉스", date, 2, 20000);
        when(performanceSnapshotRepository.findAllOrderBySnapshotDateDescRankAsc())
                .thenReturn(List.of(winner, unknown));
        when(priceHistoryRepository.findLatestByTicker("005930")).thenReturn(Optional.of(price(12000)));
        when(priceHistoryRepository.findLatestByTicker("000660")).thenReturn(Optional.empty());

        PerformanceSummaryResponse summary = service.getPerformanceSummary();

        assertThat(summary.getTotalCount()).isEqualTo(2);
        assertThat(summary.getItems()).extracting(i -> i.getTicker())
                .containsExactly("005930", "000660");
        assertThat(summary.getItems().get(1).getReturnRate()).isNull();
        assertThat(summary.getItems().get(1).getCurrentPrice()).isNull();
        // 집계는 유효한 1건(005930)만으로 계산되어야 함
        assertThat(summary.getWinRate()).isEqualTo(100.0);
        assertThat(summary.getAvgReturnRate()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("스냅샷이 하나도 없으면 승률/평균수익률은 null, 목록은 빈 리스트")
    void getPerformanceSummary_returns_nulls_when_no_snapshots() {
        when(performanceSnapshotRepository.findAllOrderBySnapshotDateDescRankAsc()).thenReturn(List.of());

        PerformanceSummaryResponse summary = service.getPerformanceSummary();

        assertThat(summary.getTotalCount()).isEqualTo(0);
        assertThat(summary.getWinRate()).isNull();
        assertThat(summary.getAvgReturnRate()).isNull();
        assertThat(summary.getItems()).isEmpty();
    }

    private Recommendation recAt(String ticker, String name, LocalDate recDate, double finalScore) {
        Recommendation r = rec(ticker, name, finalScore);
        r.setRecDate(recDate);
        return r;
    }

    @Test
    @DisplayName("스냅샷 선정일부터 오늘까지의 추천 이력을 시간순으로 반환한다")
    void getTrend_returns_history_from_snapshot_date_ordered_ascending() {
        PerformanceSnapshot snapshot = new PerformanceSnapshot();
        snapshot.setId(1L);
        snapshot.setTicker("005930");
        snapshot.setSnapshotDate(LocalDate.of(2026, 9, 1));
        when(performanceSnapshotRepository.findById(1L)).thenReturn(Optional.of(snapshot));

        List<Recommendation> history = List.of(
                recAt("005930", "삼성전자", LocalDate.of(2026, 9, 1), 70.0),
                recAt("005930", "삼성전자", LocalDate.of(2026, 9, 2), 75.0),
                recAt("005930", "삼성전자", LocalDate.of(2026, 9, 3), 80.0));
        when(recommendationRepository.findByTickerAndRecDateFromOrderByRecDateAsc(
                "005930", LocalDate.of(2026, 9, 1))).thenReturn(history);

        List<RecommendationResponse> trend = service.getTrend(1L);

        assertThat(trend).hasSize(3);
        assertThat(trend).extracting(RecommendationResponse::getDate)
                .containsExactly("2026-09-01", "2026-09-02", "2026-09-03");
        assertThat(trend).extracting(RecommendationResponse::getFinalScore)
                .containsExactly(70.0, 75.0, 80.0);
    }

    @Test
    @DisplayName("존재하지 않는 스냅샷 id로 추이를 조회하면 예외가 발생한다")
    void getTrend_throws_when_snapshot_not_found() {
        when(performanceSnapshotRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTrend(999L))
                .isInstanceOf(NoSuchElementException.class);

        verifyNoInteractions(recommendationRepository);
    }
}
