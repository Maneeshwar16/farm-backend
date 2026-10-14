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
            return response("CANCELLED", "Cancelled. Nothing was saved or changed.");
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
                        "Please reply YES to proceed or NO to cancel.");
            }

            return continueConversation(
                    conversation, text, conversationId);
        }

        // 1. Crop deletion request
        if (parser.isCropDelete(lower)) {
            String target = parser.extractCropDeleteTarget(text);
            return startCropDeleteConversation(farmerId, target);
        }

        // 2. Read-only crop questions answered from saved database records
        if (isCropQuery(lower)) {
            return answerCropQuestion(farmerId, lower);
        }

        // 3. Expense summary questions
        if (isExpenseSummaryQuestion(lower)) {
            return answerExpenseQuestion(farmerId, lower);
        }

        // 4. Stock / product summary questions
        if (isStockQuestion(lower)) {
            return answerStockQuestion(farmerId, lower);
        }

        // 5. Harvest requests
        if (isHarvestRequest(lower)) {
            return startHarvestConversation(farmerId, text);
        }

        // 6. Product creation requests
        if (isProductCreateRequest(lower)) {
            return startProductConversation(farmerId, text);
        }

        // 7. Parse message using FarmerTextParser
        AssistantResponse parsed = parser.parse(text);

        if ("CROP_DELETE".equals(parsed.getIntent())) {
            String target = parsed.getData() != null ? String.valueOf(parsed.getData().get("target")) : null;
            return startCropDeleteConversation(farmerId, target);
        }

        if ("CROP_CREATE".equals(parsed.getIntent())) {
            Conversation crop = new Conversation(farmerId, "CROP_CREATE");

            if (parsed.getData() != null) {
                crop.data.putAll(parsed.getData());
            }

            crop.step = parser.nextCropStep(crop.data);
            String id = UUID.randomUUID().toString();
            conversations.put(id, crop);

            if ("CONFIRM_CROP".equals(crop.step)) {
                return cropSummary(crop, id);
            }

            return question(crop, id, parser.cropQuestion(crop.step));
        }

        if ("PRODUCT_DELETE".equals(parsed.getIntent())) {
            String prodName = parsed.getData() != null ? String.valueOf(parsed.getData().get("productName")) : null;
            return startProductDeleteConversation(farmerId, prodName);
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
                "I can help you add or delete crops, list products, record expenses, " +
                        "record harvests, and check your saved farm records.\n\n" +
                        "Try:\n" +
                        "• \"Show my crops\"\n" +
                        "• \"I am planting 5 acres of wheat\"\n" +
                        "• \"Delete crop tomato\"\n" +
                        "• \"Existing stock\"\n" +
                        "• \"I spent ₹500 on fertilizer\"");
    }

    // ---------------------------------------------------------
    // CROP DELETION FLOW
    // ---------------------------------------------------------

    private AssistantResponse startCropDeleteConversation(Long farmerId, String target) {
        List<Crop> crops = cropRepository.findByFarmerId(farmerId);
        if (crops.isEmpty()) {
            return response("CROP_DELETE", "🌱 You don't have any crop records to delete.");
        }

        Crop matched = null;
        if (target != null && !target.isBlank() && !"null".equalsIgnoreCase(target)) {
            // Check by ID
            try {
                String cleanNum = target.replaceAll("[^0-9]", "");
                if (!cleanNum.isBlank()) {
                    Long numId = Long.parseLong(cleanNum);
                    for (Crop c : crops) {
                        if (c.getId().equals(numId)) {
                            matched = c;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Check by name
            if (matched == null) {
                String tLower = target.toLowerCase(Locale.ROOT).trim();
                for (Crop c : crops) {
                    if (c.getCropName() != null) {
                        String cName = c.getCropName().toLowerCase(Locale.ROOT);
                        if (cName.equals(tLower) || cName.contains(tLower) || tLower.contains(cName)) {
                            matched = c;
                            break;
                        }
                    }
                }
            }
        }

        Conversation c = new Conversation(farmerId, "CROP_DELETE");
        String id = UUID.randomUUID().toString();

        if (matched != null) {
            c.data.put("cropId", matched.getId());
            c.data.put("cropName", matched.getCropName());
            c.step = "CONFIRM_CROP_DELETE";
            conversations.put(id, c);

            AssistantResponse res = question(
                    c, id,
                    "⚠️ Are you sure you want to delete crop **" + matched.getCropName() +
                            "** (ID: #" + matched.getId() + ", Area: " + matched.getArea() + " " + (matched.getAreaUnit() != null ? matched.getAreaUnit() : "Acres") + ")?\n\n" +
                            "Reply **YES** to confirm permanent deletion or **NO** to cancel.");
            res.setRequiresConfirmation(true);
            res.setNextStep("CONFIRM_CROP_DELETE");
            return res;
        }

        // If target not specified or multiple crops, list crops and prompt for choice
        c.step = "CROP_DELETE_TARGET";
        conversations.put(id, c);

        StringBuilder sb = new StringBuilder("Which crop would you like to delete? Enter the crop name or ID from your records:\n\n");
        int idx = 1;
        for (Crop crop : crops) {
            sb.append(idx++).append(". **").append(crop.getCropName()).append("** (ID: #").append(crop.getId())
                    .append(", Area: ").append(crop.getArea()).append(" ").append(crop.getAreaUnit() != null ? crop.getAreaUnit() : "Acres")
                    .append(", Status: ").append(crop.getCropStatus() != null ? crop.getCropStatus() : "Seeded / Planted").append(")\n");
        }
        sb.append("\nOr reply **NO** to cancel.");
        return question(c, id, sb.toString());
    }

    // ---------------------------------------------------------
    // PRODUCT DELETION FLOW
    // ---------------------------------------------------------

    private AssistantResponse startProductDeleteConversation(Long farmerId, String target) {
        List<Product> products = productRepository.findByFarmerId(farmerId);
        if (products.isEmpty()) {
            return response("PRODUCT_DELETE", "🧺 You don't have any product listings to delete.");
        }

        Product matched = null;
        if (target != null && !target.isBlank() && !"null".equalsIgnoreCase(target)) {
            try {
                String cleanNum = target.replaceAll("[^0-9]", "");
                if (!cleanNum.isBlank()) {
                    Long numId = Long.parseLong(cleanNum);
                    for (Product p : products) {
                        if (p.getId().equals(numId)) {
                            matched = p;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (matched == null) {
                String tLower = target.toLowerCase(Locale.ROOT).trim();
                for (Product p : products) {
                    if (p.getProductName() != null) {
                        String pName = p.getProductName().toLowerCase(Locale.ROOT);
                        if (pName.equals(tLower) || pName.contains(tLower) || tLower.contains(pName)) {
                            matched = p;
                            break;
                        }
                    }
                }
            }
        }

        Conversation c = new Conversation(farmerId, "PRODUCT_DELETE");
        String id = UUID.randomUUID().toString();

        if (matched != null) {
            c.data.put("productId", matched.getId());
            c.data.put("productName", matched.getProductName());
            c.step = "CONFIRM_PRODUCT_DELETE";
            conversations.put(id, c);

            AssistantResponse res = question(
                    c, id,
                    "⚠️ Are you sure you want to remove product **" + matched.getProductName() +
                            "** (ID: #" + matched.getId() + ", Quantity: " + matched.getQuantity() + ", Price: ₹" + matched.getPrice() + ")?\n\n" +
                            "Reply **YES** to confirm removal or **NO** to cancel.");
            res.setRequiresConfirmation(true);
            res.setNextStep("CONFIRM_PRODUCT_DELETE");
            return res;
        }

        c.step = "PRODUCT_DELETE_TARGET";
        conversations.put(id, c);

        StringBuilder sb = new StringBuilder("Which product would you like to delete? Enter the product name or ID:\n\n");
        for (Product p : products) {
            sb.append("• **").append(p.getProductName()).append("** (ID: #").append(p.getId())
                    .append(", Qty: ").append(p.getQuantity()).append(", Price: ₹").append(p.getPrice()).append(")\n");
        }
        sb.append("\nOr reply **NO** to cancel.");
        return question(c, id, sb.toString());
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

        String productName = extractProductNameFromDb(farmerId, text);

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
                return "What category does the product belong to? (e.g. Vegetables, Fruits, Grains, Pulses)";
            case "PRODUCT_QUANTITY":
                return "What quantity is available (in kg)? For example, 100.";
            case "PRODUCT_PRICE":
                return "What is the selling price per kg? Enter the number only (e.g. 25).";
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
                        "• **Product**: " + c.data.get("productName") + "\n" +
                        "• **Category**: " + c.data.get("category") + "\n" +
                        "• **Quantity**: " + c.data.get("quantity") + " kg\n" +
                        "• **Price**: ₹" + c.data.get("price") + " / kg\n" +
                        "• **Description**: " + c.data.get("description") + "\n\n" +
                        "Reply **YES** to save or **NO** to cancel.");

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
                    "🌱 You have no crop records yet. Add a crop before recording a harvest.");
        }

        Conversation c = new Conversation(farmerId, "HARVEST");

        String lower = text.toLowerCase(Locale.ROOT);
        Crop selectedCrop = null;

        for (Crop crop : crops) {
            if (crop.getCropName() != null && !crop.getCropName().isBlank()) {
                if (lower.contains(crop.getCropName().toLowerCase(Locale.ROOT))) {
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
                                " is already marked as harvested in your crop records.");
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
                        "Which crop did you harvest? Choose a crop from your records:\n\n");

                for (Crop crop : crops) {
                    names.append("• **")
                            .append(crop.getCropName())
                            .append("** (ID: #")
                            .append(crop.getId())
                            .append(", Status: ")
                            .append(crop.getCropStatus() != null ? crop.getCropStatus() : "Seeded / Planted")
                            .append(")\n");
                }

                return names.toString();

            case "HARVEST_QUANTITY":
                return "How much did you harvest? Enter a quantity (e.g. 50).";

            case "HARVEST_UNIT":
                return "What is the quantity unit? (e.g. kg, quintal, tonnes).";

            case "HARVEST_PRICE":
                return "What is the selling price per unit? Enter 0 if not sold yet.";

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

        if (c.intent.equals("CROP_DELETE")) {
            if ("CROP_DELETE_TARGET".equals(c.step)) {
                List<Crop> crops = cropRepository.findByFarmerId(c.farmerId);
                Crop matched = null;

                try {
                    String cleanNum = answer.replaceAll("[^0-9]", "");
                    if (!cleanNum.isBlank()) {
                        Long numId = Long.parseLong(cleanNum);
                        for (Crop cr : crops) {
                            if (cr.getId().equals(numId)) {
                                matched = cr;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                if (matched == null) {
                    String aLower = answer.toLowerCase(Locale.ROOT).trim();
                    for (Crop cr : crops) {
                        if (cr.getCropName() != null) {
                            String cName = cr.getCropName().toLowerCase(Locale.ROOT);
                            if (cName.equals(aLower) || cName.contains(aLower) || aLower.contains(cName)) {
                                matched = cr;
                                break;
                            }
                        }
                    }
                }

                if (matched == null) {
                    return question(c, id, "I couldn't find a crop matching \"" + answer + "\". Please enter a valid crop name or ID from your records, or reply NO to cancel.");
                }

                c.data.put("cropId", matched.getId());
                c.data.put("cropName", matched.getCropName());
                c.step = "CONFIRM_CROP_DELETE";

                AssistantResponse res = question(
                        c, id,
                        "⚠️ Are you sure you want to delete crop **" + matched.getCropName() +
                                "** (ID: #" + matched.getId() + ", Area: " + matched.getArea() + " " + (matched.getAreaUnit() != null ? matched.getAreaUnit() : "Acres") + ")?\n\n" +
                                "Reply **YES** to confirm permanent deletion or **NO** to cancel.");
                res.setRequiresConfirmation(true);
                res.setNextStep("CONFIRM_CROP_DELETE");
                return res;
            }
        }

        if (c.intent.equals("PRODUCT_DELETE")) {
            if ("PRODUCT_DELETE_TARGET".equals(c.step)) {
                List<Product> products = productRepository.findByFarmerId(c.farmerId);
                Product matched = null;

                try {
                    String cleanNum = answer.replaceAll("[^0-9]", "");
                    if (!cleanNum.isBlank()) {
                        Long numId = Long.parseLong(cleanNum);
                        for (Product p : products) {
                            if (p.getId().equals(numId)) {
                                matched = p;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                if (matched == null) {
                    String aLower = answer.toLowerCase(Locale.ROOT).trim();
                    for (Product p : products) {
                        if (p.getProductName() != null) {
                            String pName = p.getProductName().toLowerCase(Locale.ROOT);
                            if (pName.equals(aLower) || pName.contains(aLower) || aLower.contains(pName)) {
                                matched = p;
                                break;
                            }
                        }
                    }
                }

                if (matched == null) {
                    return question(c, id, "I couldn't find a product matching \"" + answer + "\". Please enter a valid product name or ID, or reply NO to cancel.");
                }

                c.data.put("productId", matched.getId());
                c.data.put("productName", matched.getProductName());
                c.step = "CONFIRM_PRODUCT_DELETE";

                AssistantResponse res = question(
                        c, id,
                        "⚠️ Are you sure you want to remove product **" + matched.getProductName() +
                                "** (ID: #" + matched.getId() + ")?\n\n" +
                                "Reply **YES** to confirm removal or **NO** to cancel.");
                res.setRequiresConfirmation(true);
                res.setNextStep("CONFIRM_PRODUCT_DELETE");
                return res;
            }
        }

        if (c.intent.equals("PRODUCT_CREATE")) {
            switch (c.step) {
                case "PRODUCT_NAME":
                    c.data.put("productName", answer.trim());
                    break;
                case "PRODUCT_CATEGORY":
                    c.data.put("category", answer.trim());
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
                    c.data.put("description", answer.trim());
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
                                        "Enter the crop name or ID from your records.");
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
                case "AREA":
                    Matcher areaMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(answer);
                    if (!areaMatcher.find() || Double.parseDouble(areaMatcher.group(1)) <= 0) {
                        return question(c, id, "Please enter a positive area number, such as 5 acres or 2.5.");
                    }
                    c.data.put("area", Double.parseDouble(areaMatcher.group(1)));
                    String areaText = answer.toLowerCase(Locale.ROOT);
                    if (areaText.contains("hectare") || areaText.contains("ha")) {
                        c.data.put("areaUnit", "Hectares");
                    } else if (areaText.contains("cent")) {
                        c.data.put("areaUnit", "Cents");
                    } else {
                        c.data.put("areaUnit", "Acres");
                    }
                    break;
                default:
                    break;
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

        return response("UNKNOWN", "I couldn't continue that action. How can I help with your crops or farm?");
    }

    // ---------------------------------------------------------
    // SAVE CONFIRMED ACTIONS
    // ---------------------------------------------------------

    @Transactional
    private AssistantResponse saveAction(Conversation c, String id) {

        if (c.intent.equals("CROP_DELETE")) {
            Long cropId = ((Number) c.data.get("cropId")).longValue();
            String cropName = String.valueOf(c.data.get("cropName"));

            Crop crop = cropRepository.findById(cropId).orElse(null);
            if (crop != null && crop.getFarmerId().equals(c.farmerId)) {
                cropRepository.delete(crop);
            }
            conversations.remove(id);

            return completed(
                    "CROP_DELETED",
                    "🗑️ Crop **" + cropName + "** (ID: #" + cropId + ") has been deleted successfully from your farm records.",
                    id);
        }

        if (c.intent.equals("PRODUCT_DELETE")) {
            Long productId = ((Number) c.data.get("productId")).longValue();
            String productName = String.valueOf(c.data.get("productName"));

            Product product = productRepository.findById(productId).orElse(null);
            if (product != null && product.getFarmerId().equals(c.farmerId)) {
                productRepository.delete(product);
            }
            conversations.remove(id);

            return completed(
                    "PRODUCT_DELETED",
                    "🗑️ Product **" + productName + "** (ID: #" + productId + ") has been removed from your listed products.",
                    id);
        }

        if (c.intent.equals("CROP_CREATE")) {
            Crop crop = new Crop();
            crop.setFarmerId(c.farmerId);
            crop.setCropName(String.valueOf(c.data.get("cropName")));
            crop.setPlantingDate(c.data.get("plantingDate") != null ? String.valueOf(c.data.get("plantingDate")) : LocalDate.now().toString());
            crop.setSoilType(c.data.get("soilType") != null ? String.valueOf(c.data.get("soilType")) : "Loamy");
            crop.setArea(c.data.get("area") != null ? ((Number) c.data.get("area")).doubleValue() : 1.0);
            crop.setAreaUnit(c.data.get("areaUnit") != null ? String.valueOf(c.data.get("areaUnit")) : "Acres");
            crop.setVariety(c.data.get("variety") != null ? String.valueOf(c.data.get("variety")) : "Standard");
            crop.setSeedSource(c.data.get("seedSource") != null ? String.valueOf(c.data.get("seedSource")) : "Certified Seeds");
            crop.setFertilizerUsed(c.data.get("fertilizerUsed") != null ? String.valueOf(c.data.get("fertilizerUsed")) : "Standard Organic");
            crop.setIrrigationMethod(c.data.get("irrigationMethod") != null ? String.valueOf(c.data.get("irrigationMethod")) : "Drip");
            crop.setExpectedHarvestDate(c.data.get("expectedHarvestDate") != null ? String.valueOf(c.data.get("expectedHarvestDate")) : LocalDate.now().plusDays(90).toString());
            // Store as "Seeded / Planted" so it directly corresponds to the active stage on the /mycrop page!
            crop.setCropStatus("Seeded / Planted");

            Crop saved = cropRepository.save(crop);
            conversations.remove(id);

            return completed(
                    "CROP_SAVED",
                    "🌱 Crop **" + saved.getCropName() + "** has been saved successfully!\n\n" +
                            "• Crop ID: #" + saved.getId() + "\n" +
                            "• Cultivated Area: " + saved.getArea() + " " + saved.getAreaUnit() + "\n" +
                            "• Planting Date: " + saved.getPlantingDate() + "\n" +
                            "• Expected Harvest: " + saved.getExpectedHarvestDate() + "\n" +
                            "• Status: " + saved.getCropStatus() + "\n\n" +
                            "This crop is now visible on your **My Crops** page.",
                    id);
        }

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
                    "🧺 Product listed successfully:\n\n" +
                            "• Product: " + saved.getProductName() + "\n" +
                            "• Product ID: #" + saved.getId() + "\n" +
                            "• Available Quantity: " + saved.getQuantity() + " kg\n" +
                            "• Price: ₹" + saved.getPrice() + " / kg",
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
                    "💰 Expense saved successfully!\n\n" +
                            "• Amount: ₹" + saved.getAmount() + "\n" +
                            "• Category: " + saved.getCategory() + "\n" +
                            "• Date: " + saved.getExpenseDate(),
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

            crop.setCropStatus("Harvested");
            cropRepository.save(crop);

            conversations.remove(id);

            return completed(
                    "HARVEST_SAVED",
                    "🌾 Harvest saved successfully for " + saved.getCropName() + "!\n\n" +
                            "• Harvest ID: #" + saved.getId() + "\n" +
                            "• Quantity: " + saved.getQuantity() + " " + saved.getQuantityUnit() + "\n" +
                            "• Selling Price: ₹" + saved.getSellingPrice() + "\n" +
                            "• Total Revenue: ₹" + saved.getRevenue() + "\n\n" +
                            "The crop status has been updated to **Harvested**.",
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
        String requested = extractProductNameFromDb(farmerId, text);

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
                            ? "🧺 You don't have any product listings recorded yet."
                            : "I couldn't find a listing for \"" + requested + "\" in your products.");
        }

        StringBuilder answer = new StringBuilder("🧺 **Your Saved Product Listings (Total: " + matches.size() + "):**\n\n");

        for (Product product : matches) {
            answer.append("• **")
                    .append(product.getProductName())
                    .append("** (ID: #").append(product.getId()).append(")")
                    .append("\n  - Quantity: ").append(product.getQuantity()).append(" kg")
                    .append("\n  - Price: ₹").append(product.getPrice()).append(" / kg")
                    .append("\n  - Category: ").append(product.getCategory() != null ? product.getCategory() : "Other")
                    .append("\n\n");
        }

        answer.append("💡 *You can say \"add product\" to list a new item, or \"delete product <name>\" to remove an item.*");

        return response("STOCK", answer.toString());
    }

    private AssistantResponse answerCropQuestion(
            Long farmerId, String text) {

        List<Crop> crops = cropRepository.findByFarmerId(farmerId);

        if (crops.isEmpty()) {
            return response(
                    "CROPS",
                    "🌱 You don't have any crop records saved yet.\n\n" +
                            "Try saying: *\"I am planting 5 acres of wheat\"* or *\"Add crop tomatoes 3 acres\"* to record your first crop!");
        }

        String lower = text.toLowerCase(Locale.ROOT);

        // Check if user is asking about a specific crop in their records
        Crop specificCrop = null;
        for (Crop c : crops) {
            if (c.getCropName() != null && !c.getCropName().isBlank()) {
                String cName = c.getCropName().toLowerCase(Locale.ROOT);
                if (lower.contains(cName)) {
                    specificCrop = c;
                    break;
                }
            }
        }

        if (specificCrop != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("🌾 **Details for your crop: ").append(specificCrop.getCropName()).append("** (ID: #").append(specificCrop.getId()).append(")\n\n");
            sb.append("• **Current Stage / Status**: ").append(specificCrop.getCropStatus() != null ? specificCrop.getCropStatus() : "Seeded / Planted").append("\n");
            sb.append("• **Cultivated Area**: ").append(specificCrop.getArea()).append(" ").append(specificCrop.getAreaUnit() != null ? specificCrop.getAreaUnit() : "Acres").append("\n");
            sb.append("• **Variety**: ").append(specificCrop.getVariety() != null ? specificCrop.getVariety() : "Standard").append("\n");
            sb.append("• **Planting Date**: ").append(specificCrop.getPlantingDate() != null ? specificCrop.getPlantingDate() : "N/A").append("\n");
            sb.append("• **Expected Harvest Date**: ").append(specificCrop.getExpectedHarvestDate() != null ? specificCrop.getExpectedHarvestDate() : "N/A").append("\n");
            sb.append("• **Soil Type**: ").append(specificCrop.getSoilType() != null ? specificCrop.getSoilType() : "Loamy").append("\n");
            sb.append("• **Irrigation**: ").append(specificCrop.getIrrigationMethod() != null ? specificCrop.getIrrigationMethod() : "Drip").append("\n");
            sb.append("• **Fertilizer**: ").append(specificCrop.getFertilizerUsed() != null ? specificCrop.getFertilizerUsed() : "Standard").append("\n\n");
            sb.append("💡 *You can say \"delete crop ").append(specificCrop.getCropName()).append("\" or \"harvest ").append(specificCrop.getCropName()).append("\" to manage this crop.*");
            return response("CROPS", sb.toString());
        }

        // Otherwise list all crops clearly
        StringBuilder answer = new StringBuilder("🌱 **Your Saved Crop Records (Total: ")
                .append(crops.size()).append("):**\n\n");

        int index = 1;
        for (Crop crop : crops) {
            answer.append(index++).append(". **").append(crop.getCropName()).append("** (ID: #").append(crop.getId()).append(")\n")
                    .append("   • **Status**: ").append(crop.getCropStatus() != null ? crop.getCropStatus() : "Seeded / Planted").append("\n")
                    .append("   • **Area**: ").append(crop.getArea()).append(" ").append(crop.getAreaUnit() != null ? crop.getAreaUnit() : "Acres").append("\n")
                    .append("   • **Planted**: ").append(crop.getPlantingDate() != null ? crop.getPlantingDate() : "N/A")
                    .append(" | **Harvest**: ").append(crop.getExpectedHarvestDate() != null ? crop.getExpectedHarvestDate() : "N/A").append("\n")
                    .append("   • **Variety**: ").append(crop.getVariety() != null ? crop.getVariety() : "Standard").append("\n\n");
        }

        answer.append("💡 *You can ask me to add a crop (e.g. \"I am planting 5 acres of wheat\"), delete a crop (e.g. \"delete crop <name/ID>\"), or record a harvest.*");

        return response("CROPS", answer.toString());
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
                "💰 Your recorded expenses for " + categoryText + period +
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
                        "• **Crop**: " + c.data.get("cropName") + "\n" +
                        "• **Quantity**: " + quantity + " " + c.data.get("quantityUnit") + "\n" +
                        "• **Selling Price**: ₹" + price + " / unit\n" +
                        "• **Estimated Revenue**: ₹" + (quantity * price) +
                        "\n\nReply **YES** to save or **NO** to cancel.");

        result.setRequiresConfirmation(true);
        result.setNextStep("CONFIRM_HARVEST");
        return result;
    }

    private AssistantResponse cropSummary(Conversation c, String id) {
        AssistantResponse result = question(
                c, id,
                "🌱 **Please confirm your crop details:**\n\n" +
                        "• **Crop**: " + c.data.get("cropName") + "\n" +
                        "• **Area**: " + c.data.get("area") + " " + (c.data.get("areaUnit") != null ? c.data.get("areaUnit") : "Acres") + "\n" +
                        "• **Planting Date**: " + c.data.get("plantingDate") + "\n" +
                        "• **Expected Harvest Date**: " + c.data.get("expectedHarvestDate") + "\n" +
                        "• **Status**: " + c.data.get("cropStatus") + "\n" +
                        "• **Soil & Irrigation**: " + c.data.get("soilType") + " / " + c.data.get("irrigationMethod") + "\n\n" +
                        "Reply **YES** to save to your records or **NO** to cancel.");

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

        try {
            Long numId = Long.parseLong(wanted.replaceAll("[^0-9]", ""));
            for (Crop crop : cropRepository.findByFarmerId(farmerId)) {
                if (crop.getId().equals(numId)) return crop;
            }
        } catch (Exception ignored) {}

        for (Crop crop : cropRepository.findByFarmerId(farmerId)) {
            if (crop.getCropName() != null) {
                String cName = crop.getCropName().trim().toLowerCase(Locale.ROOT);
                if (cName.equals(wanted) || cName.contains(wanted) || wanted.contains(cName)) {
                    return crop;
                }
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

    private String extractProductNameFromDb(Long farmerId, String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        // Check against existing products in DB first
        List<Product> products = productRepository.findByFarmerId(farmerId);
        for (Product p : products) {
            if (p.getProductName() != null && !p.getProductName().isBlank()) {
                if (lower.contains(p.getProductName().toLowerCase(Locale.ROOT))) {
                    return p.getProductName();
                }
            }
        }

        // Fallback to common product names
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
                || text.contains("expense total")
                || text.contains("my expenses")
                || text.contains("show expenses")
                || text.contains("list expenses"))
                && (text.contains("spent") || text.contains("expense")
                || text.contains("fertilizer") || text.contains("fertiliser")
                || text.contains("seed") || text.contains("pesticide")
                || text.contains("labour") || text.contains("labor"));
    }

    private boolean isStockQuestion(String text) {
        return text.contains("stock") || text.contains("inventory")
                || text.contains("available quantity")
                || text.contains("existing stock")
                || text.contains("my products")
                || text.contains("show products")
                || text.contains("list products")
                || text.contains("all products")
                || (text.contains("how many") && (text.contains("product") || text.contains("stock")));
    }

    private boolean isCropQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT).trim();

        if (parser.isCropDelete(lower) || parser.isCropCreate(lower)) {
            return false;
        }

        // Exact short phrases
        if (lower.equals("crops") || lower.equals("crop") || lower.equals("my crops")
                || lower.equals("my crop") || lower.equals("mycrops") || lower.equals("show crops")
                || lower.equals("list crops") || lower.equals("existing crops") || lower.equals("current crops")
                || lower.equals("crop status") || lower.equals("crops status") || lower.equals("all crops")
                || lower.equals("view crops") || lower.equals("check crops")) {
            return true;
        }

        boolean mentionsCrop = containsAny(lower,
                "crop", "crops", "planted", "planting", "growing",
                "cultivating", "sowing", "sowed");

        boolean asksForRecords = containsAny(lower,
                "my", "show", "list", "tell", "what", "which", "details",
                "status", "records", "record", "current", "currently",
                "existing", "have i", "how many", "view", "display",
                "check", "get", "see", "any", "find", "all", "summary");

        return (mentionsCrop && asksForRecords) || lower.contains("my crop") || lower.contains("my crops");
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
                || text.contains("product for sale");
    }

    private boolean isYes(String value) {
        return value != null && value.trim().matches(
                "(?i)yes|y|confirm|confirmed|save|save it|ok|sure|proceed");
    }

    private boolean isNo(String value) {
        return value != null && value.trim().matches(
                "(?i)no|n|cancel|stop|no thanks|dont|don't");
    }

    private boolean blank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isEmpty() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
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