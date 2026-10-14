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

        // 1. Check for Greetings / General Help
        if (isGreeting(text)) {
            return response("GREETING",
                    "Namaste! 🌱 I am your smart **Farmer Assistant**.\n\n" +
                            "Here is what I can do for you:\n\n" +
                            "🌾 **Crops**: *\"I am planting 5 acres of wheat\"*, *\"Show my crops\"*, *\"Delete crop tomato\"*\n" +
                            "🚜 **Farms**: *\"Add farm Green Valley in 10 acres at Guntur\"*, *\"Show my farms\"*, *\"Delete farm\"*\n" +
                            "🌾 **Harvests**: *\"Record harvest\"*, *\"My harvests\"*\n" +
                            "🧺 **Products**: *\"Sell 100 kg of potatoes at ₹25\"*, *\"Existing stock\"*, *\"Delete product\"*\n" +
                            "💰 **Expenses**: *\"I spent ₹500 on fertilizer\"*, *\"My expenses\"*, *\"Total spent\"*\n\n" +
                            "What would you like to do today?");
        }

        // 2. Check for farm deletion
        if (isFarmDelete(text)) {
            String target = extractFarmDeleteTarget(message);
            AssistantResponse result = response("FARM_DELETE", "Delete farm");
            if (target != null && !target.isBlank()) {
                result.getData().put("target", target);
            }
            result.setNextStep("CONFIRM_FARM_DELETE");
            return result;
        }

        // 3. Check for farm creation
        if (isFarmCreate(text)) {
            Map<String, Object> data = extractFarmDetails(message);
            AssistantResponse result = response("FARM_CREATE", "Let's register your farm.");
            result.setData(data);
            String nextStep = nextFarmStep(data);
            result.setNextStep(nextStep);
            result.setMessage(farmQuestion(nextStep));
            return result;
        }

        // 4. Check for crop deletion
        if (isCropDelete(text)) {
            String target = extractCropDeleteTarget(message);
            AssistantResponse result = response("CROP_DELETE", "Delete crop");
            if (target != null && !target.isBlank()) {
                result.getData().put("target", target);
            }
            result.setNextStep("CONFIRM_CROP_DELETE");
            return result;
        }

        // 5. Check for product deletion
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

        // 6. Check for expense recording
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

        // 7. Check for crop creation
        if (isCropCreate(text)) {
            Map<String, Object> data = extractCropDetails(message);
            AssistantResponse result = response("CROP_CREATE", "Let's register your crop.");
            result.setData(data);
            String nextStep = nextCropStep(data);
            result.setNextStep(nextStep);
            result.setMessage(cropQuestion(nextStep));
            return result;
        }

        // 8. Check for harvest recording
        if (containsAny(text, "harvest", "harvested", "reaped", "record harvest", "record my harvests", "add harvest")) {
            AssistantResponse result = response("HARVEST", "Which crop did you harvest?");
            result.setNextStep("HARVEST_CROP");
            return result;
        }

        // 9. Check for product creation
        if (containsAny(text, "sell", "list product", "upload product", "add product", "put up for sale")) {
            AssistantResponse result = response("PRODUCT_CREATE", "Which product would you like to sell?");
            result.setNextStep("PRODUCT_NAME");
            return result;
        }

        return response("UNKNOWN",
                "I can help you add or delete crops, manage farms, list products, record expenses, record harvests, and check your farm records.\n\n" +
                        "Try:\n" +
                        "• \"Show my crops\"\n" +
                        "• \"I am planting 5 acres of wheat\"\n" +
                        "• \"Delete crop tomato\"\n" +
                        "• \"Help me to add farm\"\n" +
                        "• \"My farms\"\n" +
                        "• \"Record my harvests\"\n" +
                        "• \"Existing stock\"\n" +
                        "• \"I spent ₹500 on fertilizer\"");
    }

    public boolean isGreeting(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim().replaceAll("^[!?,.]+|[!?,.]+$", "").trim();
        if (lower.equals("hi") || lower.equals("hello") || lower.equals("hey")
                || lower.equals("namaste") || lower.equals("help") || lower.equals("start")
                || lower.equals("menu") || lower.equals("options") || lower.equals("commands")
                || lower.equals("vanakkam") || lower.equals("pranam")) {
            return true;
        }
        if (lower.startsWith("hi ") || lower.startsWith("hello ") || lower.startsWith("hey ")
                || lower.startsWith("namaste ") || lower.startsWith("help ")) {
            return true;
        }
        return lower.contains("who are you") || lower.contains("what can you do")
                || lower.contains("good morning") || lower.contains("good afternoon")
                || lower.contains("good evening") || lower.contains("how can you help")
                || lower.contains("how to use");
    }

    public boolean isFarmCreate(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("delete") || lower.contains("remove")) return false;
        return lower.contains("add farm") || lower.contains("new farm") || lower.contains("create farm")
                || lower.contains("register farm") || lower.contains("help me to add farm")
                || lower.contains("record farm");
    }

    public boolean isFarmDelete(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasDelete = containsAny(lower, "delete", "remove", "drop", "erase", "cancel");
        boolean hasFarm = containsAny(lower, "farm", "farms", "land");
        return hasDelete && hasFarm;
    }

    public boolean isFarmQuery(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim().replaceAll("^[!?,.]+|[!?,.]+$", "").trim();
        if (isFarmCreate(lower) || isFarmDelete(lower)) return false;
        if (lower.equals("farms") || lower.equals("farm") || lower.equals("my farms") || lower.equals("my farm")
                || lower.equals("myfarms") || lower.equals("show farms") || lower.equals("list farms")
                || lower.equals("existing farms") || lower.equals("view farms") || lower.equals("all farms")
                || lower.equals("check farms") || lower.equals("farm details") || lower.equals("farm status")) {
            return true;
        }
        boolean hasFarm = containsAny(lower, "farm", "farms", "land");
        boolean hasQuery = containsAny(lower, "my", "show", "list", "tell", "what", "which", "details",
                "status", "records", "record", "current", "existing", "have i", "how many", "view",
                "display", "check", "get", "see", "any", "all", "summary");
        return hasFarm && hasQuery;
    }

    public boolean isHarvestQuery(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim().replaceAll("^[!?,.]+|[!?,.]+$", "").trim();
        if (lower.contains("record harvest") || lower.contains("add harvest") || lower.contains("new harvest")
                || lower.contains("record my harvests")) return false;
        if (lower.equals("harvests") || lower.equals("harvest") || lower.equals("my harvests")
                || lower.equals("my harvest") || lower.equals("show harvests") || lower.equals("list harvests")
                || lower.equals("past harvests") || lower.equals("all harvests") || lower.equals("harvest records")
                || lower.equals("harvest history") || lower.equals("harvest list") || lower.equals("view harvests")) {
            return true;
        }
        boolean hasHarvest = containsAny(lower, "harvest", "harvests", "reaped");
        boolean hasQuery = containsAny(lower, "my", "show", "list", "tell", "what", "which", "past", "history",
                "records", "record", "view", "display", "check", "get", "see", "all", "summary", "how much");
        return hasHarvest && hasQuery;
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
        if (isCropDelete(lower) || isFarmCreate(lower) || isFarmDelete(lower)) return false;
        return containsAny(lower,
                "plant", "planting", "planted",
                "grow", "growing",
                "add crop", "new crop", "record crop", "register crop",
                "sow", "sowing", "sowed", "cultivate", "cultivating");
    }

    public String extractCropDeleteTarget(String message) {
        if (message == null) return null;
        String text = message.trim();

        Matcher idMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?crop\\s+(?:id\\s+|#)?(\\d+)")
                .matcher(text);
        if (idMatcher.find()) {
            return idMatcher.group(1).trim();
        }

        Matcher nameMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?crop\\s+(?:named\\s+|called\\s+)?([a-zA-Z0-9\\s]+)")
                .matcher(text);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim().replaceAll("[,.;!?]+$", "").trim();
            if (!name.isBlank()) return name;
        }

        Matcher nameBeforeMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?([a-zA-Z0-9\\s]+?)\\s+crop")
                .matcher(text);
        if (nameBeforeMatcher.find()) {
            String name = nameBeforeMatcher.group(1).trim().replaceAll("[,.;!?]+$", "").trim();
            if (!name.isBlank()) return name;
        }

        return null;
    }

    public String extractFarmDeleteTarget(String message) {
        if (message == null) return null;
        String text = message.trim();

        Matcher idMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?(?:farm|land)\\s+(?:id\\s+|#)?(\\d+)")
                .matcher(text);
        if (idMatcher.find()) {
            return idMatcher.group(1).trim();
        }

        Matcher nameMatcher = Pattern.compile("(?i)(?:delete|remove|drop|erase)\\s+(?:my\\s+)?(?:farm|land)\\s+(?:named\\s+|called\\s+)?([a-zA-Z0-9\\s]+)")
                .matcher(text);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim().replaceAll("[,.;!?]+$", "").trim();
            if (!name.isBlank()) return name;
        }

        return null;
    }

    public Map<String, Object> extractFarmDetails(String message) {
        Map<String, Object> data = new LinkedHashMap<>();

        // Area & unit
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

        // Location: e.g. "at Guntur", "in Vijayawada", "location Guntur"
        Matcher locMatcher = Pattern.compile("(?i)(?:at|in|location)\\s+([a-zA-Z0-9\\s]+?)(?=\\s+(?:in|on|with|area|acres?|ha|hectares?|cents?)|[,.;]|$)")
                .matcher(message);
        if (locMatcher.find()) {
            String loc = locMatcher.group(1).trim();
            if (!containsAny(loc.toLowerCase(Locale.ROOT), "acres", "acres", "hectares", "farm", "my")) {
                data.put("location", titleCase(loc));
            }
        }

        // Farm name: e.g. "add farm Green Valley", "farm named Sunrise"
        Matcher nameMatcher = Pattern.compile("(?i)(?:add farm|new farm|create farm|register farm|farm named|farm called)[:\\s]+([a-zA-Z0-9\\s]+?)(?=\\s+(?:at|in|with|area|acres?|ha|hectares?|cents?)|[,.;]|$)")
                .matcher(message);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim();
            if (!containsAny(name.toLowerCase(Locale.ROOT), "today", "here", "farm", "land") && !name.matches("\\d+")) {
                data.put("farmName", titleCase(name));
            }
        }

        // Defaults
        data.put("soilType", "Loamy soil");
        data.put("irrigationMethod", "Drip irrigation");
        if (!data.containsKey("areaUnit")) {
            data.put("areaUnit", "Acres");
        }

        return data;
    }

    public String nextFarmStep(Map<String, Object> data) {
        if (!data.containsKey("farmName") || blank(data.get("farmName"))) {
            return "FARM_NAME";
        }
        if (!data.containsKey("location") || blank(data.get("location"))) {
            return "FARM_LOCATION";
        }
        if (!data.containsKey("area") || blank(data.get("area"))) {
            return "FARM_AREA";
        }
        return "CONFIRM_FARM";
    }

    public String farmQuestion(String step) {
        switch (step) {
            case "FARM_NAME":
                return "What is the name of your farm? (e.g. Green Valley Farm, Sunrise Acres)";
            case "FARM_LOCATION":
                return "What is the location or village of the farm? (e.g. Guntur, Vijayawada)";
            case "FARM_AREA":
                return "How much total land area is this farm? (e.g. 10 acres, 5 hectares)";
            case "CONFIRM_FARM":
                return "All details are collected. Reply YES to save or NO to cancel.";
            default:
                return "Please provide the requested farm detail.";
        }
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

        // Try direct extraction after "acres of <crop>", "ha of <crop>", "cents of <crop>", or "<number> acres <crop>"
        Matcher mOf = Pattern.compile("(?i)(?:acres?|hectares?|cents?|ha|ac)\\s+(?:of\\s+)?([a-zA-Z\\s]+?)(?=\\s+(?:with|today|yesterday|on|in|using|for)|[,.;]|$)")
                .matcher(message);
        if (mOf.find()) {
            String candidate = mOf.group(1).trim();
            if (!candidate.isBlank() && !containsAny(candidate.toLowerCase(Locale.ROOT), "today", "yesterday", "crop", "crops", "land", "my")) {
                extractedCropName = candidate;
            }
        }

        if (extractedCropName == null || extractedCropName.isBlank()) {
            Matcher mPlant = Pattern.compile("(?i)\\b(?:planting|plant|planted|grow|growing|sow|sowing|sowed|cultivating)\\s+(?:some\\s+|a\\s+|the\\s+)?([a-zA-Z\\s]+?)(?=\\s+(?:in|on|with|across|over|area|acres?|ha|hectares?|cents?|today|yesterday|\\d)|[,.;]|$)")
                    .matcher(message);
            if (mPlant.find()) {
                String candidate = mPlant.group(1).trim();
                if (!candidate.isBlank() && !containsAny(candidate.toLowerCase(Locale.ROOT), "today", "yesterday", "crop", "crops", "land", "my")) {
                    extractedCropName = candidate;
                }
            }
        }

        if (extractedCropName == null || extractedCropName.isBlank()) {
            Matcher mAdd = Pattern.compile("(?i)\\b(?:add crop|new crop|record crop|register crop)[:\\s]+(?:a\\s+|the\\s+)?([a-zA-Z\\s]+?)(?=\\s+(?:in|on|with|of|area|acres?|ha|hectares?|cents?|today|yesterday|\\d)|[,.;]|$)")
                    .matcher(message);
            if (mAdd.find()) {
                String candidate = mAdd.group(1).trim();
                if (!candidate.isBlank() && !containsAny(candidate.toLowerCase(Locale.ROOT), "today", "yesterday", "crop", "crops", "land", "my")) {
                    extractedCropName = candidate;
                }
            }
        }

        // Common crop dictionary lookup as high-accuracy fallback or match
        String[][] commonCrops = {
                {"wheat", "Wheat"}, {"rice", "Rice"}, {"paddy", "Paddy"},
                {"tomato", "Tomato"}, {"tomatoes", "Tomato"},
                {"potato", "Potato"}, {"potatoes", "Potato"},
                {"onion", "Onion"}, {"onions", "Onion"},
                {"maize", "Maize"}, {"corn", "Corn"},
                {"cotton", "Cotton"}, {"sugarcane", "Sugarcane"},
                {"chilli", "Chilli"}, {"chillies", "Chilli"}, {"chili", "Chili"},
                {"groundnut", "Groundnut"}, {"peanut", "Groundnut"},
                {"soybean", "Soybean"}, {"mustard", "Mustard"},
                {"barley", "Barley"}, {"millet", "Millet"}, {"ragi", "Ragi"},
                {"jowar", "Jowar"}, {"bajra", "Bajra"}, {"sunflower", "Sunflower"},
                {"turmeric", "Turmeric"}, {"ginger", "Ginger"}, {"garlic", "Garlic"},
                {"carrot", "Carrot"}, {"cabbage", "Cabbage"}, {"cauliflower", "Cauliflower"},
                {"brinjal", "Brinjal"}, {"eggplant", "Brinjal"}, {"okra", "Okra"},
                {"mango", "Mango"}, {"banana", "Banana"}, {"apple", "Apple"},
                {"orange", "Orange"}, {"grapes", "Grapes"}, {"watermelon", "Watermelon"}
        };

        for (String[] pair : commonCrops) {
            if (text.matches("(?s).*\\b" + pair[0] + "\\b.*")) {
                extractedCropName = pair[1];
                break;
            }
        }

        if (extractedCropName != null && !extractedCropName.isBlank()) {
            data.put("cropName", titleCase(extractedCropName.trim()));
        }

        // 3. Extract Planting Date
        Matcher date = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b").matcher(message);
        try {
            if (date.find()) {
                data.put("plantingDate", LocalDate.parse(date.group()).toString());
            } else if (text.contains("yesterday")) {
                data.put("plantingDate", LocalDate.now().minusDays(1).toString());
            } else {
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
