
package com.agriculture.backend.repository;

import com.agriculture.backend.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByCustomerId(Long customerId);

    Optional<CustomerOrder> findByCheckoutRequestId(String checkoutRequestId);
}
