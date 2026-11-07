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
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
    private final Map<String, Double> features = Map.of(); // weights=0으로 만들 것이므로 값은 무관(없으면 평균으로 대체)

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

    @Test
    @DisplayName("featureNames가 기존 8개 순서면 예전 위치 기반 계산과 확률이 똑같다")
    void name_based_matches_old_positional_path() throws IOException {
        double[] w = {0.3, -1.2, 0.8, 0.5, -0.7, 0.2, 0.4, -0.9};
        double[] m = {0.01, 0.02, -0.03, 0.1, 0.0, 0.5, 0.05, 0.12};
        double[] sd = {0.05, 0.04, 0.06, 0.3, 0.01, 0.25, 0.4, 0.3};
        double[] x = {0.02, -0.01, 0.04, 0.2, 0.003, 0.7, -0.1, 0.25};
        double bias = -0.15;
        writeDeployModel(RiseProbabilityService.FEATURE_NAMES, w, m, sd, bias);

        // 예전 computeProbability(double[]) 공식 그대로
        double z = bias;
        for (int i = 0; i < x.length; i++) z += w[i] * (x[i] - m[i]) / sd[i];
        double expected = 100.0 / (1.0 + Math.exp(-z));

        Map<String, Double> named = new HashMap<>();
        for (int i = 0; i < x.length; i++) named.put(RiseProbabilityService.FEATURE_NAMES.get(i), x[i]);
        named.put(RiseProbabilityService.SHORT_RATIO, 3.5); // 모델이 안 쓰는 특징은 영향 없음

        assertThat(service.predict(named)).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("featureNames가 없는 옛 모델 JSON도 기존 FEATURE_NAMES 순서로 계산")
    void falls_back_to_default_order_without_feature_names() throws IOException {
        writeDeployModel(null, new double[]{0, 0, 0, 0, 0, 0, 0, 2.0}, new double[8], ones(8), 0.0);

        double p = service.predict(Map.of("momentum90", 0.5)); // z = 2.0 * 0.5
        assertThat(p).isCloseTo(100.0 / (1.0 + Math.exp(-1.0)), within(1e-12));
    }

    @Test
    @DisplayName("벡터는 모델 JSON의 featureNames 순서로 조립된다")
    void assembles_vector_in_model_feature_order() throws IOException {
        writeDeployModel(List.of("shortRatio", "rsiNorm"), new double[]{1.0, 0.0}, new double[2], ones(2), 0.0);

        double p = service.predict(Map.of("rsiNorm", 5.0, "shortRatio", -2.0)); // z = 1.0 * shortRatio
        assertThat(p).isCloseTo(100.0 / (1.0 + Math.exp(2.0)), within(1e-12));
    }

    @Test
    @DisplayName("값이 없는 특징은 모델 평균(표준화 0)으로 대체 - 모델은 그대로 사용")
    void missing_feature_uses_model_mean() throws IOException {
        writeDeployModel(List.of("rsiNorm", "shortRatio"), new double[]{1.0, 5.0}, new double[]{0.0, 2.0}, ones(2), 0.0);

        double p = service.predict(Map.of("rsiNorm", 0.5)); // shortRatio 없음 -> z = 0.5
        assertThat(p).isCloseTo(100.0 / (1.0 + Math.exp(-0.5)), within(1e-12));
    }

    @Test
    @DisplayName("Java가 모르는 특징을 요구하는 모델은 건너뛴다(null)")
    void unknown_feature_skips_model() throws IOException {
        writeDeployModel(List.of("rsiNorm", "foreign20"), new double[]{1.0, 1.0}, new double[2], ones(2), 0.0);
        assertThat(service.predict(Map.of("rsiNorm", 0.5))).isNull();

        Map<String, Object> model = baseModelFields(60, 5.0);
        model.put("holdoutAuc", 0.70);
        model.put("featureNames", List.of("a", "b", "c", "d", "e", "f", "g", "h"));
        objectMapper.writeValue(new File(tempDir.toFile(), "model/rise_model_60d.json"), model);
        assertThat(service.findNotableHorizon(features)).isNull();
    }

    private void writeDeployModel(List<String> names, double[] w, double[] m, double[] sd, double bias) throws IOException {
        Map<String, Object> model = new LinkedHashMap<>();
        if (names != null) model.put("featureNames", names);
        model.put("weights", w);
        model.put("featureMeans", m);
        model.put("featureStds", sd);
        model.put("bias", bias);
        model.put("forwardDays", 5);
        objectMapper.writeValue(new File(tempDir.toFile(), "model/rise_model.json"), model);
    }

    private double[] ones(int n) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, 1.0);
        return a;
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
