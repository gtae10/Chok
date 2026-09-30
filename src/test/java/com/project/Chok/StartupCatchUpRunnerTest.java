package com.project.Chok;

import com.project.Chok.service.StartupCatchUpRunner;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StartupCatchUpRunnerTest {

    @Test
    void 평일에_오늘자_데이터가_부족하면_보정대상() {
        LocalDate weekday = LocalDate.of(2026, 9, 11); // 금요일
        assertThat(StartupCatchUpRunner.shouldCatchUp(weekday, true, 100, 1, 1)).isTrue();
    }

    @Test
    void 평일에_오늘자_데이터가_충분하면_건너뜀() {
        LocalDate weekday = LocalDate.of(2026, 9, 11); // 금요일
        assertThat(StartupCatchUpRunner.shouldCatchUp(weekday, true, 100, 100, 100)).isFalse();
    }

    @Test
    void 주말이면_데이터가_없어도_건너뜀() {
        LocalDate saturday = LocalDate.of(2026, 9, 12);
        assertThat(StartupCatchUpRunner.shouldCatchUp(saturday, true, 100, 0, 0)).isFalse();
    }

    @Test
    void 등록된_종목이_없으면_건너뜀() {
        LocalDate weekday = LocalDate.of(2026, 9, 11);
        assertThat(StartupCatchUpRunner.shouldCatchUp(weekday, true, 0, 0, 0)).isFalse();
    }

    @Test
    void 마감_전에는_오늘_가격이_없어도_오늘_분석이_있으면_건너뜀() {
        LocalDate weekday = LocalDate.of(2026, 9, 30);
        assertThat(StartupCatchUpRunner.shouldCatchUp(weekday, false, 100, 0, 100)).isFalse();
        assertThat(StartupCatchUpRunner.shouldCatchUp(weekday, false, 100, 0, 0)).isTrue();
    }
}
