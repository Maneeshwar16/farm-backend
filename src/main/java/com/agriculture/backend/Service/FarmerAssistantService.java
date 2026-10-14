
package com.agriculture.backend.Service;

import com.agriculture.backend.dto.AssistantResponse;
import com.agriculture.backend.entity.Crop;
import com.agriculture.backend.entity.FarmerExpense;
import com.agriculture.backend.repository.CropRepository;
import com.agriculture.backend.repository.FarmerExpenseRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
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
            return response("UNKNOWN", "Please log in as a farmer first.");
        }

        Conversation conversation = conversationId == null
                ? null : conversations.get(conversationId);

        if (conversation != null && !conversation.farmerId.equals(farmerId)) {
            return response("UNKNOWN", "This conversation does not belong to your account.");
        }

        if (cancelled || isNo(message)) {
            if (conversationId != null) conversations.remove(conversationId);
            return response("CANCELLED", "Cancelled. Nothing was saved.");
        }

        if (conversation != null) {
            if (confirmed || isYes(message)) {
                if ("CONFIRM_CROP".equals(conversation.step)
                        || "CONFIRM_EXPENSE".equals(conversation.step)) {
                    return saveAction(conversation, conversationId);
                }
            }
            return continueConversation(conversation, message, conversationId);
        }

        AssistantResponse parsed = parser.parse(message);

        if ("CROP_CREATE".equals(parsed.getIntent())) {
            Conversation c = new Conversation(farmerId, "CROP_CREATE");

            if (parsed.getData() != null) {
                c.data.putAll(parsed.getData());
            }

            // Unknown optional details can be supplied as "Unknown".
            // Required dates, area, unit, crop name, and soil are collected.
            c.step = parser.nextCropStep(c.data);

            String id = UUID.randomUUID().toString();
            conversations.put(id, c);

            if ("CONFIRM_CROP".equals(c.step)) return cropSummary(c, id);

            AssistantResponse result = question(
                    c, id, parser.cropQuestion(c.step)
            );
            return result;
        }

        if ("EXPENSE".equals(parsed.getIntent())) {
            if (parsed.getAmount() == null || parsed.getCategory() == null) {
                return parsed;
            }

            Conversation c = new Conversation(farmerId, "EXPENSE");
            c.step = "CONFIRM_EXPENSE";
            c.data.put("amount", parsed.getAmount());
            c.data.put("category", parsed.getCategory());
            c.data.put("description", message.trim());

            String id = UUID.randomUUID().toString();
            conversations.put(id, c);

            parsed.setConversationId(id);
            parsed.setNextStep(c.step);
            return parsed;
        }

        return parsed;
    }

    private AssistantResponse continueConversation(
            Conversation c, String answer, String id) {

        if (answer == null || answer.isBlank()) {
            return question(c, id, parser.cropQuestion(c.step));
        }

        if ("CROP_CREATE".equals(c.intent)) {
            switch (c.step) {
                case "CROP_NAME":
                    c.data.put("cropName", answer.trim());
                    break;

                case "PLANTING_DATE":
                    String date = parseDate(answer);
                    if (date == null) {
                        return question(c, id,
                                "Enter the planting date as YYYY-MM-DD, or say today or yesterday.");
                    }
                    c.data.put("plantingDate", date);
                    break;

                case "SOIL_TYPE":
                    c.data.put("soilType", answer.trim());
                    break;

                case "AREA":
                    String number = answer.trim().replaceAll(
                            "(?i)\\s*(acres?|hectares?|cents?)\\s*", ""
                    );
                    try {
                        double area = Double.parseDouble(number);
                        if (!Double.isFinite(area) || area <= 0) throw new NumberFormatException();
                        c.data.put("area", area);

                        String lower = answer.toLowerCase(Locale.ROOT);
                        if (lower.contains("acre")) {
                            c.data.put("areaUnit", "Acres");
                        } else if (lower.contains("hectare")) {
                            c.data.put("areaUnit", "Hectares");
                        } else if (lower.contains("cent")) {
                            c.data.put("areaUnit", "Cents");
                        }
                    } catch (NumberFormatException e) {
                        return question(c, id,
                                "Enter a positive area, such as 5 or 5 acres.");
                    }
                    break;

                case "AREA_UNIT":
                    String unit = answer.toLowerCase(Locale.ROOT);
                    if (unit.contains("acre")) c.data.put("areaUnit", "Acres");
                    else if (unit.contains("hectare")) c.data.put("areaUnit", "Hectares");
                    else if (unit.contains("cent")) c.data.put("areaUnit", "Cents");
                    else return question(c, id, "Enter acres, hectares, or cents.");
                    break;

                case "VARIETY":
                    c.data.put("variety", answer.trim());
                    break;

                case "SEED_SOURCE":
                    c.data.put("seedSource", answer.trim());
                    break;

                case "FERTILIZER":
                    c.data.put("fertilizerUsed", answer.trim());
                    break;

                case "IRRIGATION":
                    c.data.put("irrigationMethod", answer.trim());
                    break;

                case "EXPECTED_HARVEST_DATE":
                    String harvestDate = parseDate(answer);
                    if (harvestDate == null) {
                        return question(c, id,
                                "Enter the expected harvest date as YYYY-MM-DD.");
                    }
                    c.data.put("expectedHarvestDate", harvestDate);
                    break;

                case "CONFIRM_CROP":
                    if (isYes(answer)) return saveAction(c, id);
                    if (isNo(answer)) {
                        conversations.remove(id);
                        return response("CANCELLED", "Crop creation cancelled.");
                    }
                    return cropSummary(c, id);

                default:
                    return response("UNKNOWN", "Invalid crop conversation step.");
            }

            // Treat an area answer like "5 acres" as supplying both fields.
            c.step = parser.nextCropStep(c.data);

            if ("CONFIRM_CROP".equals(c.step)) return cropSummary(c, id);
            return question(c, id, parser.cropQuestion(c.step));
        }

        if ("EXPENSE".equals(c.intent)) {
            if (isYes(answer)) return saveAction(c, id);
            return question(c, id,
                    "Reply YES to save this expense, or NO to cancel.");
        }

        return response("UNKNOWN", "This conversation cannot be continued.");
    }

    private AssistantResponse cropSummary(Conversation c, String id) {
        String summary =
                "Please confirm your crop details:\n\n" +
                        "Crop: " + c.data.get("cropName") + "\n" +
                        "Planting date: " + c.data.get("plantingDate") + "\n" +
                        "Soil: " + c.data.get("soilType") + "\n" +
                        "Area: " + c.data.get("area") + " " + c.data.get("areaUnit") + "\n" +
                        "Variety: " + c.data.get("variety") + "\n" +
                        "Seed source: " + c.data.get("seedSource") + "\n" +
                        "Fertilizer: " + c.data.get("fertilizerUsed") + "\n" +
                        "Irrigation: " + c.data.get("irrigationMethod") + "\n" +
                        "Expected harvest date: " + c.data.get("expectedHarvestDate") +
                        "\n\nReply YES to save or NO to cancel.";

        AssistantResponse result = question(c, id, summary);
        result.setRequiresConfirmation(true);
        result.setNextStep("CONFIRM_CROP");
        return result;
    }

    private AssistantResponse saveAction(Conversation c, String id) {
        if ("EXPENSE".equals(c.intent)) {
            Object value = c.data.get("amount");
            if (!(value instanceof Number) || c.data.get("category") == null) {
                conversations.remove(id);
                return response("UNKNOWN", "Expense details are invalid.");
            }

            double amount = ((Number) value).doubleValue();
            if (!Double.isFinite(amount) || amount <= 0) {
                conversations.remove(id);
                return response("UNKNOWN", "Expense amount is invalid.");
            }

            FarmerExpense expense = new FarmerExpense();
            expense.setFarmerId(c.farmerId);
            expense.setAmount(amount);
            expense.setCategory(c.data.get("category").toString());
            expense.setDescription(String.valueOf(c.data.get("description")));
            expense.setExpenseDate(LocalDate.now());

            FarmerExpense saved = expenseRepository.save(expense);
            conversations.remove(id);

            AssistantResponse result = response(
                    "EXPENSE_SAVED",
                    "Expense saved successfully! ₹" + saved.getAmount() +
                            ", category: " + saved.getCategory() +
                            ", ID: " + saved.getId()
            );
            result.setNextStep("COMPLETED");
            return result;
        }

        if ("CROP_CREATE".equals(c.intent)) {
            // Revalidate required data immediately before persistence.
            if (!"CONFIRM_CROP".equals(c.step)
                    || parser.nextCropStep(c.data) != "CONFIRM_CROP") {
                return response("UNKNOWN",
                        "Some crop details are missing. Please continue the conversation.");
            }

            Crop crop = new Crop();
            crop.setFarmerId(c.farmerId);
            crop.setCropName((String) c.data.get("cropName"));
            crop.setPlantingDate((String) c.data.get("plantingDate"));
            crop.setSoilType((String) c.data.get("soilType"));
            crop.setArea(((Number) c.data.get("area")).doubleValue());
            crop.setAreaUnit((String) c.data.get("areaUnit"));
            crop.setVariety((String) c.data.get("variety"));
            crop.setSeedSource((String) c.data.get("seedSource"));
            crop.setFertilizerUsed((String) c.data.get("fertilizerUsed"));
            crop.setIrrigationMethod((String) c.data.get("irrigationMethod"));
            crop.setExpectedHarvestDate((String) c.data.get("expectedHarvestDate"));
            crop.setCropStatus("Growing");

            Crop saved = cropRepository.save(crop);
            conversations.remove(id);

            AssistantResponse result = response(
                    "CROP_SAVED",
                    "Crop saved successfully! 🌱 " + saved.getCropName() +
                            " (Crop ID: " + saved.getId() + ")"
            );
            result.setConversationId(id);
            result.setNextStep("COMPLETED");
            return result;
        }

        return response("UNKNOWN", "This action cannot be saved.");
    }

    private AssistantResponse question(Conversation c, String id, String message) {
        AssistantResponse result = response(c.intent, message);
        result.setConversationId(id);
        result.setNextStep(c.step);
        result.setData(new LinkedHashMap<>(c.data));
        return result;
    }

    private String parseDate(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.equals("today")) return LocalDate.now().toString();
        if (text.equals("yesterday")) return LocalDate.now().minusDays(1).toString();
        try {
            return LocalDate.parse(value.trim()).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean isYes(String value) {
        if (value == null) return false;
        return value.trim().matches("(?i)yes|y|confirm|confirmed|save|save it");
    }

    private boolean isNo(String value) {
        if (value == null) return false;
        return value.trim().matches("(?i)no|n|cancel|stop|no thanks");
    }

    private AssistantResponse response(String intent, String message) {
        return new AssistantResponse(intent, message, false, null, null);
    }

    private static class Conversation {
        private final Long farmerId;
        private final String intent;
        private String step;
        private final Map<String, Object> data = new ConcurrentHashMap<>();

        private Conversation(Long farmerId, String intent) {
            this.farmerId = farmerId;
            this.intent = intent;
        }
    }
}
