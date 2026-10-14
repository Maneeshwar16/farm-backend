
package com.agriculture.backend.Service;

import com.agriculture.backend.dto.AssistantResponse;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FarmerTextParser {

    public AssistantResponse parse(String message) {

        if (message == null || message.isBlank()) {
            return new AssistantResponse(
                    "UNKNOWN",
                    "Please enter a message.",
                    false,
                    null,
                    null
            );
        }

        String text = message.toLowerCase(Locale.ROOT);

        if (text.contains("spent")
                || text.contains("expense")
                || text.contains("paid")
                || text.contains("cost")) {

            Pattern pattern = Pattern.compile(
                    "(?:₹|rs\\.?\\s*)?([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:rupees?|rs\\.?)?",
                    Pattern.CASE_INSENSITIVE
            );

            Matcher matcher = pattern.matcher(text);

            if (!matcher.find()) {
                return new AssistantResponse(
                        "EXPENSE",
                        "How much did you spend? Please include the amount.",
                        false,
                        null,
                        null
                );
            }

            double amount = Double.parseDouble(matcher.group(1));

            if (!Double.isFinite(amount) || amount <= 0) {
                return new AssistantResponse(
                        "EXPENSE",
                        "Please enter a valid expense amount greater than zero.",
                        false,
                        null,
                        null
                );
            }

            String category = "Other";

            if (text.contains("fertilizer") || text.contains("fertiliser")) {
                category = "Fertilizer";
            } else if (text.contains("seed")) {
                category = "Seeds";
            } else if (text.contains("labour") || text.contains("labor")) {
                category = "Labour";
            } else if (text.contains("pesticide")) {
                category = "Pesticides";
            } else if (text.contains("equipment")) {
                category = "Equipment";
            } else if (text.contains("water") || text.contains("irrigation")) {
                category = "Irrigation";
            }

            return new AssistantResponse(
                    "EXPENSE",
                    "Please confirm: record an expense of ₹"
                            + amount + " for " + category + "?",
                    true,
                    amount,
                    category
            );
        }

        return new AssistantResponse(
                "UNKNOWN",
                "I couldn't identify that request yet. Try: "
                        + "\"I spent 1500 rupees on fertilizer.\"",
                false,
                null,
                null
        );
    }
}
