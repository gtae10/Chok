package com.project.Chok.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.config.AppProperties;
import com.project.Chok.config.PythonEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * python-collector/train_model.py 가 학습해서 내보낸 로지스틱 회귀 계수(JSON)를 읽어
 * sigmoid(w·((x-mean)/std) + b) 로 상승확률을 계산한다.
 * 모델 파일이 아직 없거나(데이터 부족으로 학습 전) 읽기 실패하면 null을 반환하고,
 * 호출부(TechnicalAnalysisService)가 휴리스틱 계산으로 대체한다.
 *
 * 기본 배포 모델(rise_model.json, FORWARD_DAYS 하나)과는 별개로, 여러 예측기간별로
 * 저장된 모델(rise_model_30d.json 등)도 함께 로드해서 종목별 "유력 구간"을 판단한다.
 */
@Service
public class RiseProbabilityService {

    private static final Logger log = LoggerFactory.getLogger(RiseProbabilityService.class);

    public static final List<String> FEATURE_NAMES = List.of(
            "priceVsMa5", "ma5VsMa20", "ma20VsMa60", "rsiNorm",
            "macdHistNorm", "bbPercentB", "logVolumeRatio", "momentum90"
    );

    // 영업일 -> 대략적인 달력일 근사치 (주말 감안, 완벽한 거래일 캘린더는 불필요)
    private static final double BUSINESS_TO_CALENDAR_DAYS = 7.0 / 5.0;

    private final PythonEnvironment pythonEnvironment;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CachedEntry> modelCache = new ConcurrentHashMap<>();

    public RiseProbabilityService(PythonEnvironment pythonEnvironment, AppProperties appProperties) {
        this.pythonEnvironment = pythonEnvironment;
        this.appProperties = appProperties;
    }

    /** 모델이 있으면 표준화 + 시그모이드로 확률(0~100)을 계산, 없으면 null */
    public Double predict(double[] features) {
        ModelData model = loadModelIfAvailable();
        if (model == null) return null;
        Double probability = computeProbability(model, features);
        if (probability == null) {
            log.warn("모델 특징 개수({})와 계산된 특징 개수({})가 다름 - 모델 무시", model.weights.length, features.length);
        }
        return probability;
    }

    public boolean isModelAvailable() {
        return loadModelIfAvailable() != null;
    }

    /** 모델이 사용됐다면 그 모델이 학습된 예측기간(영업일), 없으면 null */
    public Integer getModelForwardDays() {
        ModelData model = loadModelIfAvailable();
        return model == null ? null : model.forwardDays;
    }

    public ModelMeta getMeta() {
        ModelData model = loadModelIfAvailable();
        if (model == null) return null;
        return new ModelMeta(model.trainedAt, model.sampleCount, model.holdoutAccuracy, model.forwardDays);
    }

    /**
     * 여러 기간별 모델 중 holdoutAuc가 chok.model.min-auc-for-highlight 이상인 후보들만 놓고,
     * 그 중 상승확률이 가장 높은 기간 하나를 고른다. 후보가 하나도 없으면 null
     * (다중비교 문제 방지 - AUC 기준 미달 기간을 억지로 보여주지 않음).
     */
    public NotableHorizon findNotableHorizon(double[] features) {
        return findExtremeHorizon(features, true);
    }

    /**
     * findNotableHorizon()의 대칭 버전 - 같은 AUC 기준을 통과한 후보들 중 이번엔 확률이
     * 가장 낮은(하락 쪽으로 가장 확신 있는) 기간을 고른다. 별도 모델을 새로 학습하지 않고
     * 같은 기간별 모델들을 재사용한다 - 상승확률이 낮다는 것 자체가 곧 하락 쪽 신호이므로.
     */
    public NotableHorizon findNotableFallHorizon(double[] features) {
        return findExtremeHorizon(features, false);
    }

    private NotableHorizon findExtremeHorizon(double[] features, boolean highest) {
        double minAuc = appProperties.getModel().getMinAucForHighlight();

        NotableHorizon best = null;
        for (ModelData model : loadAllHorizonModels()) {
            if (model.holdoutAuc == null || model.holdoutAuc < minAuc) continue;

            Double probability = computeProbability(model, features);
            if (probability == null) continue;

            boolean better = best == null || (highest ? probability > best.probability() : probability < best.probability());
            if (better) {
                long calendarDays = Math.round(model.forwardDays * BUSINESS_TO_CALENDAR_DAYS);
                LocalDate approxDate = LocalDate.now().plusDays(calendarDays);
                best = new NotableHorizon(model.forwardDays, probability, approxDate);
            }
        }
        return best;
    }

