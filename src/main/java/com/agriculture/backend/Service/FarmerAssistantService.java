
package com.agriculture.backend.Service;
import com.agriculture.backend.dto.AssistantResponse;
import com.agriculture.backend.entity.Crop;
import com.agriculture.backend.entity.FarmerExpense;
import com.agriculture.backend.repository.CropRepository;
import com.agriculture.backend.repository.FarmerExpenseRepository;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FarmerAssistantService {

    private final FarmerTextParser parser;
    private final FarmerExpenseRepository expenseRepository;
    private final CropRepository cropRepository;

    private final Map<String, Conversation> conversations =
            new ConcurrentHashMap<>();

    public FarmerAssistantService(
            FarmerTextParser parser,
            FarmerExpenseRepository expenseRepository,
            CropRepository cropRepository) {

        this.parser = parser;
        this.expenseRepository = expenseRepository;
        this.cropRepository = cropRepository;
    }

    public AssistantResponse processMessage(
            String message,
            Long farmerId,
            String conversationId,
            boolean confirmed,
            boolean cancelled) {

        if (farmerId == null || farmerId <= 0) {
            return response(
                    "UNKNOWN",
                    "Please log in as a farmer before continuing."
            );
        }

        Conversation conversation = null;

        if (conversationId != null && !conversationId.isBlank()) {
            conversation = conversations.get(conversationId);

            if (conversation != null
                    && !conversation.farmerId.equals(farmerId)) {
                return response(
                        "UNKNOWN",
                        "This conversation does not belong to your account."
                );
            }
        }

        if (cancelled) {
            if (conversationId != null) {
                conversations.remove(conversationId);
            }

            return response(
                    "CANCELLED",
                    "Action cancelled. Nothing was saved."
            );
        }

        // Continue an existing conversation.
        if (conversation != null) {
            if (confirmed) {
                return confirmAction(conversation, conversationId);
            }

            return continueConversation(
                    conversation,
                    message,
                    conversationId
            );
        }

        // Start a new conversation.
        AssistantResponse parsed = parser.parse(message);

        if ("CROP_CREATE".equals(parsed.getIntent())) {
            return startCropConversation(farmerId);
        }

        if ("EXPENSE".equals(parsed.getIntent())) {
            if (parsed.getAmount() == null
                    || parsed.getAmount() <= 0
                    || parsed.getCategory() == null) {
                return parsed;
            }

            Conversation expenseConversation =
                    new Conversation(farmerId, "EXPENSE");

            expenseConversation.data.put("amount", parsed.getAmount());
            expenseConversation.data.put(
                    "category",
                    parsed.getCategory()
            );
            expenseConversation.data.put(
                    "description",
                    message.trim()
            );

            String id = UUID.randomUUID().toString();

            conversations.put(id, expenseConversation);

            parsed.setConversationId(id);
            parsed.setNextStep("CONFIRM_EXPENSE");

            return parsed;
        }

        if ("HARVEST".equals(parsed.getIntent())) {
            return response(
                    "HARVEST",
                    "Harvest conversation will be added next. " +
                            "For now, please use your existing harvest form."
            );
        }

        if ("PRODUCT_CREATE".equals(parsed.getIntent())
                || "PRODUCT_DELETE".equals(parsed.getIntent())) {
            return response(
                    parsed.getIntent(),
                    "Product conversation will be added next. " +
                            "For now, please use your existing product page."
            );
        }

        return parsed;
    }

    private AssistantResponse startCropConversation(Long farmerId) {

        Conversation conversation =
                new Conversation(farmerId, "CROP_CREATE");

        String id = UUID.randomUUID().toString();

        conversation.step = "CROP_NAME";
        conversations.put(id, conversation);

        AssistantResponse result = response(
                "CROP_CREATE",
                "Let's add your crop! 🌱\n\n" +
                        "1. What crop do you want to plant?"
        );

        result.setConversationId(id);
        result.setNextStep("CROP_NAME");

        return result;
    }

    private AssistantResponse continueConversation(
            Conversation conversation,
            String message,
            String conversationId) {

        String answer = message == null ? "" : message.trim();

        if (answer.isBlank()) {
            return askCurrentQuestion(conversation, conversationId);
        }

        if (isCancelMessage(answer)) {
            conversations.remove(conversationId);

            return response(
                    "CANCELLED",
                    "No changes were saved. Your conversation was cancelled."
            );
        }

        if (conversation.step.startsWith("CROP_")) {
            return collectCropAnswer(
                    conversation,
                    answer,
                    conversationId
            );
        }

        return askCurrentQuestion(conversation, conversationId);
    }

    private AssistantResponse collectCropAnswer(
            Conversation conversation,
            String answer,
            String conversationId) {

        switch (conversation.step) {

            case "CROP_NAME":
                conversation.data.put("cropName", answer);
                conversation.step = "PLANTING_DATE";
                break;

            case "PLANTING_DATE":
                if (!isValidDate(answer)) {
                    return question(
                            conversation,
                            conversationId,
                            "Please enter a valid date in YYYY-MM-DD format."
                    );
                }

                conversation.data.put("plantingDate", answer);
                conversation.step = "SOIL_TYPE";
                break;

            case "SOIL_TYPE":
                conversation.data.put("soilType", answer);
                conversation.step = "AREA";
                break;

            case "AREA":
                Double area = parsePositiveNumber(answer);

                if (area == null) {
                    return question(
                            conversation,
                            conversationId,
                            "Please enter a valid land area, such as 0.5."
                    );
                }

                conversation.data.put("area", area);
                conversation.step = "AREA_UNIT";
                break;

            case "AREA_UNIT":
                String unit = answer.toLowerCase();

                if (unit.contains("acre")) {
                    conversation.data.put("areaUnit", "Acre");
                } else if (unit.contains("hectare")) {
                    conversation.data.put("areaUnit", "Hectare");
                } else if (unit.contains("cent")) {
                    conversation.data.put("areaUnit", "Cent");
                } else if (unit.contains("sq")) {
                    conversation.data.put("areaUnit", "Square Meter");
                } else {
                    return question(
                            conversation,
                            conversationId,
                            "Please enter the unit: acre, hectare, cent, or square meter."
                    );
                }

                conversation.step = "VARIETY";
                break;

            case "VARIETY":
                conversation.data.put("variety", answer);
                conversation.step = "SEED_SOURCE";
                break;

            case "SEED_SOURCE":
                conversation.data.put("seedSource", answer);
                conversation.step = "FERTILIZER";
                break;

            case "FERTILIZER":
                conversation.data.put("fertilizerUsed", answer);
                conversation.step = "IRRIGATION";
                break;

            case "IRRIGATION":
                conversation.data.put("irrigationMethod", answer);
                conversation.step = "EXPECTED_HARVEST_DATE";
                break;

            case "EXPECTED_HARVEST_DATE":
                if (!isValidDate(answer)) {
                    return question(
                            conversation,
                            conversationId,
                            "Please enter a valid expected harvest date in YYYY-MM-DD format."
                    );
                }

                conversation.data.put("expectedHarvestDate", answer);
                conversation.step = "CONFIRM_CROP";

                return cropConfirmation(conversation, conversationId);

            default:
                return response(
                        "UNKNOWN",
                        "I couldn't continue this crop conversation. Please start again."
                );
        }

        return askCurrentQuestion(conversation, conversationId);
    }

    private AssistantResponse askCurrentQuestion(
            Conversation conversation,
            String conversationId) {

        String prompt;

        switch (conversation.step) {

            case "CROP_NAME":
                prompt = "What crop do you want to plant?";
                break;

            case "PLANTING_DATE":
                prompt = "What is the planting date? Enter YYYY-MM-DD.";
                break;

            case "SOIL_TYPE":
                prompt = "What type of soil do you have? " +
                        "For example, red, black, sandy, or loamy soil.";
                break;

            case "AREA":
                prompt = "How much land will you use? Enter the number only, such as 0.5.";
                break;

            case "AREA_UNIT":
                prompt = "What is the land area unit? Enter acre, hectare, cent, or square meter.";
                break;

            case "VARIETY":
                prompt = "Which crop variety are you planting? " +
                        "If unknown, type Unknown.";
                break;

            case "SEED_SOURCE":
                prompt = "Where did you get the seeds? " +
                        "If unknown, type Unknown.";
                break;

            case "FERTILIZER":
                prompt = "Which fertilizer will you use? " +
                        "If undecided, type Not decided.";
                break;

            case "IRRIGATION":
                prompt = "Which irrigation method will you use? " +
                        "For example, drip, sprinkler, or flood.";
                break;

            case "EXPECTED_HARVEST_DATE":
                prompt = "What is the expected harvest date? Enter YYYY-MM-DD.";
                break;

            case "CONFIRM_CROP":
                return cropConfirmation(conversation, conversationId);

            default:
                prompt = "Please provide your answer.";
        }

        return question(conversation, conversationId, prompt);
    }

    private AssistantResponse cropConfirmation(
            Conversation conversation,
            String conversationId) {

        Map<String, Object> d = conversation.data;

        String summary =
                "Please review your crop details:\n\n" +
                        "Crop: " + d.get("cropName") + "\n" +
                        "Planting date: " + d.get("plantingDate") + "\n" +
                        "Soil: " + d.get("soilType") + "\n" +
                        "Land area: " + d.get("area") + " " +
                        d.get("areaUnit") + "\n" +
                        "Variety: " + d.get("variety") + "\n" +
                        "Seed source: " + d.get("seedSource") + "\n" +
                        "Fertilizer: " + d.get("fertilizerUsed") + "\n" +
                        "Irrigation: " + d.get("irrigationMethod") + "\n" +
                        "Expected harvest: " + d.get("expectedHarvestDate") +
                        "\n\nReply YES to save or NO to cancel.";

        AssistantResponse result = response(
                "CROP_CREATE",
                summary
        );

        result.setConversationId(conversationId);
        result.setRequiresConfirmation(true);
        result.setNextStep("CONFIRM_CROP");
        result.setData(d);

        return result;
    }

    private AssistantResponse confirmAction(
            Conversation conversation,
            String conversationId) {

        if ("EXPENSE".equals(conversation.intent)) {

            Object amountValue = conversation.data.get("amount");

            if (!(amountValue instanceof Number)) {
                conversations.remove(conversationId);

                return response(
                        "UNKNOWN",
                        "The expense details are invalid. Please start again."
                );
            }

            FarmerExpense expense = new FarmerExpense();
            expense.setFarmerId(conversation.farmerId);
            expense.setAmount(((Number) amountValue).doubleValue());
            expense.setCategory(
                    (String) conversation.data.get("category")
            );
            expense.setDescription(
                    (String) conversation.data.get("description")
            );
            expense.setExpenseDate(LocalDate.now());

            FarmerExpense saved = expenseRepository.save(expense);
            conversations.remove(conversationId);

            return response(
                    "EXPENSE_SAVED",
                    "Expense saved successfully! Amount: ₹" +
                            saved.getAmount() +
                            ", Category: " + saved.getCategory() +
                            ", Expense ID: " + saved.getId()
            );
        }

        if ("CROP_CREATE".equals(conversation.intent)) {

            if (!"CONFIRM_CROP".equals(conversation.step)) {
                return response(
                        "UNKNOWN",
                        "The crop is not ready for confirmation."
                );
            }

            Crop crop = new Crop();

            crop.setFarmerId(conversation.farmerId);
            crop.setCropName((String) conversation.data.get("cropName"));
            crop.setPlantingDate((String) conversation.data.get("plantingDate"));
            crop.setSoilType((String) conversation.data.get("soilType"));
            crop.setArea(((Number) conversation.data.get("area")).doubleValue());
            crop.setAreaUnit((String) conversation.data.get("areaUnit"));
            crop.setVariety((String) conversation.data.get("variety"));
            crop.setSeedSource((String) conversation.data.get("seedSource"));
            crop.setFertilizerUsed((String) conversation.data.get("fertilizerUsed"));
            crop.setIrrigationMethod((String) conversation.data.get("irrigationMethod"));
            crop.setExpectedHarvestDate(
                    (String) conversation.data.get("expectedHarvestDate")
            );
            crop.setCropStatus("Growing");

            Crop saved = cropRepository.save(crop);
            conversations.remove(conversationId);

            AssistantResponse result = response(
                    "CROP_SAVED",
                    "Crop saved successfully! 🌱\n" +
                            "Crop: " + saved.getCropName() +
                            "\nCrop ID: " + saved.getId()
            );

            result.setNextStep("COMPLETED");
            return result;
        }

        return response(
                "UNKNOWN",
                "This action cannot be confirmed yet."
        );
    }

    private AssistantResponse question(
            Conversation conversation,
            String conversationId,
            String message) {

        AssistantResponse result = response(
                conversation.intent,
                message
        );

        result.setConversationId(conversationId);
        result.setNextStep(conversation.step);
        result.setData(conversation.data);

        return result;
    }

    private AssistantResponse response(
            String intent,
            String message) {

        return new AssistantResponse(
                intent,
                message,
                false,
                null,
                null
        );
    }

    private boolean isCancelMessage(String message) {
        String text = message.trim().toLowerCase();

        return text.equals("cancel")
                || text.equals("stop")
                || text.equals("no")
                || text.equals("no thanks");
    }

    private boolean isValidDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private Double parsePositiveNumber(String value) {
        try {
            double number = Double.parseDouble(value.trim());

            if (Double.isFinite(number) && number > 0) {
                return number;
            }

            return null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static class Conversation {

        private final Long farmerId;
        private final String intent;
        private String step;

        private final Map<String, Object> data =
                new ConcurrentHashMap<>();

        private Conversation(Long farmerId, String intent) {
            this.farmerId = farmerId;
            this.intent = intent;
        }
    }
}
