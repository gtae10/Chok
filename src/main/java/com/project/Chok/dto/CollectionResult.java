package com.project.Chok.dto;

public class CollectionResult {

    private boolean success;
    private String output;
    private String errorMessage;

    public CollectionResult(boolean success, String output, String errorMessage) {
        this.success = success;
        this.output = output;
        this.errorMessage = errorMessage;
    }

    public static CollectionResult success(String output) {
        return new CollectionResult(true, output, null);
    }

    public static CollectionResult failure(String errorMessage) {
        return new CollectionResult(false, null, errorMessage);
    }

    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getErrorMessage() { return errorMessage; }
}