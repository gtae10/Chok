package com.project.Chok.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "chok")
public class AppProperties {

    private Anthropic anthropic = new Anthropic();
    private Analysis analysis = new Analysis();
    private Collector collector = new Collector();

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

        public double getWeightTechnical() { return weightTechnical; }
        public void setWeightTechnical(double weightTechnical) { this.weightTechnical = weightTechnical; }

        public double getWeightSentiment() { return weightSentiment; }
        public void setWeightSentiment(double weightSentiment) { this.weightSentiment = weightSentiment; }

        public int getNewsPerStock() { return newsPerStock; }
        public void setNewsPerStock(int newsPerStock) { this.newsPerStock = newsPerStock; }

        public int getNewsLookbackDays() { return newsLookbackDays; }
        public void setNewsLookbackDays(int newsLookbackDays) { this.newsLookbackDays = newsLookbackDays; }
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
}