    private Double computeProbability(ModelData model, double[] features) {
        if (model.weights.length != features.length) return null;

        double z = model.bias;
        for (int i = 0; i < features.length; i++) {
            double std = model.featureStds[i] == 0 ? 1.0 : model.featureStds[i];
            double standardized = (features[i] - model.featureMeans[i]) / std;
            z += model.weights[i] * standardized;
        }
        double probability = 1.0 / (1.0 + Math.exp(-z));
        return probability * 100.0;
    }

    private ModelData loadModelIfAvailable() {
        return loadModelFile(new File(pythonEnvironment.modelOutputPath()));
    }

    /** 기본 배포 모델(rise_model.json) + 기간별로 따로 저장된 모델 파일들을 전부 로드한다. */
    private List<ModelData> loadAllHorizonModels() {
        List<ModelData> models = new ArrayList<>();
        ModelData deploy = loadModelIfAvailable();
        if (deploy != null) models.add(deploy);

        for (File file : pythonEnvironment.horizonModelFiles()) {
            ModelData data = loadModelFile(file);
            if (data != null) models.add(data);
        }
        return models;
    }

    private ModelData loadModelFile(File file) {
        if (!file.exists()) return null;

        long lastModified = file.lastModified();
        CachedEntry cached = modelCache.get(file.getAbsolutePath());
        if (cached != null && cached.lastModified == lastModified) {
            return cached.data;
        }

        try {
            Map<String, Object> raw = objectMapper.readValue(file, Map.class);
            ModelData data = new ModelData();
            data.weights = toDoubleArray((List<?>) raw.get("weights"));
            data.featureMeans = toDoubleArray((List<?>) raw.get("featureMeans"));
            data.featureStds = toDoubleArray((List<?>) raw.get("featureStds"));
            data.bias = ((Number) raw.get("bias")).doubleValue();
            data.trainedAt = (String) raw.get("trainedAt");
            data.sampleCount = raw.get("sampleCount") == null ? 0 : ((Number) raw.get("sampleCount")).intValue();
            data.holdoutAccuracy = raw.get("holdoutAccuracy") == null ? null : ((Number) raw.get("holdoutAccuracy")).doubleValue();
            data.holdoutAuc = raw.get("holdoutAuc") == null ? null : ((Number) raw.get("holdoutAuc")).doubleValue();
            data.forwardDays = raw.get("forwardDays") == null ? 5 : ((Number) raw.get("forwardDays")).intValue();

            modelCache.put(file.getAbsolutePath(), new CachedEntry(lastModified, data));
            log.info("상승확률 모델 로드 완료 (파일={}, 기간={}일, 학습일={}, 샘플수={}, AUC={})",
                    file.getName(), data.forwardDays, data.trainedAt, data.sampleCount, data.holdoutAuc);
            return data;
        } catch (IOException | RuntimeException e) {
            log.warn("상승확률 모델 파일 읽기 실패 ({}): {}", file.getAbsolutePath(), e.getMessage());
            return null;
        }
    }

    private double[] toDoubleArray(List<?> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = ((Number) list.get(i)).doubleValue();
        return arr;
    }

    private static class CachedEntry {
        final long lastModified;
        final ModelData data;

        CachedEntry(long lastModified, ModelData data) {
            this.lastModified = lastModified;
            this.data = data;
        }
    }

    private static class ModelData {
        double[] weights;
        double[] featureMeans;
        double[] featureStds;
        double bias;
        String trainedAt;
        int sampleCount;
        Double holdoutAccuracy;
        Double holdoutAuc;
        int forwardDays;
    }

    public record ModelMeta(String trainedAt, int sampleCount, Double holdoutAccuracy, int forwardDays) {}

    /** 종목별로 유의미하게 유력해 보이는 예측기간 - AUC 기준을 통과한 기간 중 확률이 가장 높은 것. */
    public record NotableHorizon(int horizonDays, double probability, LocalDate approxDate) {}
}
