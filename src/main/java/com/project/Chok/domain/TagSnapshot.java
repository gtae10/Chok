package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * 그날 화면에 붙은 태그 기록 (C1 태그 추적). 분석이 끝날 때 TagService가 그날치를 통째로 다시 쓴다.
 * 성과 집계(python-collector/evaluate_tags.py)는 이 표 + price_history로 태그별 시장 대비 수익률을 본다.
 */
@Entity
@Table(name = "tag_snapshots",
        uniqueConstraints = @UniqueConstraint(columnNames = {"snap_date", "ticker", "tag"}))
public class TagSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snap_date", nullable = false)
    private LocalDate snapDate;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(nullable = false, length = 30)
    private String tag;

    // 태그 정의 버전 (docs/PROJECT_PLAN.md 4장). 정의를 바꾸면 올려서 이전 기록과 섞이지 않게 한다
    @Column(name = "tag_version", nullable = false, length = 10)
    private String tagVersion;

    protected TagSnapshot() {}

    public TagSnapshot(LocalDate snapDate, String ticker, String tag, String tagVersion) {
        this.snapDate = snapDate;
        this.ticker = ticker;
        this.tag = tag;
        this.tagVersion = tagVersion;
    }

    public LocalDate getSnapDate() { return snapDate; }
    public String getTicker() { return ticker; }
    public String getTag() { return tag; }
    public String getTagVersion() { return tagVersion; }
}
