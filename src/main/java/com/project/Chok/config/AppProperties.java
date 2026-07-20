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
        private String pythonExecutable;
        private String scriptPath;
        private String workingDirectory;
        private int timeoutSeconds;

        public String getPythonExecutable() { return pythonExecutable; }
        public void setPythonExecutable(String pythonExecutable) { this.pythonExecutable = pythonExecutable; }

        public String getScriptPath() { return scriptPath; }
        public void setScriptPath(String scriptPath) { this.scriptPath = scriptPath; }

        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

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
        private String path = "python-collector/model/rise_model.json";
        private boolean retrainEnabled = false;
        private String retrainCron = "0 0 20 * * SUN";
        private String pythonExecutable;
        private String trainScriptPath;
        private String workingDirectory;
        private int timeoutSeconds = 300;
        private int minSamples = 500;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public boolean isRetrainEnabled() { return retrainEnabled; }
        public void setRetrainEnabled(boolean retrainEnabled) { this.retrainEnabled = retrainEnabled; }

        public String getRetrainCron() { return retrainCron; }
        public void setRetrainCron(String retrainCron) { this.retrainCron = retrainCron; }

        public String getPythonExecutable() { return pythonExecutable; }
        public void setPythonExecutable(String pythonExecutable) { this.pythonExecutable = pythonExecutable; }

        public String getTrainScriptPath() { return trainScriptPath; }
        public void setTrainScriptPath(String trainScriptPath) { this.trainScriptPath = trainScriptPath; }

        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }
    }
}