package com.project.Chok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.config.AppProperties;
import com.project.Chok.config.PythonEnvironment;
import com.project.Chok.service.RiseProbabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "유력 구간" 판단 로직의 통계적 안전장치를 검증한다:
 * - holdoutAuc가 기준(min-auc-for-highlight) 미달인 기간은 후보에서 제외
 * - 후보가 하나도 없으면 null (다중비교 문제 방지 - 억지로 아무 기간이나 보여주지 않음)
 * - 후보가 여럿이면 확률이 가장 높은 기간을 선택
 */
class RiseProbabilityServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AppProperties appProperties;
    private RiseProbabilityService service;
    private final double[] features = new double[8]; // weights=0으로 만들 것이므로 값은 무관

    @BeforeEach
    void setUp() throws IOException {
        appProperties = new AppProperties();
        appProperties.getPythonCollector().setBaseDir(tempDir.toAbsolutePath().toString());
        new File(tempDir.toFile(), "model").mkdirs();

        PythonEnvironment env = new PythonEnvironment(appProperties);
        service = new RiseProbabilityService(env, appProperties);
    }

    @Test
    @DisplayName("모든 기간의 AUC가 기준 미달이면 유력 구간 없음(null)")
    void no_candidate_when_all_auc_below_threshold() throws IOException {
        writeModel("rise_model_30d.json", 30, 0.50, 10.0); // bias 커도 AUC가 낮으면 후보 제외
        writeModel("rise_model_60d.json", 60, 0.52, 10.0);

        assertThat(service.findNotableHorizon(features)).isNull();
    }

    @Test
    @DisplayName("모델 파일이 하나도 없으면 유력 구간 없음(null)")
    void no_candidate_when_no_models() {
        assertThat(service.findNotableHorizon(features)).isNull();
    }

    @Test
    @DisplayName("AUC 기준을 넘는 기간이 하나면 그 기간을 유력 구간으로 선택")
    void picks_single_candidate_above_threshold() throws IOException {
        writeModel("rise_model_30d.json", 30, 0.50, 0.0);  // 후보 아님
        writeModel("rise_model_60d.json", 60, 0.60, 2.0);  // 후보 (AUC 0.60 >= 0.55)

        RiseProbabilityService.NotableHorizon result = service.findNotableHorizon(features);

        assertThat(result).isNotNull();
        assertThat(result.horizonDays()).isEqualTo(60);
        assertThat(result.probability()).isGreaterThan(50.0);
    }

    @Test
    @DisplayName("후보가 여럿이면 확률이 더 높은(AUC가 아니라) 기간을 선택")
    void picks_highest_probability_among_multiple_candidates() throws IOException {
        writeModel("rise_model_30d.json", 30, 0.70, 1.0);  // AUC는 더 높지만 확률은 낮음
        writeModel("rise_model_60d.json", 60, 0.56, 5.0);  // AUC는 낮지만 확률(=bias로 결정)은 더 높음

        RiseProbabilityService.NotableHorizon result = service.findNotableHorizon(features);

        assertThat(result).isNotNull();
        assertThat(result.horizonDays()).isEqualTo(60);
    }

    @Test
    @DisplayName("holdoutAuc 필드 자체가 없는(null) 모델은 후보에서 제외")
    void excludes_model_without_holdout_auc() throws IOException {
        Map<String, Object> model = baseModelFields(30, 5.0);
        model.remove("holdoutAuc");
        objectMapper.writeValue(new File(tempDir.toFile(), "model/rise_model_30d.json"), model);

        assertThat(service.findNotableHorizon(features)).isNull();
    }

    private void writeModel(String filename, int forwardDays, double holdoutAuc, double bias) throws IOException {
        Map<String, Object> model = baseModelFields(forwardDays, bias);
        model.put("holdoutAuc", holdoutAuc);
        objectMapper.writeValue(new File(tempDir.toFile(), "model/" + filename), model);
    }

    /** weights=0, featureMeans=0, featureStds=1로 고정해서 z = bias만 남게 만들어, bias로 확률을 직접 통제한다. */
    private Map<String, Object> baseModelFields(int forwardDays, double bias) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("trainedAt", "2026-01-01T00:00:00Z");
        model.put("sampleCount", 1000);
        model.put("forwardDays", forwardDays);
        model.put("featureMeans", List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        model.put("featureStds", List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0));
        model.put("weights", List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        model.put("bias", bias);
        model.put("holdoutAccuracy", 0.55);
        return model;
    }
}
