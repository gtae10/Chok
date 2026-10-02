package com.project.Chok;

import com.project.Chok.service.NewsRelevance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsRelevanceTest {

    @Test
    @DisplayName("제목이나 본문 앞부분에 종목명이 있으면 통과, 없으면 거른다")
    void requiresNameInTitleOrBody() {
        assertThat(NewsRelevance.isRelevant("삼성전자", "삼성전자, 3분기 실적 발표", null)).isTrue();
        assertThat(NewsRelevance.isRelevant("KB금융", "브로커리지 업고 영업익 1조 돌파", "KB금융이 상반기...")).isTrue();
        assertThat(NewsRelevance.isRelevant("NAVER", "전통시장, 가을엔 夜해집니다", "1일 용산 핫플 신흥시장부터 릴레이 야장")).isFalse();
    }

    @Test
    @DisplayName("'※' 뒤의 안내 문구에 나온 종목명은 기사 내용으로 치지 않는다")
    void ignoresNoticeAfterAsterisk() {
        String body = "전통시장 축제가 열린다. ※ 기사 전문은 매경플러스에서 확인할 수 있습니다. 네이버에서 검색하세요.";
        assertThat(NewsRelevance.isRelevant("NAVER", "전통시장 축제", body)).isFalse();
    }

    @Test
    @DisplayName("우선주·홀딩스·지주 접미사와 약칭을 별칭으로 인정한다")
    void matchesAliases() {
        assertThat(NewsRelevance.isRelevant("삼성전자우", "삼성전자 주가 상승", null)).isTrue();
        assertThat(NewsRelevance.isRelevant("NAVER", "네이버, AI 검색 개편", null)).isTrue();
        assertThat(NewsRelevance.isRelevant("SK하이닉스", "삼전닉스 동반 약세, 하이닉스 4% 하락", null)).isTrue();
        assertThat(NewsRelevance.isRelevant("신한지주", "신한 기업대출 부실 증가", null)).isTrue();
    }

    @Test
    @DisplayName("종목명을 모르면 거르지 않는다")
    void passesWhenNameUnknown() {
        assertThat(NewsRelevance.isRelevant(null, "아무 제목", null)).isTrue();
    }
}
