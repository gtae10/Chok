package com.project.Chok;

import com.project.Chok.controller.RecommendationController;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.dto.RecommendationResponse;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.service.AnalysisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecommendationControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final LocalDate PREV = LocalDate.of(2026, 9, 29);

    private List<RecommendationResponse> fetch(boolean running) {
        RecommendationRepository repo = mock(RecommendationRepository.class);
        when(repo.findLatestRecDate()).thenReturn(TODAY);
        when(repo.findLatestRecDateBefore(TODAY)).thenReturn(PREV);
        when(repo.countByRecDate(TODAY)).thenReturn(51L);
        when(repo.countByRecDate(PREV)).thenReturn(100L);
        when(repo.findByRecDateOrderByFinalScoreDesc(TODAY)).thenReturn(List.of(rec(TODAY)));
        when(repo.findByRecDateOrderByFinalScoreDesc(PREV)).thenReturn(List.of(rec(PREV)));

        AnalysisStatus status = new AnalysisStatus();
        if (running) status.tryStart("test");

        return new RecommendationController(repo, null, null, null, null, status, null, null, mock(com.project.Chok.service.TagService.class))
                .getRecommendations(null).getBody();
    }

    private static Recommendation rec(LocalDate date) {
        Recommendation r = new Recommendation();
        r.setRecDate(date);
        r.setRecommendation("HOLD");
        return r;
    }

    @Test
    @DisplayName("분석 도중 오늘 행이 직전 날짜보다 적으면 직전(완료된) 날짜를 보여준다")
    void showsPreviousCompleteDateWhileAnalysisRunning() {
        assertThat(fetch(true)).extracting(RecommendationResponse::getDate).containsExactly(PREV.toString());
    }

    @Test
    @DisplayName("분석이 끝났으면 최신 날짜를 그대로 보여준다")
    void showsLatestDateWhenIdle() {
        assertThat(fetch(false)).extracting(RecommendationResponse::getDate).containsExactly(TODAY.toString());
    }
}
