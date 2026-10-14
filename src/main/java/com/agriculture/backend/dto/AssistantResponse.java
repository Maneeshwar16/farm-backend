package com.agriculture.backend.dto;
import java.util.LinkedHashMap;
import java.util.Map;

public class AssistantResponse {

    private String intent;
    private String message;
    private boolean requiresConfirmation;
    private Double amount;
    private String category;
    private String conversationId;
    private String nextStep;
    private Map<String, Object> data = new LinkedHashMap<>();

    public AssistantResponse() {
    }

    public AssistantResponse(
            String intent,
            String message,
            boolean requiresConfirmation,
            Double amount,
            String category
    ) {
        this.intent = intent;
        this.message = message;
        this.requiresConfirmation = requiresConfirmation;
        this.amount = amount;
        this.category = category;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getNextStep() {
        return nextStep;
    }

    public void setNextStep(String nextStep) {
        this.nextStep = nextStep;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null
                ? new LinkedHashMap<>()
                : data;
    }
}
