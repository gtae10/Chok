package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.repository.NewsSentimentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 여러 언론사가 같은 사건을 토씨만 다르게 보도한 헤드라인을 걸러낸다. 형태소 분석기 없이도
 * 어순·조사 차이에 비교적 강건한 문자 2-gram Jaccard 유사도를 쓴다 - 완벽한 정규화보다는
 * "같은 사건이면 확실히 잡히는" 보수적인 근사치가 목표.
 *
 * LLM 호출(배치 구성) 이전 단계에서 걸러야 토큰도 아낄 수 있으므로, 뉴스 수집 직후
 * RecommendationService에서 호출한다.
 */
@Service
public class NewsDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(NewsDeduplicationService.class);
    private static final int NGRAM_SIZE = 2;

    private final NewsSentimentRepository newsSentimentRepository;
    private final AppProperties appProperties;

    public NewsDeduplicationService(NewsSentimentRepository newsSentimentRepository, AppProperties appProperties) {
        this.newsSentimentRepository = newsSentimentRepository;
        this.appProperties = appProperties;
    }

    /**
     * candidates 중, (1) 최근 lookback 기간 내 이미 저장된 헤드라인과 유사하거나
     * (2) candidates 안에서 이미 채택된 다른 헤드라인과 유사한 것을 제외한다.
     * 같은 사건으로 판단되는 여러 후보 중에는 먼저 수집된(리스트 앞쪽) 것만 남는다.
     */
    public List<NewsArticle> filterSimilarDuplicates(String ticker, List<NewsArticle> candidates) {
        if (candidates.isEmpty()) return candidates;

        double threshold = appProperties.getNews().getDedupSimilarityThreshold();
        int lookbackDays = appProperties.getAnalysis().getNewsLookbackDays();
        LocalDate fromDate = LocalDate.now().minusDays(Math.max(lookbackDays, 1));

        List<String> existingNormalized = newsSentimentRepository.findByTickerSince(ticker, fromDate).stream()
                .map(n -> normalize(n.getHeadline()))
                .toList();

        List<String> keptNormalized = new ArrayList<>();
        List<NewsArticle> kept = new ArrayList<>();

        for (NewsArticle candidate : candidates) {
            String normalized = normalize(candidate.getHeadline());
            boolean duplicate = isSimilarToAny(normalized, existingNormalized, threshold)
                    || isSimilarToAny(normalized, keptNormalized, threshold);

            if (duplicate) {
                log.debug("유사 헤드라인 중복 제외 (ticker={}): {}", ticker, candidate.getHeadline());
                continue;
            }
            kept.add(candidate);
            keptNormalized.add(normalized);
        }

        if (kept.size() < candidates.size()) {
            log.info("유사 헤드라인 중복 제외: 종목={}, {}건 → {}건", ticker, candidates.size(), kept.size());
        }

        return kept;
    }

    private boolean isSimilarToAny(String normalized, List<String> others, double threshold) {
        for (String other : others) {
            if (jaccardSimilarity(normalized, other) >= threshold) return true;
        }
        return false;
    }

    /** 공백/쉼표/마침표 제거 정도의 가벼운 정규화 - "6,800억원"과 "6800억원"이 비슷하게 비교되도록. */
    public static String normalize(String headline) {
        if (headline == null) return "";
        return headline.replaceAll("[\\s,.]", "").toLowerCase();
    }

    public static double jaccardSimilarity(String a, String b) {
        Set<String> gramsA = ngrams(a);
        Set<String> gramsB = ngrams(b);
        if (gramsA.isEmpty() && gramsB.isEmpty()) return a.equals(b) ? 1.0 : 0.0;

        Set<String> union = new HashSet<>(gramsA);
        union.addAll(gramsB);
        Set<String> intersection = new HashSet<>(gramsA);
        intersection.retainAll(gramsB);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> ngrams(String s) {
        Set<String> result = new HashSet<>();
        if (s.length() < NGRAM_SIZE) {
            if (!s.isEmpty()) result.add(s);
            return result;
        }
        for (int i = 0; i <= s.length() - NGRAM_SIZE; i++) {
            result.add(s.substring(i, i + NGRAM_SIZE));
        }
        return result;
    }
}
