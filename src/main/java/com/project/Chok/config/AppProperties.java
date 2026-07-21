package com.project.Chok.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "chok")
public class AppProperties {

    private Anthropic anthropic = new Anthropic();
    private Analysis analysis = new Analysis();
    private Collector collector = new Collector();
    private Scheduler scheduler = new Scheduler();
    private Model model = new Model();
    private PythonCollector pythonCollector = new PythonCollector();

    public PythonCollector getPythonCollector() {
        return pythonCollector;
    }

    public void setPythonCollector(PythonCollector pythonCollector) {
        this.pythonCollector = pythonCollector;
    }

    public Anthropic getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(Anthropic anthropic) {
        this.anthropic = anthropic;
    }

    public Analysis getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Analysis analysis) {
        this.analysis = analysis;
    }

    public Collector getCollector() {
        return collector;
    }

    public void setCollector(Collector collector) {
        this.collector = collector;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    // ─────────────────────────────────────────
    // chok.anthropic.*
    // ─────────────────────────────────────────
    public static class Anthropic {
        private String apiKey;
        private String model;
        private String baseUrl;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    // ─────────────────────────────────────────
    // chok.analysis.*
    // ─────────────────────────────────────────
    public static class Analysis {
        private double weightTechnical;
        private double weightSentiment;
        private int newsPerStock;
        private int newsLookbackDays;
        private int parallelism = 5;

        public double getWeightTechnical() { return weightTechnical; }
        public void setWeightTechnical(double weightTechnical) { this.weightTechnical = weightTechnical; }

        public double getWeightSentiment() { return weightSentiment; }
        public void setWeightSentiment(double weightSentiment) { this.weightSentiment = weightSentiment; }

        public int getNewsPerStock() { return newsPerStock; }
        public void setNewsPerStock(int newsPerStock) { this.newsPerStock = newsPerStock; }

        public int getNewsLookbackDays() { return newsLookbackDays; }
        public void setNewsLookbackDays(int newsLookbackDays) { this.newsLookbackDays = newsLookbackDays; }

        public int getParallelism() { return parallelism; }
        public void setParallelism(int parallelism) { this.parallelism = parallelism; }
    }

    // ─────────────────────────────────────────
    // chok.collector.*
    // ─────────────────────────────────────────
    public static class Collector {
        private int timeoutSeconds = 120;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    // ─────────────────────────────────────────
    // chok.scheduler.*
    // ─────────────────────────────────────────
    public static class Scheduler {
        private boolean enabled = false;
        private String cron = "0 30 18 * * MON-FRI";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }

    // ─────────────────────────────────────────
    // chok.model.* - rise-probability logistic regression model
    // ─────────────────────────────────────────
    public static class Model {
        private boolean retrainEnabled = false;
        private String retrainCron = "0 0 20 * * SUN";
        private int timeoutSeconds = 300;
        private int minSamples = 500;

        public boolean isRetrainEnabled() { return retrainEnabled; }
        public void setRetrainEnabled(boolean retrainEnabled) { this.retrainEnabled = retrainEnabled; }

        public String getRetrainCron() { return retrainCron; }
        public void setRetrainCron(String retrainCron) { this.retrainCron = retrainCron; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }
    }

    // ─────────────────────────────────────────
    // chok.python-collector.* - python-collector 폴더 위치 하나만 지정하면
    // 파이썬 실행파일/스크립트/모델파일 경로는 PythonEnvironment가 전부 계산해줌
    // ─────────────────────────────────────────
    public static class PythonCollector {
        private String baseDir = "python-collector";

        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    }
}