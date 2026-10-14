
package com.agriculture.backend.repository;

import com.agriculture.backend.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository
        extends JpaRepository<OrderItem, Long> {

    // Existing method — preserved
    List<OrderItem> findByOrderId(Long orderId);

    // All items belonging to a farmer
    List<OrderItem> findByFarmerId(Long farmerId);

    // Items for a specific product belonging to a farmer
    List<OrderItem> findByFarmerIdAndProductId(
            Long farmerId,
            Long productId
    );
}
