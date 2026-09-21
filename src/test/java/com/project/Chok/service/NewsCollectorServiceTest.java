package com.project.Chok.service;

import com.project.Chok.dto.NewsArticle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsCollectorServiceTest {

    @Test
    void fetchRecentNewsReturnsArticlesFromLiveApi() {
        NewsCollectorService service = new NewsCollectorService();
        List<NewsArticle> articles = service.fetchRecentNews("005930", 5);

        assertFalse(articles.isEmpty(), "삼성전자 뉴스가 하나도 조회되지 않음 (API 응답 형식 변경 가능성)");
        assertTrue(articles.size() <= 5);
        articles.forEach(a -> {
            assertFalse(a.getHeadline().isEmpty());
            assertFalse(a.getUrl().isEmpty());
        });
    }
}
