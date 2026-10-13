
package com.agriculture.backend.repository;

import com.agriculture.backend.entity.FarmerExpense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FarmerExpenseRepository
        extends JpaRepository<FarmerExpense, Long> {

    List<FarmerExpense> findByFarmerId(Long farmerId);
}
