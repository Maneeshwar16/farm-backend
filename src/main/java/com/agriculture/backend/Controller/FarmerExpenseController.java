
package com.agriculture.backend.Controller;

import com.agriculture.backend.entity.FarmerExpense;
import com.agriculture.backend.repository.FarmerExpenseRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/expenses")
public class FarmerExpenseController {

    private final FarmerExpenseRepository expenseRepository;

    public FarmerExpenseController(
            FarmerExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    // Get all expenses for one farmer
    @GetMapping("/farmer/{farmerId}")
    public List<FarmerExpense> getExpenses(
            @PathVariable Long farmerId) {
        return expenseRepository.findByFarmerId(farmerId);
    }

    // Add a new expense
    @PostMapping
    public FarmerExpense addExpense(
            @RequestBody FarmerExpense expense) {

        expense.setId(null);
        return expenseRepository.save(expense);
    }

    // Delete an expense
    @DeleteMapping("/{id}")
    public void deleteExpense(@PathVariable Long id) {
        expenseRepository.deleteById(id);
    }
}
