package com.project.Chok.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "chok")
public class AppProperties {

    private OpenAi openai = new OpenAi();
    private Anthropic anthropic = new Anthropic();
    private Sentiment sentiment = new Sentiment();
    private News news = new News();
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

    public OpenAi getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAi openai) {
        this.openai = openai;
    }

    public Anthropic getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(Anthropic anthropic) {
        this.anthropic = anthropic;
    }

    public Sentiment getSentiment() {
        return sentiment;
    }

    public void setSentiment(Sentiment sentiment) {
        this.sentiment = sentiment;
    }

    public News getNews() {
        return news;
    }

    public void setNews(News news) {
        this.news = news;
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
    // chok.openai.*
    // ─────────────────────────────────────────
    public static class OpenAi {
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
    // chok.anthropic.* - Claude용 대체 감성분석 프로바이더
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
    // chok.sentiment.* - 감성분석 프로바이더 선택 및 동시성 상한
    // (종목 병렬도 chok.analysis.parallelism과는 별개의 자원이므로 분리해서 관리)
    // ─────────────────────────────────────────
    public static class Sentiment {
        private String provider = "openai";
        private int maxConcurrentCalls = 3;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }
    }

    // ─────────────────────────────────────────
    // chok.news.* - 뉴스 수집/저장 단계의 유사 헤드라인(같은 사건, 다른 언론사) 중복 제거
    // ─────────────────────────────────────────
    public static class News {
        private double dedupSimilarityThreshold = 0.3;

        public double getDedupSimilarityThreshold() { return dedupSimilarityThreshold; }
        public void setDedupSimilarityThreshold(double dedupSimilarityThreshold) { this.dedupSimilarityThreshold = dedupSimilarityThreshold; }
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
        // 종목별 "유력 구간" 후보로 삼을 최소 holdout AUC. 이 기준 미달인 기간은
        // 다중비교 문제(여러 기간 중 우연히 높게 나온 것)를 "발견"처럼 보여주지 않기 위해 후보에서 제외.
        private double minAucForHighlight = 0.55;

        public boolean isRetrainEnabled() { return retrainEnabled; }
        public void setRetrainEnabled(boolean retrainEnabled) { this.retrainEnabled = retrainEnabled; }

        public String getRetrainCron() { return retrainCron; }
        public void setRetrainCron(String retrainCron) { this.retrainCron = retrainCron; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }

        public double getMinAucForHighlight() { return minAucForHighlight; }
        public void setMinAucForHighlight(double minAucForHighlight) { this.minAucForHighlight = minAucForHighlight; }
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