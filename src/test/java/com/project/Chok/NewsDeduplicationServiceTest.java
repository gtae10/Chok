package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.NewsSentiment;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.repository.NewsSentimentRepository;
import com.project.Chok.service.NewsDeduplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsDeduplicationServiceTest {

    @Mock private NewsSentimentRepository newsSentimentRepository;

    private AppProperties appProperties;
    private NewsDeduplicationService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getNews().setDedupSimilarityThreshold(0.5);
        AppProperties.Analysis analysis = new AppProperties.Analysis();
        analysis.setNewsLookbackDays(3);
        appProperties.setAnalysis(analysis);

        service = new NewsDeduplicationService(newsSentimentRepository, appProperties);
    }

    private NewsArticle article(String headline) {
        return new NewsArticle(headline, "http://test.com", LocalDate.now());
    }

    private NewsSentiment existing(String headline) {
        NewsSentiment n = new NewsSentiment();
        n.setHeadline(headline);
        return n;
    }

    @Test
    @DisplayName("같은 사건을 토씨만 다르게 보도한 헤드라인은 중복으로 걸러진다")
    void filters_out_paraphrased_same_event_headline() {
        when(newsSentimentRepository.findByTickerSince(anyString(), any())).thenReturn(List.of(
                existing("한화에어로, 크로아티아에 천무 수출 6,800억원 규모 계약")
        ));

        List<NewsArticle> candidates = List.of(
                article("한화에어로스페이스, 크로아티아와 6800억원 규모 천무 수출계약 체결")
        );

        List<NewsArticle> result = service.filterSimilarDuplicates("012450", candidates);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("실제로 다른 사건을 다루는 헤드라인은 중복으로 걸러지지 않는다")
    void keeps_headlines_about_genuinely_different_events() {
        when(newsSentimentRepository.findByTickerSince(anyString(), any())).thenReturn(List.of(
                existing("한화에어로, 크로아티아에 천무 수출 6,800억원 규모 계약")
        ));

        List<NewsArticle> candidates = List.of(
                article("한화에어로, 3분기 영업이익 전년 대비 40% 증가")
        );

        List<NewsArticle> result = service.filterSimilarDuplicates("012450", candidates);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("기존 저장된 뉴스가 없으면 전부 그대로 통과한다")
    void keeps_all_when_no_existing_history() {
        when(newsSentimentRepository.findByTickerSince(anyString(), any())).thenReturn(List.of());

        List<NewsArticle> candidates = List.of(
                article("삼성전자 3분기 영업이익 발표"),
                article("삼성전자 갤럭시 신제품 공개")
        );

        List<NewsArticle> result = service.filterSimilarDuplicates("005930", candidates);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("같은 수집 배치 안에서도 유사 헤드라인이면 먼저 나온 것만 남긴다")
    void filters_duplicates_within_same_batch() {
        when(newsSentimentRepository.findByTickerSince(anyString(), any())).thenReturn(List.of());

        List<NewsArticle> candidates = List.of(
                article("한화에어로, 크로아티아에 천무 수출 6,800억원 규모 계약"),
                article("한화에어로스페이스, 크로아티아와 6800억원 규모 천무 수출계약 체결"),
                article("한화에어로, 3분기 영업이익 전년 대비 40% 증가")
        );

        List<NewsArticle> result = service.filterSimilarDuplicates("012450", candidates);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getHeadline()).isEqualTo("한화에어로, 크로아티아에 천무 수출 6,800억원 규모 계약");
        assertThat(result.get(1).getHeadline()).isEqualTo("한화에어로, 3분기 영업이익 전년 대비 40% 증가");
    }

    @Test
    @DisplayName("빈 후보 목록은 그대로 빈 목록을 반환한다")
    void returns_empty_for_empty_candidates() {
        List<NewsArticle> result = service.filterSimilarDuplicates("005930", List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("jaccardSimilarity는 완전히 동일한 문자열에 대해 1.0을 반환한다")
    void jaccardSimilarity_identical_strings_returns_one() {
        assertThat(NewsDeduplicationService.jaccardSimilarity("가나다라", "가나다라")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("jaccardSimilarity는 공통 부분이 전혀 없으면 0.0을 반환한다")
    void jaccardSimilarity_completely_different_strings_returns_zero() {
        assertThat(NewsDeduplicationService.jaccardSimilarity("가나다라", "ABCD")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("normalize는 공백/쉼표/마침표를 제거한다")
    void normalize_strips_whitespace_comma_period() {
        assertThat(NewsDeduplicationService.normalize("6,800억원 규모.")).isEqualTo("6800억원규모");
    }
}
