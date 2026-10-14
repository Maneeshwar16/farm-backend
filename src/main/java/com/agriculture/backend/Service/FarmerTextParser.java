
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
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern AREA_PATTERN = Pattern.compile(
            "\\b([0-9]+(?:\\.[0-9]+)?)\\s*(acres?|hectares?|cents?)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public AssistantResponse parse(String message) {
        if (message == null || message.isBlank()) {
            return response("UNKNOWN", "Please enter a message.");
        }

        String text = message.toLowerCase(Locale.ROOT);

        if (containsAny(text, "spent", "expense", "paid", "cost",
                "purchased fertilizer", "bought seeds")) {
            Matcher matcher = AMOUNT_PATTERN.matcher(message);

            if (!matcher.find()) {
                return response("EXPENSE",
                        "How much did you spend? Please include the amount.");
            }

            double amount;
            try {
                amount = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return response("EXPENSE", "Enter a valid expense amount.");
            }

            if (!Double.isFinite(amount) || amount <= 0) {
                return response("EXPENSE",
                        "Enter an expense amount greater than zero.");
            }

            String category = detectExpenseCategory(text);

            AssistantResponse result = new AssistantResponse(
                    "EXPENSE",
                    "Confirm recording ₹" + amount + " for " + category + "?",
                    true, amount, category
            );
            result.setNextStep("CONFIRM_EXPENSE");
            return result;
        }

        if (containsAny(text, "plant", "planting", "planted",
                "grow", "growing", "add crop", "new crop",
                "register crop")) {
            Map<String, Object> data = extractCropDetails(message);

            AssistantResponse result = response(
                    "CROP_CREATE", "Let's register your crop."
            );
            result.setData(data);
            result.setNextStep(nextCropStep(data));
            result.setMessage(cropQuestion(result.getNextStep()));
            return result;
        }

        if (containsAny(text, "harvest", "harvested", "reaped")) {
            AssistantResponse result = response(
                    "HARVEST", "Which crop did you harvest?"
            );
            result.setNextStep("HARVEST_CROP");
            return result;
        }

        if (containsAny(text, "delete product", "remove listing",
                "delete listing", "remove product")) {
            AssistantResponse result = response(
                    "PRODUCT_DELETE", "Which product listing should be removed?"
            );
            result.setNextStep("PRODUCT_DELETE_NAME");
            return result;
        }

        if (containsAny(text, "sell", "list product", "upload product",
                "add product", "put up for sale")) {
            AssistantResponse result = response(
                    "PRODUCT_CREATE", "Which product would you like to sell?"
            );
            result.setNextStep("PRODUCT_NAME");
            return result;
        }

        return response("UNKNOWN",
                "Try: I am planting tomatoes today on 5 acres with red soil.");
    }

    public Map<String, Object> extractCropDetails(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        String text = message.toLowerCase(Locale.ROOT);

        Matcher crop = Pattern.compile(
                "\\b(?:planting|plant|planted|grow|growing)\\s+" +
                        "(?:some\\s+|the\\s+)?([a-zA-Z]+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(message);

        if (crop.find()) {
            String name = crop.group(1);
            if (!containsAny(name.toLowerCase(Locale.ROOT),
                    "today", "tomorrow", "yesterday")) {
                data.put("cropName", titleCase(name));
            }
        }

        Matcher date = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b")
                .matcher(message);

        try {
            if (date.find()) {
                data.put("plantingDate",
                        LocalDate.parse(date.group()).toString());
            } else if (text.contains("today")) {
                data.put("plantingDate", LocalDate.now().toString());
            } else if (text.contains("yesterday")) {
                data.put("plantingDate",
                        LocalDate.now().minusDays(1).toString());
            }
        } catch (DateTimeParseException ignored) {
            // The service will ask for a valid date.
        }

        Matcher area = AREA_PATTERN.matcher(message);
        if (area.find()) {
            try {
                double value = Double.parseDouble(area.group(1));
                if (Double.isFinite(value) && value > 0) {
                    data.put("area", value);
                    String unit = area.group(2).toLowerCase(Locale.ROOT);
                    data.put("areaUnit",
                            unit.startsWith("acre") ? "Acres" :
                                    unit.startsWith("hectare") ? "Hectares" : "Cents");
                }
            } catch (NumberFormatException ignored) {
                // Missing area will be requested.
            }
        }

        if (text.contains("red soil")) data.put("soilType", "Red soil");
        else if (text.contains("black soil")) data.put("soilType", "Black soil");
        else if (text.contains("sandy soil")) data.put("soilType", "Sandy soil");
        else if (text.contains("loamy soil")) data.put("soilType", "Loamy soil");
        else if (text.contains("clay soil")) data.put("soilType", "Clay soil");
        else if (text.contains("alluvial soil")) data.put("soilType", "Alluvial soil");

        if (text.contains("drip irrigation")) {
            data.put("irrigationMethod", "Drip");
        } else if (text.contains("sprinkler")) {
            data.put("irrigationMethod", "Sprinkler");
        } else if (text.contains("flood irrigation")) {
            data.put("irrigationMethod", "Flood");
        } else if (text.contains("rain-fed") || text.contains("rainfed")) {
            data.put("irrigationMethod", "Rain-fed");
        }

        Matcher variety = Pattern.compile(
                "\\bvariety\\s+(?:is\\s+)?([a-zA-Z0-9 -]+?)(?=\\s+(?:on|with|in)\\b|[,.;]|$)",
                Pattern.CASE_INSENSITIVE
        ).matcher(message);
        if (variety.find()) data.put("variety", variety.group(1).trim());

        Matcher seeds = Pattern.compile(
                "\\bseed source\\s+(?:is\\s+)?(.+?)(?=[,.;]|$)",
                Pattern.CASE_INSENSITIVE
        ).matcher(message);
        if (seeds.find()) data.put("seedSource", seeds.group(1).trim());

        Matcher fertilizer = Pattern.compile(
                "\\bfertilizer\\s+(?:is\\s+|used\\s+)?(.+?)(?=[,.;]|$)",
                Pattern.CASE_INSENSITIVE
        ).matcher(message);
        if (fertilizer.find()) {
            String value = fertilizer.group(1).trim();
            if (!value.isBlank()) data.put("fertilizerUsed", value);
        }

        Matcher harvestDate = Pattern.compile(
                "\\bexpected harvest\\s+(?:date\\s+)?(?:is\\s+)?(\\d{4}-\\d{2}-\\d{2})\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(message);
        if (harvestDate.find()) {
            try {
                data.put("expectedHarvestDate",
                        LocalDate.parse(harvestDate.group(1)).toString());
            } catch (DateTimeParseException ignored) {
                // Ask for a valid date later.
            }
        }

        return data;
    }

    public String nextCropStep(Map<String, Object> data) {
        if (!data.containsKey("cropName")) return "CROP_NAME";
        if (!data.containsKey("plantingDate")) return "PLANTING_DATE";
        if (!data.containsKey("soilType")) return "SOIL_TYPE";
        if (!data.containsKey("area")) return "AREA";
        if (!data.containsKey("areaUnit")) return "AREA_UNIT";
        if (!data.containsKey("variety")) return "VARIETY";
        if (!data.containsKey("seedSource")) return "SEED_SOURCE";
        if (!data.containsKey("fertilizerUsed")) return "FERTILIZER";
        if (!data.containsKey("irrigationMethod")) return "IRRIGATION";
        if (!data.containsKey("expectedHarvestDate")) return "EXPECTED_HARVEST_DATE";
        return "CONFIRM_CROP";
    }

    public String cropQuestion(String step) {
        switch (step) {
            case "CROP_NAME": return "What crop are you planting?";
            case "PLANTING_DATE": return "What is the planting date? Enter YYYY-MM-DD or say today.";
            case "SOIL_TYPE": return "What type of soil do you have? For example, red, black, sandy, or loamy.";
            case "AREA": return "How much land will you use? Enter the number, such as 0.5.";
            case "AREA_UNIT": return "Is the area measured in acres, hectares, or cents?";
            case "VARIETY": return "Which variety are you planting? Type Unknown if you don't know.";
            case "SEED_SOURCE": return "Where did you get the seeds? Type Unknown if you don't know.";
            case "FERTILIZER": return "Which fertilizer will you use? Type Not decided if unknown.";
            case "IRRIGATION": return "Which irrigation method will you use: drip, sprinkler, flood, or rain-fed?";
            case "EXPECTED_HARVEST_DATE": return "What is the expected harvest date? Enter YYYY-MM-DD.";
            case "CONFIRM_CROP": return "All details are collected. Reply YES to save or NO to cancel.";
            default: return "Please provide the requested detail.";
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
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    private String titleCase(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private AssistantResponse response(String intent, String message) {
        return new AssistantResponse(intent, message, false, null, null);
    }
}
