
package com.agriculture.backend.Service;

import com.agriculture.backend.dto.AssistantResponse;
import com.agriculture.backend.entity.FarmerExpense;
import com.agriculture.backend.repository.FarmerExpenseRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class FarmerAssistantService {

    private final FarmerTextParser parser;
    private final FarmerExpenseRepository expenseRepository;

    public FarmerAssistantService(
            FarmerTextParser parser,
            FarmerExpenseRepository expenseRepository) {
        this.parser = parser;
        this.expenseRepository = expenseRepository;
    }

    public AssistantResponse processMessage(
            String message,
            Long farmerId,
            boolean confirmed) {

        if (farmerId == null || farmerId <= 0) {
            return new AssistantResponse(
                    "UNKNOWN",
                    "Please log in as a farmer before recording an expense.",
                    false,
                    null,
                    null
            );
        }

        AssistantResponse parsed = parser.parse(message);

        // Only process expenses understood by the parser.
        if (!"EXPENSE".equals(parsed.getIntent())) {
            return parsed;
        }

        // Ask for confirmation before saving.
        if (!confirmed) {
            return parsed;
        }

        if (parsed.getAmount() == null ||
                parsed.getAmount() <= 0 ||
                parsed.getCategory() == null) {
            return new AssistantResponse(
                    "EXPENSE",
                    "I couldn't validate the expense. Please enter it again.",
                    false,
                    null,
                    null
            );
        }

        FarmerExpense expense = new FarmerExpense();
        expense.setFarmerId(farmerId);
        expense.setCategory(parsed.getCategory());
        expense.setDescription(message.trim());
        expense.setAmount(parsed.getAmount());
        expense.setExpenseDate(LocalDate.now());

        FarmerExpense saved = expenseRepository.save(expense);

        return new AssistantResponse(
                "EXPENSE_SAVED",
                "Expense saved successfully! Amount: ₹"
                        + saved.getAmount()
                        + ", Category: "
                        + saved.getCategory()
                        + ", Expense ID: "
                        + saved.getId(),
                false,
                saved.getAmount(),
                saved.getCategory()
        );
    }
}
