package com.agriculture.backend.Service;

import com.agriculture.backend.dto.AssistantResponse;
import com.agriculture.backend.entity.Crop;
import com.agriculture.backend.entity.FarmerExpense;
import com.agriculture.backend.entity.Harvest;
import com.agriculture.backend.entity.Product;
import com.agriculture.backend.repository.CropRepository;
import com.agriculture.backend.repository.FarmerExpenseRepository;
import com.agriculture.backend.repository.HarvestRepository;
import com.agriculture.backend.repository.ProductRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FarmerAssistantService {

    private final FarmerTextParser parser;
    private final CropRepository cropRepository;
    private final FarmerExpenseRepository expenseRepository;
    private final ProductRepository productRepository;
    private final HarvestRepository harvestRepository;

    private final Map<String, Conversation> conversations =
            new ConcurrentHashMap<>();

    public FarmerAssistantService(
            FarmerTextParser parser,
            CropRepository cropRepository,
            FarmerExpenseRepository expenseRepository,
            ProductRepository productRepository,
            HarvestRepository harvestRepository) {

        this.parser = parser;
        this.cropRepository = cropRepository;
        this.expenseRepository = expenseRepository;
        this.productRepository = productRepository;
        this.harvestRepository = harvestRepository;
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

        if (message == null || message.isBlank()) {
            return response("UNKNOWN", "Please enter a message.");
        }

        String text = message.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        Conversation conversation =
                conversationId == null || conversationId.isBlank()
                        ? null
                        : conversations.get(conversationId);

        if (conversation != null
                && !conversation.farmerId.equals(farmerId)) {
            return response(
                    "UNKNOWN",
                    "This conversation does not belong to your account.");
        }

        if (cancelled || isNo(text)) {
            if (conversationId != null) {
                conversations.remove(conversationId);
            }
            return response("CANCELLED", "Cancelled. Nothing was saved.");
        }

        // Continue the same guided conversation.
        if (conversation != null) {
            if (conversation.step.startsWith("CONFIRM_")) {
                if (confirmed || isYes(text)) {
                    return saveAction(conversation, conversationId);
                }

                return question(
                        conversation,
                        conversationId,
                        "Please reply YES to save or NO to cancel.");
            }

            return continueConversation(
                    conversation, text, conversationId);
        }

        // Read-only questions are answered from saved database records.
        if (isExpenseSummaryQuestion(lower)) {
            return answerExpenseQuestion(farmerId, lower);
        }

        if (isStockQuestion(lower)) {
            return answerStockQuestion(farmerId, lower);
        }

        if (isCropQuery(lower)) {
            return answerCropQuestion(farmerId, lower);
        }

        if (isHarvestRequest(lower)) {
            return startHarvestConversation(farmerId, text);
        }

        if (isProductCreateRequest(lower)) {
            return startProductConversation(farmerId, text);
        }

        AssistantResponse parsed = parser.parse(text);

        if ("CROP_CREATE".equals(parsed.getIntent())) {
            Conversation crop =
                    new Conversation(farmerId, "CROP_CREATE");

            if (parsed.getData() != null) {
                crop.data.putAll(parsed.getData());
            }

            crop.step = parser.nextCropStep(crop.data);

            String id = UUID.randomUUID().toString();
            conversations.put(id, crop);

            if ("CONFIRM_CROP".equals(crop.step)) {
                return cropSummary(crop, id);
            }

            return question(
                    crop, id, parser.cropQuestion(crop.step));
        }

        if ("EXPENSE".equals(parsed.getIntent())) {
            if (parsed.getAmount() == null
                    || parsed.getAmount() <= 0
                    || parsed.getCategory() == null) {
                return response(
                        "EXPENSE",
                        "Please include an amount and expense category. " +
                                "Example: I spent ₹500 on fertilizer.");
            }

            Conversation expense =
                    new Conversation(farmerId, "EXPENSE");

            expense.data.put("amount", parsed.getAmount());
            expense.data.put("category", parsed.getCategory());
            expense.data.put("description", text);
            expense.step = "CONFIRM_EXPENSE";

            String id = UUID.randomUUID().toString();
            conversations.put(id, expense);

            AssistantResponse result = question(
                    expense,
                    id,
                    "Save this expense?\n\n" +
                            "Category: " + expense.data.get("category") + "\n" +
                            "Amount: ₹" + expense.data.get("amount") + "\n" +
                            "Description: " + expense.data.get("description") +
                            "\n\nReply YES to save or NO to cancel.");

            result.setRequiresConfirmation(true);
            return result;
        }

        return response(
                "UNKNOWN",
                "I can help you add crops, list products, record expenses, " +
                        "record harvests, and check your saved farm records.\n\n" +
                        "Try: \"I am planting tomatoes in 5 acres\" or " +
                        "\"I spent ₹500 on fertilizer\".");
    }

    // ---------------------------------------------------------
    // PRODUCT CREATION
    // ---------------------------------------------------------

    private AssistantResponse startProductConversation(
            Long farmerId, String text) {

        Conversation c = new Conversation(farmerId, "PRODUCT_CREATE");

        Matcher quantityMatcher = Pattern.compile(
                        "(?i)(\\d+(?:\\.\\d+)?)\\s*(?:kg|kgs|kilograms?)\\b")
                .matcher(text);

        if (quantityMatcher.find()) {
            c.data.put(
                    "quantity",
                    Double.parseDouble(quantityMatcher.group(1)));
        }

        Matcher priceMatcher = Pattern.compile(
                        "(?i)(?:₹|rs\\.?\\s*)?(\\d+(?:\\.\\d{1,2})?)" +
                                "\\s*(?:rupees?|rs\\.?)?\\s*(?:/\\s*kg|per\\s*kg)")
                .matcher(text);

        if (priceMatcher.find()) {
            c.data.put(
                    "price",
                    Double.parseDouble(priceMatcher.group(1)));
        }

        String productName = extractProductName(text);

        if (productName != null) {
            c.data.put("productName", productName);
        }

        c.step = nextProductStep(c.data);

        String id = UUID.randomUUID().toString();
        conversations.put(id, c);

        if ("CONFIRM_PRODUCT".equals(c.step)) {
            return productSummary(c, id);
        }

        return question(c, id, productQuestion(c.step));
    }

    private String nextProductStep(Map<String, Object> data) {
        if (blank(data.get("productName"))) return "PRODUCT_NAME";
        if (blank(data.get("category"))) return "PRODUCT_CATEGORY";
        if (blank(data.get("quantity"))) return "PRODUCT_QUANTITY";
        if (blank(data.get("price"))) return "PRODUCT_PRICE";
        if (blank(data.get("description"))) return "PRODUCT_DESCRIPTION";
        return "CONFIRM_PRODUCT";
    }

    private String productQuestion(String step) {
        switch (step) {
            case "PRODUCT_NAME":
                return "Which product do you want to list for sale?";
            case "PRODUCT_CATEGORY":
                return "What category does the product belong to?";
            case "PRODUCT_QUANTITY":
                return "What quantity is available? For example, 100.";
            case "PRODUCT_PRICE":
                return "What is the selling price? Enter the number only.";
            case "PRODUCT_DESCRIPTION":
                return "Add a short product description.";
            default:
                return "Please provide the product details.";
        }
    }

    private AssistantResponse productSummary(
            Conversation c, String id) {

        AssistantResponse result = question(
                c,
                id,
                "Please confirm your product listing:\n\n" +
                        "Product: " + c.data.get("productName") + "\n" +
                        "Category: " + c.data.get("category") + "\n" +
                        "Quantity: " + c.data.get("quantity") + "\n" +
                        "Price: ₹" + c.data.get("price") + "\n" +
                        "Description: " + c.data.get("description") + "\n\n" +
                        "Reply YES to save or NO to cancel.");

        result.setRequiresConfirmation(true);
        result.setNextStep("CONFIRM_PRODUCT");
        return result;
    }

    // ---------------------------------------------------------
    // HARVEST CREATION
    // ---------------------------------------------------------

    private AssistantResponse startHarvestConversation(
            Long farmerId, String text) {

        List<Crop> crops = cropRepository.findByFarmerId(farmerId);

        if (crops.isEmpty()) {
            return response(
                    "HARVEST",
                    "You have no crop records yet. Add a crop before recording a harvest.");
        }

        Conversation c = new Conversation(farmerId, "HARVEST");

        String requestedCrop = extractProductName(text);

        Crop selectedCrop = null;

        if (requestedCrop != null) {
            for (Crop crop : crops) {
                if (crop.getCropName() != null
                        && crop.getCropName().equalsIgnoreCase(requestedCrop)) {
                    selectedCrop = crop;
                    break;
                }
            }
        }

        if (selectedCrop == null && crops.size() == 1) {
            selectedCrop = crops.get(0);
        }

        if (selectedCrop != null) {
            if ("Harvested".equalsIgnoreCase(selectedCrop.getCropStatus())) {
                return response(
                        "HARVEST",
                        selectedCrop.getCropName() +
                                " is already marked as harvested in your crop record.");
            }

            c.data.put("cropId", selectedCrop.getId());
            c.data.put("cropName", selectedCrop.getCropName());
            c.step = "HARVEST_QUANTITY";
        } else {
            c.step = "HARVEST_CROP";
        }

        String id = UUID.randomUUID().toString();
        conversations.put(id, c);

        return question(c, id, harvestQuestion(c));
    }

    private String harvestQuestion(Conversation c) {
        switch (c.step) {
            case "HARVEST_CROP":
                List<Crop> crops =
                        cropRepository.findByFarmerId(c.farmerId);

                StringBuilder names = new StringBuilder(
                        "Which crop did you harvest? Choose a crop name from your records:\n");

                for (Crop crop : crops) {
                    names.append("- ")
                            .append(crop.getCropName())
                            .append(" (ID: ")
                            .append(crop.getId())
                            .append(", status: ")
                            .append(crop.getCropStatus())
                            .append(")\n");
                }

                return names.toString();

            case "HARVEST_QUANTITY":
                return "How much did you harvest? Enter a quantity, such as 20.";

            case "HARVEST_UNIT":
                return "What is the quantity unit? For example, kg, quintal, or tonnes.";

            case "HARVEST_PRICE":
                return "What is the selling price per unit? Enter 0 if it has not been sold yet.";

            case "CONFIRM_HARVEST":
                return "Reply YES to save the harvest or NO to cancel.";

            default:
                return "Please provide the harvest details.";
        }
    }

    // ---------------------------------------------------------
    // CONTINUE GUIDED CONVERSATIONS
    // ---------------------------------------------------------

    private AssistantResponse continueConversation(
            Conversation c, String answer, String id) {

        if (c.intent.equals("PRODUCT_CREATE")) {
            switch (c.step) {
                case "PRODUCT_NAME":
                    c.data.put("productName", answer);
                    break;
                case "PRODUCT_CATEGORY":
                    c.data.put("category", answer);
                    break;
                case "PRODUCT_QUANTITY":
                    if (!putPositiveNumber(c.data, "quantity", answer)) {
                        return question(c, id,
                                "Enter a positive quantity, such as 100.");
                    }
                    break;
                case "PRODUCT_PRICE":
                    if (!putNonNegativeNumber(c.data, "price", answer)) {
                        return question(c, id,
                                "Enter a valid price, such as 25.");
                    }
                    break;
                case "PRODUCT_DESCRIPTION":
                    c.data.put("description", answer);
                    break;
                default:
                    return question(c, id, productQuestion(c.step));
            }

            c.step = nextProductStep(c.data);

            return "CONFIRM_PRODUCT".equals(c.step)
                    ? productSummary(c, id)
                    : question(c, id, productQuestion(c.step));
        }

        if (c.intent.equals("HARVEST")) {
            switch (c.step) {
                case "HARVEST_CROP":
                    Crop selected = findCropByName(c.farmerId, answer);

                    if (selected == null) {
                        return question(
                                c, id,
                                "I couldn't find that crop in your records. " +
                                        "Enter the crop name exactly as shown in the list.");
                    }

                    if ("Harvested".equalsIgnoreCase(selected.getCropStatus())) {
                        return question(
                                c, id,
                                "That crop is already marked as harvested. " +
                                        "Please choose another crop.");
                    }

                    c.data.put("cropId", selected.getId());
                    c.data.put("cropName", selected.getCropName());
                    c.step = "HARVEST_QUANTITY";
                    break;

                case "HARVEST_QUANTITY":
                    if (!putPositiveNumber(c.data, "quantity", answer)) {
                        return question(c, id,
                                "Enter a positive harvest quantity, such as 20.");
                    }
                    c.step = "HARVEST_UNIT";
                    break;

                case "HARVEST_UNIT":
                    c.data.put("quantityUnit", answer.trim());
                    c.step = "HARVEST_PRICE";
                    break;

                case "HARVEST_PRICE":
                    if (!putNonNegativeNumber(c.data, "sellingPrice", answer)) {
                        return question(c, id,
                                "Enter a valid price, or enter 0 if not sold.");
                    }
                    c.step = "CONFIRM_HARVEST";
                    return harvestSummary(c, id);

                default:
                    return question(c, id, harvestQuestion(c));
            }

            return question(c, id, harvestQuestion(c));
        }

        if (c.intent.equals("CROP_CREATE")) {
            switch (c.step) {
                case "CROP_NAME":
                    c.data.put("cropName", answer.trim());
                    break;
                case "PLANTING_DATE":
                    String plantingDate = parseDate(answer);
                    if (plantingDate == null) {
                        return question(c, id,
                                "Enter the date as YYYY-MM-DD, today, or yesterday.");
                    }
                    c.data.put("plantingDate", plantingDate);
                    break;
                case "SOIL_TYPE":
                    c.data.put("soilType", answer.trim());
                    break;
                case "AREA":
                    Matcher areaMatcher = Pattern.compile(
                            "(\\d+(?:\\.\\d+)?)").matcher(answer);
                    if (!areaMatcher.find()
                            || Double.parseDouble(areaMatcher.group(1)) <= 0) {
                        return question(c, id,
                                "Enter a positive area, such as 5 acres.");
                    }
                    c.data.put("area",
                            Double.parseDouble(areaMatcher.group(1)));

                    String areaText = answer.toLowerCase(Locale.ROOT);
                    if (areaText.contains("acre")) {
                        c.data.put("areaUnit", "Acres");
                    } else if (areaText.contains("hectare")) {
                        c.data.put("areaUnit", "Hectares");
                    } else if (areaText.contains("cent")) {
                        c.data.put("areaUnit", "Cents");
                    }
                    break;
                case "AREA_UNIT":
                    String unit = answer.toLowerCase(Locale.ROOT);
                    if (unit.contains("acre")) {
                        c.data.put("areaUnit", "Acres");
                    } else if (unit.contains("hectare")) {
                        c.data.put("areaUnit", "Hectares");
                    } else if (unit.contains("cent")) {
                        c.data.put("areaUnit", "Cents");
                    } else {
                        return question(c, id,
                                "Enter acres, hectares, or cents.");
                    }
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
                    String expectedDate = parseDate(answer);
                    if (expectedDate == null) {
                        return question(c, id,
                                "Enter the expected harvest date as YYYY-MM-DD.");
                    }
                    c.data.put("expectedHarvestDate", expectedDate);
                    break;
                default:
                    return question(c, id, parser.cropQuestion(c.step));
            }

            c.step = parser.nextCropStep(c.data);

            return "CONFIRM_CROP".equals(c.step)
                    ? cropSummary(c, id)
                    : question(c, id, parser.cropQuestion(c.step));
        }

        if (c.intent.equals("EXPENSE")) {
            return question(
                    c, id,
                    "Reply YES to save this expense or NO to cancel.");
        }

        return response("UNKNOWN", "I couldn't continue that action.");
    }

    // ---------------------------------------------------------
    // SAVE CONFIRMED ACTIONS
    // ---------------------------------------------------------

    @Transactional
    private AssistantResponse saveAction(Conversation c, String id) {

        if (c.intent.equals("PRODUCT_CREATE")) {
            Product product = new Product();
            product.setFarmerId(c.farmerId);
            product.setProductName(String.valueOf(c.data.get("productName")));
            product.setCategory(String.valueOf(c.data.get("category")));
            product.setQuantity(((Number) c.data.get("quantity")).doubleValue());
            product.setPrice(((Number) c.data.get("price")).doubleValue());
            product.setDescription(String.valueOf(c.data.get("description")));

            Product saved = productRepository.save(product);
            conversations.remove(id);

            return completed(
                    "PRODUCT_SAVED",
                    "Product listed successfully: " +
                            saved.getProductName() + ". Product ID: " + saved.getId(),
                    id);
        }

        if (c.intent.equals("EXPENSE")) {
            FarmerExpense expense = new FarmerExpense();
            expense.setFarmerId(c.farmerId);
            expense.setAmount(((Number) c.data.get("amount")).doubleValue());
            expense.setCategory(String.valueOf(c.data.get("category")));
            expense.setDescription(String.valueOf(c.data.get("description")));
            expense.setExpenseDate(LocalDate.now());

            FarmerExpense saved = expenseRepository.save(expense);
            conversations.remove(id);

            return completed(
                    "EXPENSE_SAVED",
                    "Expense saved successfully. Amount: ₹" +
                            saved.getAmount() + ", category: " +
                            saved.getCategory() + ".",
                    id);
        }

        if (c.intent.equals("CROP_CREATE")) {
            Crop crop = new Crop();
            crop.setFarmerId(c.farmerId);
            crop.setCropName(String.valueOf(c.data.get("cropName")));
            crop.setPlantingDate(String.valueOf(c.data.get("plantingDate")));
            crop.setSoilType(String.valueOf(c.data.get("soilType")));
            crop.setArea(((Number) c.data.get("area")).doubleValue());
            crop.setAreaUnit(String.valueOf(c.data.get("areaUnit")));
            crop.setVariety(String.valueOf(c.data.get("variety")));
            crop.setSeedSource(String.valueOf(c.data.get("seedSource")));
            crop.setFertilizerUsed(String.valueOf(c.data.get("fertilizerUsed")));
            crop.setIrrigationMethod(String.valueOf(c.data.get("irrigationMethod")));
            crop.setExpectedHarvestDate(
                    String.valueOf(c.data.get("expectedHarvestDate")));
            crop.setCropStatus("Growing");

            Crop saved = cropRepository.save(crop);
            conversations.remove(id);

            return completed(
                    "CROP_SAVED",
                    "Crop saved successfully: " + saved.getCropName() +
                            ". Crop ID: " + saved.getId(),
                    id);
        }

        if (c.intent.equals("HARVEST")) {
            Long cropId = ((Number) c.data.get("cropId")).longValue();

            Crop crop = cropRepository.findById(cropId)
                    .orElseThrow(() ->
                            new IllegalArgumentException("Crop not found."));

            if (!crop.getFarmerId().equals(c.farmerId)) {
                return response("HARVEST", "That crop does not belong to your account.");
            }

            if ("Harvested".equalsIgnoreCase(crop.getCropStatus())) {
                conversations.remove(id);
                return response("HARVEST",
                        "This crop is already marked as harvested.");
            }

            double quantity =
                    ((Number) c.data.get("quantity")).doubleValue();

            double sellingPrice =
                    ((Number) c.data.get("sellingPrice")).doubleValue();

            if (quantity <= 0 || sellingPrice < 0) {
                return response("HARVEST", "The harvest details are invalid.");
            }

            Harvest harvest = new Harvest();
            harvest.setCropId(crop.getId());
            harvest.setFarmerId(c.farmerId);
            harvest.setCropName(crop.getCropName());
            harvest.setVariety(crop.getVariety());
            harvest.setPlantingDate(crop.getPlantingDate());
            harvest.setHarvestDate(LocalDate.now().toString());
            harvest.setQuantity(quantity);
            harvest.setQuantityUnit(String.valueOf(c.data.get("quantityUnit")));
            harvest.setSellingPrice(sellingPrice);
            harvest.setRevenue(quantity * sellingPrice);

            Harvest saved = harvestRepository.save(harvest);

            // Preserve the existing application's harvest rule:
            // saving a harvest marks this crop as Harvested.
            crop.setCropStatus("Harvested");
            cropRepository.save(crop);

            conversations.remove(id);

            return completed(
                    "HARVEST_SAVED",
                    "Harvest saved successfully for " + saved.getCropName() +
                            ". Quantity: " + saved.getQuantity() + " " +
                            saved.getQuantityUnit() + ". Revenue recorded: ₹" +
                            saved.getRevenue() + ".",
                    id);
        }

        return response("UNKNOWN", "This action cannot be saved.");
    }

    // ---------------------------------------------------------
    // DATABASE READS
    // ---------------------------------------------------------

    private AssistantResponse answerStockQuestion(
            Long farmerId, String text) {

        List<Product> products = productRepository.findByFarmerId(farmerId);
        String requested = extractProductName(text);

        List<Product> matches = new ArrayList<>();

        for (Product product : products) {
            if (requested == null ||
                    (product.getProductName() != null &&
                            product.getProductName().toLowerCase(Locale.ROOT)
                                    .contains(requested.toLowerCase(Locale.ROOT)))) {
                matches.add(product);
            }
        }

        if (matches.isEmpty()) {
            return response(
                    "STOCK",
                    requested == null
                            ? "You don't have any product listings recorded yet."
                            : "I couldn't find a listing for " + requested +
                            " in your products.");
        }

        StringBuilder answer = new StringBuilder("Your saved product listings:\n");

        for (Product product : matches) {
            answer.append("- ")
                    .append(product.getProductName())
                    .append(": quantity ")
                    .append(product.getQuantity())
                    .append(", price ₹")
                    .append(product.getPrice())
                    .append("\n");
        }

        answer.append(
                "\nThe product table does not store a quantity unit, " +
                        "so I am showing the quantity exactly as recorded.");

        return response("STOCK", answer.toString());
    }

    private AssistantResponse answerCropQuestion(
            Long farmerId, String text) {

        List<Crop> crops = cropRepository.findByFarmerId(farmerId);

        if (crops.isEmpty()) {
            return response("CROPS", "You don't have any crop records yet.");
        }

        String requested = extractProductName(text);
        StringBuilder answer = new StringBuilder();

        for (Crop crop : crops) {
            if (requested != null && crop.getCropName() != null
                    && !crop.getCropName().toLowerCase(Locale.ROOT)
                    .contains(requested.toLowerCase(Locale.ROOT))) {
                continue;
            }

            answer.append("- ")
                    .append(crop.getCropName())
                    .append(" | Status: ")
                    .append(crop.getCropStatus())
                    .append(" | Area: ")
                    .append(crop.getArea())
                    .append(" ")
                    .append(crop.getAreaUnit())
                    .append("\n");
        }

        if (answer.isEmpty()) {
            return response("CROPS",
                    "I couldn't find that crop in your records.");
        }

        return response("CROPS", "Your crop records:\n" + answer);
    }

    private AssistantResponse answerExpenseQuestion(
            Long farmerId, String text) {

        List<FarmerExpense> expenses =
                expenseRepository.findByFarmerId(farmerId);

        String category = detectExpenseCategory(text);
        LocalDate today = LocalDate.now();
        boolean thisMonth = text.contains("this month");

        double total = 0;
        int count = 0;

        for (FarmerExpense expense : expenses) {
            if (category != null
                    && (expense.getCategory() == null
                    || !expense.getCategory().toLowerCase(Locale.ROOT)
                    .contains(category))) {
                continue;
            }

            if (thisMonth && (expense.getExpenseDate() == null
                    || expense.getExpenseDate().getYear() != today.getYear()
                    || expense.getExpenseDate().getMonthValue()
                    != today.getMonthValue())) {
                continue;
            }

            total += expense.getAmount();
            count++;
        }

        String period = thisMonth ? " this month" : "";
        String categoryText = category == null ? "all categories" : category;

        return response(
                "EXPENSE_SUMMARY",
                "Your recorded expenses for " + categoryText + period +
                        " total ₹" + String.format(Locale.ROOT, "%.2f", total) +
                        " across " + count + " record(s).");
    }

    // ---------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------

    private AssistantResponse harvestSummary(Conversation c, String id) {
        double quantity = ((Number) c.data.get("quantity")).doubleValue();
        double price = ((Number) c.data.get("sellingPrice")).doubleValue();

        AssistantResponse result = question(
                c, id,
                "Please confirm your harvest:\n\n" +
                        "Crop: " + c.data.get("cropName") + "\n" +
                        "Quantity: " + quantity + " " + c.data.get("quantityUnit") + "\n" +
                        "Selling price per unit: ₹" + price + "\n" +
                        "Estimated revenue: ₹" + (quantity * price) +
                        "\n\nReply YES to save or NO to cancel.");

        result.setRequiresConfirmation(true);
        result.setNextStep("CONFIRM_HARVEST");
        return result;
    }

    private AssistantResponse cropSummary(Conversation c, String id) {
        AssistantResponse result = question(
                c, id,
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
                        "\n\nReply YES to save or NO to cancel.");

        result.setRequiresConfirmation(true);
        result.setNextStep("CONFIRM_CROP");
        return result;
    }

    private AssistantResponse question(
            Conversation c, String id, String message) {

        AssistantResponse result = response(c.intent, message);
        result.setConversationId(id);
        result.setNextStep(c.step);
        result.setData(new LinkedHashMap<>(c.data));
        return result;
    }

    private AssistantResponse completed(
            String intent, String message, String id) {

        AssistantResponse result = response(intent, message);
        result.setConversationId(id);
        result.setNextStep("COMPLETED");
        result.setRequiresConfirmation(false);
        return result;
    }

    private AssistantResponse response(String intent, String message) {
        return new AssistantResponse(intent, message, false, null, null);
    }

    private Crop findCropByName(Long farmerId, String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);

        for (Crop crop : cropRepository.findByFarmerId(farmerId)) {
            if (crop.getCropName() != null
                    && crop.getCropName().trim().equalsIgnoreCase(wanted)) {
                return crop;
            }
        }

        return null;
    }

    private boolean putPositiveNumber(
            Map<String, Object> data, String key, String answer) {

        return putNumber(data, key, answer, false);
    }

    private boolean putNonNegativeNumber(
            Map<String, Object> data, String key, String answer) {

        return putNumber(data, key, answer, true);
    }

    private boolean putNumber(
            Map<String, Object> data,
            String key,
            String answer,
            boolean allowZero) {

        Matcher matcher = Pattern.compile(
                "(\\d+(?:\\.\\d+)?)").matcher(answer);

        if (!matcher.find()) return false;

        try {
            double value = Double.parseDouble(matcher.group(1));

            if (!Double.isFinite(value)
                    || (allowZero ? value < 0 : value <= 0)) {
                return false;
            }

            data.put(key, value);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private String parseDate(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);

        if (text.equals("today")) return LocalDate.now().toString();
        if (text.equals("yesterday")) {
            return LocalDate.now().minusDays(1).toString();
        }

        try {
            return LocalDate.parse(value.trim()).toString();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String extractProductName(String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        String[][] names = {
                {"tomatoes", "Tomato"}, {"tomato", "Tomato"},
                {"potatoes", "Potato"}, {"potato", "Potato"},
                {"onions", "Onion"}, {"onion", "Onion"},
                {"mangoes", "Mango"}, {"mango", "Mango"},
                {"chillies", "Chilli"}, {"chilli", "Chilli"},
                {"wheat", "Wheat"}, {"rice", "Rice"},
                {"maize", "Maize"}, {"corn", "Corn"},
                {"cotton", "Cotton"}
        };

        for (String[] pair : names) {
            if (lower.matches("(?s).*\\b" + pair[0] + "\\b.*")) {
                return pair[1];
            }
        }

        return null;
    }

    private String detectExpenseCategory(String text) {
        if (text.contains("fertilizer") || text.contains("fertiliser")) {
            return "fertilizer";
        }
        if (text.contains("seed")) return "seed";
        if (text.contains("pesticide")) return "pesticide";
        if (text.contains("labour") || text.contains("labor")
                || text.contains("worker")) return "labour";
        if (text.contains("irrigation") || text.contains("water")) {
            return "irrigation";
        }
        return null;
    }

    private boolean isExpenseSummaryQuestion(String text) {
        return (text.contains("how much") || text.contains("total spent")
                || text.contains("total expense")
                || text.contains("expenses so far")
                || text.contains("expense total"))
                && (text.contains("spent") || text.contains("expense")
                || text.contains("fertilizer") || text.contains("fertiliser")
                || text.contains("seed") || text.contains("pesticide")
                || text.contains("labour") || text.contains("labor"));
    }

    private boolean isStockQuestion(String text) {
        return (text.contains("stock") || text.contains("inventory")
                || text.contains("available quantity")
                || text.contains("how many"))
                && (text.contains("product") || text.contains("tomato")
                || text.contains("rice") || text.contains("wheat")
                || text.contains("potato") || text.contains("stock")
                || text.contains("inventory"));
    }

    private boolean isCropQuery(String text) {
        return text.contains("what crops")
                || text.contains("my crops")
                || text.contains("crops am i growing")
                || text.contains("which crops")
                || text.contains("crop status")
                || text.contains("what is the status of my")
                || text.contains("show my crops");
    }

    private boolean isHarvestRequest(String text) {
        return text.matches("(?s).*(\\bharvest\\b|\\bharvested\\b|\\breaped\\b).*");
    }

    private boolean isProductCreateRequest(String text) {
        return text.contains("sell")
                || text.contains("list product")
                || text.contains("upload product")
                || text.contains("add product")
                || text.contains("put up for sale")
                || text.contains("product for sale")
                || text.contains("list my");
    }

    private boolean isYes(String value) {
        return value != null && value.trim().matches(
                "(?i)yes|y|confirm|confirmed|save|save it");
    }

    private boolean isNo(String value) {
        return value != null && value.trim().matches(
                "(?i)no|n|cancel|stop|no thanks");
    }

    private boolean blank(Object value) {
        return value == null || value.toString().isBlank();
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