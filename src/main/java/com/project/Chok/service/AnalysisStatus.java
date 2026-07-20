package com.project.Chok.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 분석/수집 작업이 오래 걸릴 수 있어(종목마다 순차 API 호출),
 * HTTP 요청 하나로 끝까지 기다리게 하지 않고
 * "시작됨" 응답을 즉시 보낸 뒤 이 상태를 폴링하는 방식으로 처리하기 위한 저장소.
 * 싱글 인스턴스 애플리케이션 기준이라 인메모리로 충분함 (별도 DB 불필요).
 */
@Component
public class AnalysisStatus {

    public enum Phase { IDLE, RUNNING, DONE, FAILED }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Phase phase = Phase.IDLE;
    private volatile int processedCount = 0;
    private volatile int totalCount = 0;
    private volatile String errorMessage = null;
    private volatile LocalDateTime startedAt = null;
    private volatile LocalDateTime finishedAt = null;
    private volatile String triggeredBy = null; // "manual" | "scheduler"

    public boolean tryStart(String triggeredBy) {
        if (running.compareAndSet(false, true)) {
            this.phase = Phase.RUNNING;
            this.processedCount = 0;
            this.totalCount = 0;
            this.errorMessage = null;
            this.startedAt = LocalDateTime.now();
            this.finishedAt = null;
            this.triggeredBy = triggeredBy;
            return true;
        }
        return false;
    }

    public void updateProgress(int processed, int total) {
        this.processedCount = processed;
        this.totalCount = total;
    }

    public void markDone(int processedCount) {
        this.processedCount = processedCount;
        this.phase = Phase.DONE;
        this.finishedAt = LocalDateTime.now();
        running.set(false);
    }

    public void markFailed(String message) {
        this.errorMessage = message;
        this.phase = Phase.FAILED;
        this.finishedAt = LocalDateTime.now();
        running.set(false);
    }

    public boolean isRunning() { return running.get(); }
    public Phase getPhase() { return phase; }
    public int getProcessedCount() { return processedCount; }
    public int getTotalCount() { return totalCount; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public String getTriggeredBy() { return triggeredBy; }
}
