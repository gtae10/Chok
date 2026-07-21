package com.project.Chok.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.config.PythonEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * python-collector/train_model.py 가 학습해서 내보낸 로지스틱 회귀 계수(JSON)를 읽어
 * sigmoid(w·((x-mean)/std) + b) 로 상승확률을 계산한다.
 * 모델 파일이 아직 없거나(데이터 부족으로 학습 전) 읽기 실패하면 null을 반환하고,
 * 호출부(TechnicalAnalysisService)가 휴리스틱 계산으로 대체한다.
 */
@Service
public class RiseProbabilityService {

    private static final Logger log = LoggerFactory.getLogger(RiseProbabilityService.class);

    public static final List<String> FEATURE_NAMES = List.of(
            "priceVsMa5", "ma5VsMa20", "ma20VsMa60", "rsiNorm",
            "macdHistNorm", "bbPercentB", "logVolumeRatio"
    );

    private final PythonEnvironment pythonEnvironment;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile ModelData cachedModel;
    private volatile long cachedModelLastModified = -1;

    public RiseProbabilityService(PythonEnvironment pythonEnvironment) {
        this.pythonEnvironment = pythonEnvironment;
    }

    /** 모델이 있으면 표준화 + 시그모이드로 확률(0~100)을 계산, 없으면 null */
    public Double predict(double[] features) {
        ModelData model = loadModelIfAvailable();
        if (model == null) return null;
        if (model.weights.length != features.length) {
            log.warn("모델 특징 개수({})와 계산된 특징 개수({})가 다름 - 모델 무시", model.weights.length, features.length);
            return null;
        }

        double z = model.bias;
        for (int i = 0; i < features.length; i++) {
            double std = model.featureStds[i] == 0 ? 1.0 : model.featureStds[i];
            double standardized = (features[i] - model.featureMeans[i]) / std;
            z += model.weights[i] * standardized;
        }
        double probability = 1.0 / (1.0 + Math.exp(-z));
        return probability * 100.0;
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

    private ModelData loadModelIfAvailable() {
        String path = pythonEnvironment.modelOutputPath();
        if (path == null || path.isBlank()) return null;

        File file = new File(path);
        if (!file.exists()) return null;

        long lastModified = file.lastModified();
        if (cachedModel != null && lastModified == cachedModelLastModified) {
            return cachedModel;
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
            data.forwardDays = raw.get("forwardDays") == null ? 5 : ((Number) raw.get("forwardDays")).intValue();

            cachedModel = data;
            cachedModelLastModified = lastModified;
            log.info("상승확률 모델 로드 완료 (학습일={}, 샘플수={}, 정확도={})",
                    data.trainedAt, data.sampleCount, data.holdoutAccuracy);
            return data;
        } catch (IOException | RuntimeException e) {
            log.warn("상승확률 모델 파일 읽기 실패 ({}): {}", path, e.getMessage());
            return null;
        }
    }

    private double[] toDoubleArray(List<?> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = ((Number) list.get(i)).doubleValue();
        return arr;
    }

    private static class ModelData {
        double[] weights;
        double[] featureMeans;
        double[] featureStds;
        double bias;
        String trainedAt;
        int sampleCount;
        Double holdoutAccuracy;
        int forwardDays;
    }

    public record ModelMeta(String trainedAt, int sampleCount, Double holdoutAccuracy, int forwardDays) {}
}
