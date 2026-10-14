package com.agriculture.backend.Service;

import com.agriculture.backend.dto.AssistantResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FarmerTextParser {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:₹|rs\\.?\\s*)?([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:rupees?|rs\\.?)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AREA_PATTERN = Pattern.compile(
            "\\b([0-9]+(?:\\.[0-9]+)?)\\s*(acres?|hectares?|cents?|acre|ha|ac)\\b",
            Pattern.CASE_INSENSITIVE);

    public AssistantResponse parse(String message) {
        if (message == null || message.isBlank()) {
            return response("UNKNOWN", "Please enter a message.");
        }

        String text = message.toLowerCase(Locale.ROOT).trim();

        // 1. Check for crop deletion
        if (isCropDelete(text)) {
            String target = extractCropDeleteTarget(message);
            AssistantResponse result = response("CROP_DELETE", "Delete crop");
            if (target != null && !target.isBlank()) {
                result.getData().put("target", target);
            }
            result.setNextStep("CONFIRM_CROP_DELETE");
            return result;
        }

        // 2. Check for product deletion
        if (containsAny(text, "delete product", "remove product", "delete listing", "remove listing")) {
            Matcher m = Pattern.compile("(?i)(?:delete product|remove product|delete listing|remove listing)[:\\s]+([a-zA-Z0-9\\s]+)")
                    .matcher(message);
            AssistantResponse result = response("PRODUCT_DELETE", "Which product listing should be removed?");
            if (m.find()) {
                result.getData().put("productName", m.group(1).trim());
            }
            result.setNextStep("PRODUCT_DELETE_CONFIRM");
            return result;
        }

        // 3. Check for expense recording
        if (containsAny(text, "spent", "expense", "paid", "cost", "purchased fertilizer", "bought seeds")) {
            Matcher matcher = AMOUNT_PATTERN.matcher(message);
            if (!matcher.find()) {
                return response("EXPENSE", "How much did you spend? Please include the amount. Example: I spent ₹500 on fertilizer.");
            }

            double amount;
            try {
                amount = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return response("EXPENSE", "Enter a valid expense amount.");
            }

            if (!Double.isFinite(amount) || amount <= 0) {
                return response("EXPENSE", "Enter an expense amount greater than zero.");
            }

            String category = detectExpenseCategory(text);
            AssistantResponse result = new AssistantResponse(
                    "EXPENSE",
                    "Confirm recording ₹" + amount + " for " + category + "?",
                    true, amount, category);
            result.setNextStep("CONFIRM_EXPENSE");
            result.getData().put("amount", amount);
            result.getData().put("category", category);
            result.getData().put("description", message.trim());
            return result;
        }

        // 4. Check for crop creation
        if (isCropCreate(text)) {
            Map<String, Object> data = extractCropDetails(message);
            AssistantResponse result = response("CROP_CREATE", "Let's register your crop.");
            result.setData(data);
            String nextStep = nextCropStep(data);
            result.setNextStep(nextStep);
            result.setMessage(cropQuestion(nextStep));
            return result;
        }

        // 5. Check for harvest recording
        if (containsAny(text, "harvest", "harvested", "reaped")) {
            AssistantResponse result = response("HARVEST", "Which crop did you harvest?");
            result.setNextStep("HARVEST_CROP");
            return result;
        }

        // 6. Check for product creation
        if (containsAny(text, "sell", "list product", "upload product", "add product", "put up for sale")) {
            AssistantResponse result = response("PRODUCT_CREATE", "Which product would you like to sell?");
            result.setNextStep("PRODUCT_NAME");
            return result;
        }

        return response("UNKNOWN",
                "I can help you add or delete crops, list products, record expenses, record harvests, and check your farm records.\n\n" +
                        "Try:\n" +
                        "• \"I am planting 5 acres of wheat\"\n" +
                        "• \"Show my crops\"\n" +
                        "• \"Delete crop tomato\"\n" +
                        "• \"I spent ₹500 on fertilizer\"\n" +
                        "• \"Existing stock\"");
    }

    public boolean isCropDelete(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasDelete = containsAny(lower, "delete", "remove", "drop", "erase", "cancel");
        boolean hasCrop = containsAny(lower, "crop", "crops");
        return hasDelete && hasCrop;
    }

    public boolean isCropCreate(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (isCropDelete(lower)) return false;
        return containsAny(lower,
                "plant", "planting", "planted",
                "grow", "growing",
                "add crop", "new crop", "record crop", "register crop",
                "sow", "sowing", "sowed", "cultivate", "cultivating");
    }

    public String extractCropDeleteTarget(String message) {
        if (message == null) return null;
        String text = message.trim();

        // Pattern 1: "delete crop id 15" or "delete crop #15" or "delete crop 15"
        Matcher idMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?crop\\s+(?:id\\s+|#)?(\\d+)")
                .matcher(text);
        if (idMatcher.find()) {
            return idMatcher.group(1).trim();
        }

        // Pattern 2: "delete crop <name>" or "remove crop <name>"
        Matcher nameMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?crop\\s+(?:named\\s+|called\\s+)?([a-zA-Z0-9\\s]+)")
                .matcher(text);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim();
            // clean up trailing punctuation
            name = name.replaceAll("[,.;!?]+$", "").trim();
            if (!name.isBlank()) {
                return name;
            }
        }

        // Pattern 3: "delete <name> crop"
        Matcher nameBeforeMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?([a-zA-Z0-9\\s]+?)\\s+crop")
                .matcher(text);
        if (nameBeforeMatcher.find()) {
            String name = nameBeforeMatcher.group(1).trim();
            name = name.replaceAll("[,.;!?]+$", "").trim();
            if (!name.isBlank()) {
                return name;
            }
        }

        return null;
    }

    public Map<String, Object> extractCropDetails(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        String text = message.toLowerCase(Locale.ROOT);

        // 1. Extract Area and Area Unit
        Matcher areaMatcher = AREA_PATTERN.matcher(message);
        if (areaMatcher.find()) {
            try {
                double value = Double.parseDouble(areaMatcher.group(1));
                if (Double.isFinite(value) && value > 0) {
                    data.put("area", value);
                    String unitRaw = areaMatcher.group(2).toLowerCase(Locale.ROOT);
                    String unit = unitRaw.startsWith("ha") || unitRaw.startsWith("hectare") ? "Hectares" :
                            unitRaw.startsWith("cent") ? "Cents" : "Acres";
                    data.put("areaUnit", unit);
                }
            } catch (NumberFormatException ignored) {}
        }

        // 2. Extract Crop Name
        String extractedCropName = null;

        // Try Pattern A: "add crop <name>" or "new crop <name>" or "record crop <name>"
        Matcher m1 = Pattern.compile("(?i)\\b(?:add crop|new crop|record crop|register crop)[:\\s]+(?:a\\s+|the\\s+)?([a-zA-Z0-9\\s]+?)(?=\\s+(?:in|on|with|of|area|acres?|ha|hectares?|cents?|today|yesterday|\\d)|[,.;]|$)")
                .matcher(message);
        if (m1.find()) {
            extractedCropName = m1.group(1).trim();
        }

        // Try Pattern B: "planting <name>" or "planted <name>" or "grow <name>"
        if (extractedCropName == null || extractedCropName.isBlank()) {
            Matcher m2 = Pattern.compile("(?i)\\b(?:planting|plant|planted|grow|growing|sow|sowing|sowed|cultivating)\\s+(?:some\\s+|a\\s+|the\\s+)?([a-zA-Z0-9\\s]+?)(?=\\s+(?:in|on|with|area|acres?|ha|hectares?|cents?|today|yesterday|\\d)|[,.;]|$)")
                    .matcher(message);
            if (m2.find()) {
                extractedCropName = m2.group(1).trim();
            }
        }

        // Try Pattern C: "... <number> acres of <name>" or "in <number> acres of <name>"
        if (extractedCropName == null || extractedCropName.isBlank()) {
            Matcher m3 = Pattern.compile("(?i)(?:acres?|hectares?|cents?)\\s+of\\s+([a-zA-Z0-9\\s]+?)(?=\\s+(?:with|today|yesterday|on|in)|[,.;]|$)")
                    .matcher(message);
            if (m3.find()) {
                extractedCropName = m3.group(1).trim();
            }
        }

        // Clean up common words that could be mistakenly captured
        if (extractedCropName != null) {
            String lower = extractedCropName.toLowerCase(Locale.ROOT).trim();
            if (containsAny(lower, "today", "yesterday", "tomorrow", "now", "here", "crop", "crops") || lower.matches("\\d+")) {
                extractedCropName = null;
            } else {
                data.put("cropName", titleCase(extractedCropName));
            }
        }

        // 3. Extract Planting Date
        Matcher date = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b").matcher(message);
        try {
            if (date.find()) {
                data.put("plantingDate", LocalDate.parse(date.group()).toString());
            } else if (text.contains("yesterday")) {
                data.put("plantingDate", LocalDate.now().minusDays(1).toString());
            } else {
                // Default planting date to today
                data.put("plantingDate", LocalDate.now().toString());
            }
        } catch (DateTimeParseException ignored) {
            data.put("plantingDate", LocalDate.now().toString());
        }

        // 4. Expected Harvest Date
        Matcher harvestDateMatcher = Pattern.compile(
                "\\b(?:expected harvest|harvest date)\\s+(?:is\\s+)?(\\d{4}-\\d{2}-\\d{2})\\b",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (harvestDateMatcher.find()) {
            try {
                data.put("expectedHarvestDate", LocalDate.parse(harvestDateMatcher.group(1)).toString());
            } catch (DateTimeParseException ignored) {}
        }
        if (!data.containsKey("expectedHarvestDate")) {
            // Smart default: 90 days from planting date
            LocalDate pDate = LocalDate.now();
            if (data.get("plantingDate") != null) {
                try {
                    pDate = LocalDate.parse(String.valueOf(data.get("plantingDate")));
                } catch (Exception ignored) {}
            }
            data.put("expectedHarvestDate", pDate.plusDays(90).toString());
        }

        // 5. Soil Type
        if (text.contains("red soil") || text.contains("red")) data.put("soilType", "Red soil");
        else if (text.contains("black soil") || text.contains("black")) data.put("soilType", "Black soil");
        else if (text.contains("sandy soil") || text.contains("sandy")) data.put("soilType", "Sandy soil");
        else if (text.contains("clay soil") || text.contains("clay")) data.put("soilType", "Clay soil");
        else if (text.contains("alluvial soil") || text.contains("alluvial")) data.put("soilType", "Alluvial soil");
        else data.put("soilType", "Loamy");

        // 6. Irrigation Method
        if (text.contains("drip irrigation") || text.contains("drip")) {
            data.put("irrigationMethod", "Drip");
        } else if (text.contains("sprinkler")) {
            data.put("irrigationMethod", "Sprinkler");
        } else if (text.contains("flood")) {
            data.put("irrigationMethod", "Flood");
        } else if (text.contains("rain-fed") || text.contains("rainfed") || text.contains("rain")) {
            data.put("irrigationMethod", "Rain-fed");
        } else {
            data.put("irrigationMethod", "Drip");
        }

        // 7. Variety
        Matcher variety = Pattern.compile(
                "\\bvariety\\s+(?:is\\s+)?([a-zA-Z0-9 -]+?)(?=\\s+(?:on|with|in)|[,.;]|$)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (variety.find()) {
            data.put("variety", variety.group(1).trim());
        } else {
            data.put("variety", "Standard");
        }

        // 8. Seed Source
        Matcher seeds = Pattern.compile(
                "\\bseed source\\s+(?:is\\s+)?(.+?)(?=[,.;]|$)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (seeds.find()) {
            data.put("seedSource", seeds.group(1).trim());
        } else {
            data.put("seedSource", "Certified Seeds");
        }

        // 9. Fertilizer Used
        Matcher fertilizer = Pattern.compile(
                "\\bfertilizer\\s+(?:is\\s+|used\\s+)?(.+?)(?=[,.;]|$)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (fertilizer.find()) {
            String val = fertilizer.group(1).trim();
            if (!val.isBlank()) data.put("fertilizerUsed", val);
        }
        if (!data.containsKey("fertilizerUsed")) {
            data.put("fertilizerUsed", "Standard Organic");
        }

        // 10. Default cropStatus matches /mycrop active stage
        data.put("cropStatus", "Seeded / Planted");

        return data;
    }

    public String nextCropStep(Map<String, Object> data) {
        if (!data.containsKey("cropName") || blank(data.get("cropName"))) {
            return "CROP_NAME";
        }
        if (!data.containsKey("area") || blank(data.get("area"))) {
            return "AREA";
        }
        if (!data.containsKey("areaUnit") || blank(data.get("areaUnit"))) {
            data.put("areaUnit", "Acres");
        }
        return "CONFIRM_CROP";
    }

    public String cropQuestion(String step) {
        switch (step) {
            case "CROP_NAME":
                return "Which crop are you planting? (e.g. Wheat, Tomato, Rice, Maize)";
            case "AREA":
                return "How much land area are you planting? (e.g. 5 acres, 2.5 hectares)";
            case "CONFIRM_CROP":
                return "All details are collected. Reply YES to save or NO to cancel.";
            default:
                return "Please provide the requested crop detail.";
        }
    }

    private String detectExpenseCategory(String text) {
        if (containsAny(text, "fertilizer", "fertiliser")) return "Fertilizer";
        if (containsAny(text, "seed", "seeds")) return "Seeds";
        if (containsAny(text, "labour", "labor", "worker")) return "Labour";
        if (containsAny(text, "pesticide", "pesticides")) return "Pesticides";
        if (containsAny(text, "equipment", "machine", "machinery")) return "Equipment";
        if (containsAny(text, "water", "irrigation")) return "Irrigation";
        if (containsAny(text, "transport", "transportation")) return "Transport";
        return "Other";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private boolean blank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private String titleCase(String value) {
        if (value == null || value.isBlank()) return "";
        String[] words = value.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1).toLowerCase(Locale.ROOT));
                }
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

    private AssistantResponse response(String intent, String message) {
        return new AssistantResponse(intent, message, false, null, null);
    }
}
