package com.project.Chok.service;

import com.project.Chok.dto.NewsArticle;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class NewsCollectorService {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectorService.class);

    private static final String NEWS_URL_TEMPLATE =
            "https://finance.naver.com/item/news_news.naver?code=%s&page=1";
    private static final DateTimeFormatter NAVER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy.MM.dd");

    public List<NewsArticle> fetchRecentNews(String ticker, int maxCount) {
        List<NewsArticle> articles = new ArrayList<>();
        String url = String.format(NEWS_URL_TEMPLATE, ticker);

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com/")
                    .timeout(7000)
                    .get();

            Elements rows = doc.select("table.type5 tr");

            for (Element row : rows) {
                if (articles.size() >= maxCount) break;

                Element titleEl = row.selectFirst("td.title a");
                Element dateEl = row.selectFirst("td.date");
                if (titleEl == null || dateEl == null) continue;

                String headline = titleEl.text().trim();
                String href = titleEl.attr("href");
                String fullUrl = href.startsWith("http")
                        ? href
                        : "https://finance.naver.com" + href;

                LocalDate date;
                try {
                    date = LocalDate.parse(dateEl.text().trim(), NAVER_DATE_FORMAT);
                } catch (Exception e) {
                    date = LocalDate.now();
                }

                if (headline.isEmpty()) continue;

                articles.add(new NewsArticle(headline, fullUrl, date));
            }

        } catch (Exception e) {
            log.error("뉴스 수집 실패 (ticker={}): {}", ticker, e.getMessage());
        }

        return articles;
    }
}
