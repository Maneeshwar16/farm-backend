package com.agriculture.backend.Service;
import com.agriculture.backend.dto.AssistantResponse;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FarmerTextParser {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:₹|rs\\.?\\s*)?([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:rupees?|rs\\.?)?",
            Pattern.CASE_INSENSITIVE
    );

    public AssistantResponse parse(String message) {

        if (message == null || message.isBlank()) {
            return response(
                    "UNKNOWN",
                    "Please enter a message."
            );
        }

        String text = message.toLowerCase(Locale.ROOT);

        // Expense requests
        if (containsAny(text,
                "spent", "expense", "paid", "cost",
                "purchased fertilizer", "bought seeds")) {

            Matcher matcher = AMOUNT_PATTERN.matcher(text);

            if (!matcher.find()) {
                return response(
                        "EXPENSE",
                        "How much did you spend? Please include the amount."
                );
            }

            double amount;

            try {
                amount = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException exception) {
                return response(
                        "EXPENSE",
                        "Please enter a valid expense amount."
                );
            }

            if (!Double.isFinite(amount) || amount <= 0) {
                return response(
                        "EXPENSE",
                        "Please enter an amount greater than zero."
                );
            }

            String category = detectExpenseCategory(text);

            AssistantResponse result = new AssistantResponse(
                    "EXPENSE",
                    "Please confirm: record an expense of ₹"
                            + amount + " for " + category + "?",
                    true,
                    amount,
                    category
            );

            result.setNextStep("CONFIRM_EXPENSE");
            return result;
        }

        // Crop creation requests
        if (containsAny(text,
                "plant", "planted", "grow", "growing",
                "add crop", "new crop", "register crop")) {

            AssistantResponse result = response(
                    "CROP_CREATE",
                    "Let's add your crop. What crop do you want to plant?"
            );

            result.setNextStep("CROP_NAME");
            return result;
        }

        // Harvest requests
        if (containsAny(text,
                "harvest", "harvested", "reaped")) {

            AssistantResponse result = response(
                    "HARVEST",
                    "Which crop did you harvest?"
            );

            result.setNextStep("HARVEST_CROP");
            return result;
        }

        // Product creation requests
        if (containsAny(text,
                "sell", "list product", "upload product",
                "add product", "put up for sale")) {

            AssistantResponse result = response(
                    "PRODUCT_CREATE",
                    "Which agricultural product would you like to sell?"
            );

            result.setNextStep("PRODUCT_NAME");
            return result;
        }

        // Product deletion requests
        if (containsAny(text,
                "delete product", "remove listing",
                "delete listing", "remove product")) {

            AssistantResponse result = response(
                    "PRODUCT_DELETE",
                    "Which product listing would you like to remove?"
            );

            result.setNextStep("PRODUCT_DELETE_NAME");
            return result;
        }

        return response(
                "UNKNOWN",
                "I couldn't identify that request. Try saying: "
                        + "\"I spent 1500 rupees on fertilizer\", "
                        + "\"I want to plant tomatoes\", "
                        + "\"I harvested tomatoes\", or "
                        + "\"I want to sell tomatoes\"."
        );
    }

    private String detectExpenseCategory(String text) {

        if (containsAny(text, "fertilizer", "fertiliser")) {
            return "Fertilizer";
        }

        if (containsAny(text, "seed", "seeds")) {
            return "Seeds";
        }

        if (containsAny(text, "labour", "labor", "worker")) {
            return "Labour";
        }

        if (containsAny(text, "pesticide", "pesticides")) {
            return "Pesticides";
        }

        if (containsAny(text, "equipment", "machine", "machinery")) {
            return "Equipment";
        }

        if (containsAny(text, "water", "irrigation")) {
            return "Irrigation";
        }

        if (containsAny(text, "transport", "transportation")) {
            return "Transport";
        }

        return "Other";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }

        return false;
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
}
