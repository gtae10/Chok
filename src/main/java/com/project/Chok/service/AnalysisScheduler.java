package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * chok.scheduler.enabled=true 일 때, chok.scheduler.cron 스케줄에 맞춰
 * 시세 수집 -> 전체 분석을 자동으로 실행한다.
 * 수동으로 "분석 실행" 버튼을 이미 누른 상태라면 AnalysisStatus.tryStart()가
 * false를 반환하므로 스케줄러가 겹쳐 도는 일은 없다 (한쪽이 양보).
 */
@Component
public class AnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisScheduler.class);

    private final DataCollectionService dataCollectionService;
    private final RecommendationService recommendationService;
    private final ModelTrainingService modelTrainingService;
    private final AnalysisStatus analysisStatus;
    private final AppProperties appProperties;

    public AnalysisScheduler(DataCollectionService dataCollectionService,
                              RecommendationService recommendationService,
                              ModelTrainingService modelTrainingService,
                              AnalysisStatus analysisStatus,
                              AppProperties appProperties) {
        this.dataCollectionService = dataCollectionService;
        this.recommendationService = recommendationService;
        this.modelTrainingService = modelTrainingService;
        this.analysisStatus = analysisStatus;
        this.appProperties = appProperties;
    }

    @Scheduled(cron = "${chok.scheduler.cron:0 30 18 * * MON-FRI}")
    public void runDaily() {
        if (!appProperties.getScheduler().isEnabled()) {
            return;
        }

        if (!analysisStatus.tryStart("scheduler")) {
            log.info("스케줄러: 이미 다른 분석이 진행 중이라 이번 회차는 건너뜀");
            return;
        }

        try {
            log.info("스케줄러: 자동 시세 수집 시작");
            dataCollectionService.runCollection();
            log.info("스케줄러: 자동 시세 수집 완료, 분석 시작");

            int processed = recommendationService.runFullAnalysis(analysisStatus);
            analysisStatus.markDone(processed);
            log.info("스케줄러: 자동 분석 완료 ({}개 종목)", processed);
        } catch (Exception e) {
            log.error("스케줄러: 자동 실행 중 오류", e);
            analysisStatus.markFailed(e.getMessage());
        }
    }

    /**
     * 상승확률 모델 주간 재학습. chok.model.retrain-enabled=true 일 때만 동작.
     * 데이터가 아직 부족하면 train_model.py가 조용히 스킵하므로(exitCode 0),
     * 이 경우는 에러 로그 없이 그냥 다음 주에 다시 시도된다.
     */
    @Scheduled(cron = "${chok.model.retrain-cron:0 0 20 * * SUN}")
    public void retrainModel() {
        if (!appProperties.getModel().isRetrainEnabled()) {
            return;
        }
        try {
            log.info("스케줄러: 상승확률 모델 재학습 시작");
            modelTrainingService.retrain();
            log.info("스케줄러: 상승확률 모델 재학습 완료");
        } catch (Exception e) {
            log.error("스케줄러: 모델 재학습 중 오류", e);
        }
    }
}
