
package com.agriculture.backend.Controller;

import com.agriculture.backend.entity.CustomerOrder;
import com.agriculture.backend.entity.OrderItem;
import com.agriculture.backend.entity.Product;
import com.agriculture.backend.repository.OrderItemRepository;
import com.agriculture.backend.repository.OrderRepository;
import com.agriculture.backend.repository.ProductRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/orders")
@CrossOrigin(origins = "http://localhost:5173")
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    public OrderController(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            ProductRepository productRepository) {

        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> placeOrder(
            @RequestBody Map<String, Object> request) {

        try {
            Object customerIdValue = request.get("customerId");
            Object requestIdValue = request.get("checkoutRequestId");

            if (customerIdValue == null || requestIdValue == null) {
                return ResponseEntity.badRequest().body(
                        Map.of("message",
                                "Customer ID and checkout request ID are required."));
            }

            Long customerId = Long.valueOf(customerIdValue.toString());
            String checkoutRequestId = requestIdValue.toString().trim();

            if (checkoutRequestId.isBlank()) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Checkout request ID is required."));
            }

            Optional<CustomerOrder> existingOrder =
                    orderRepository.findByCheckoutRequestId(checkoutRequestId);

            if (existingOrder.isPresent()) {
                CustomerOrder order = existingOrder.get();

                if (!Objects.equals(order.getCustomerId(), customerId)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(
                            Map.of("message",
                                    "This checkout request belongs to another customer."));
                }

                Map<String, Object> response = new HashMap<>();
                response.put("message",
                        "Order already placed. No duplicate order was created.");
                response.put("orderId", order.getId());
                response.put("alreadyPlaced", true);
                response.put("subtotal", order.getSubtotal());
                response.put("discount", order.getDiscount());
                response.put("totalAmount", order.getTotalAmount());
                response.put("paymentMethod", order.getPaymentMethod());
                response.put("paymentStatus", order.getPaymentStatus());
                response.put("orderStatus", order.getOrderStatus());

                return ResponseEntity.ok(response);
            }

            Map<String, Object> address =
                    (Map<String, Object>) request.get("address");

            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) request.get("items");

            if (address == null || items == null || items.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("message",
                                "Address and order items are required."));
            }

            String phone = String.valueOf(address.get("phone"));
            String deliveryAddress = String.valueOf(address.get("address"));
            String village = String.valueOf(address.get("village"));
            String district = String.valueOf(address.get("district"));
            String state = String.valueOf(address.get("state"));
            String pincode = String.valueOf(address.get("pincode"));

            if (!phone.matches("^[6-9][0-9]{9}$")
                    || deliveryAddress.isBlank()
                    || village.isBlank()
                    || district.isBlank()
                    || state.isBlank()
                    || !pincode.matches("^[1-9][0-9]{5}$")) {

                return ResponseEntity.badRequest().body(
                        Map.of("message",
                                "Please provide a valid delivery address."));
            }

            String paymentMethod = String.valueOf(request.get("paymentMethod"));

            if (!List.of("COD", "QR", "BANK", "DEMO").contains(paymentMethod)) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "Invalid payment method."));
            }

            String couponCode = request.get("couponCode") == null
                    ? ""
                    : request.get("couponCode").toString()
                    .trim().toUpperCase();

            double subtotal = 0;
            List<OrderItem> orderItems = new ArrayList<>();
            Map<Long, Integer> requestedQuantities = new HashMap<>();

            for (Map<String, Object> item : items) {
                if (item.get("productId") == null
                        || item.get("quantity") == null) {
                    return ResponseEntity.badRequest().body(
                            Map.of("message",
                                    "Product ID and quantity are required."));
                }

                Long productId = Long.valueOf(
                        item.get("productId").toString());

                int quantity = Integer.parseInt(
                        item.get("quantity").toString());

                if (quantity <= 0) {
                    return ResponseEntity.badRequest().body(
                            Map.of("message",
                                    "Product quantity must be positive."));
                }

                Product product = productRepository.findById(productId)
                        .orElse(null);

                if (product == null) {
                    return ResponseEntity.badRequest().body(
                            Map.of("message",
                                    "A selected product does not exist."));
                }

                requestedQuantities.merge(
                        productId, quantity, Integer::sum);

                double price = product.getPrice();
                double itemTotal = price * quantity;
                subtotal += itemTotal;

                OrderItem orderItem = new OrderItem();
                orderItem.setProductId(product.getId());
                orderItem.setProductName(product.getProductName());
                orderItem.setFarmerId(product.getFarmerId());
                orderItem.setQuantity(quantity);
                orderItem.setPricePerKg(price);
                orderItem.setTotalPrice(itemTotal);

                orderItems.add(orderItem);
            }

            for (Map.Entry<Long, Integer> entry
                    : requestedQuantities.entrySet()) {

                Product product = productRepository
                        .findById(entry.getKey()).orElseThrow();

                if (entry.getValue() > product.getQuantity()) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(
                            Map.of("message",
                                    "Insufficient stock for "
                                            + product.getProductName()));
                }
            }

            double discount = 0;

            if (!couponCode.isEmpty()) {
                if (!couponCode.equals("FARM10")) {
                    return ResponseEntity.badRequest().body(
                            Map.of("message", "Invalid coupon code."));
                }

                discount = subtotal * 0.10;
            }

            double deliveryCharge = 0;
            double total = subtotal + deliveryCharge - discount;

            CustomerOrder order = new CustomerOrder();
            order.setCustomerId(customerId);
            order.setCheckoutRequestId(checkoutRequestId);
            order.setPhone(phone);
            order.setDeliveryAddress(deliveryAddress);
            order.setVillage(village);
            order.setDistrict(district);
            order.setState(state);
            order.setPincode(pincode);
            order.setSubtotal(subtotal);
            order.setDiscount(discount);
            order.setDeliveryCharge(deliveryCharge);
            order.setTotalAmount(total);
            order.setCouponCode(couponCode);
            order.setPaymentMethod(paymentMethod);
            order.setPaymentStatus("PENDING");
            order.setOrderStatus("PLACED");

            CustomerOrder savedOrder = orderRepository.saveAndFlush(order);

            for (OrderItem item : orderItems) {
                item.setOrderId(savedOrder.getId());
                orderItemRepository.save(item);
            }

            for (Map.Entry<Long, Integer> entry
                    : requestedQuantities.entrySet()) {

                Product product = productRepository
                        .findById(entry.getKey()).orElseThrow();

                product.setQuantity(
                        product.getQuantity() - entry.getValue());

                productRepository.save(product);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Order placed successfully.");
            response.put("orderId", savedOrder.getId());
            response.put("alreadyPlaced", false);
            response.put("subtotal", subtotal);
            response.put("discount", discount);
            response.put("totalAmount", total);
            response.put("paymentMethod", paymentMethod);
            response.put("paymentStatus", savedOrder.getPaymentStatus());
            response.put("orderStatus", savedOrder.getOrderStatus());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("message",
                            "This checkout was already submitted. Please check My Orders."));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(
                    Map.of("message",
                            "Unable to place order. Check the submitted details."));
        }
    }

    @GetMapping("/customer/{customerId}")
    public List<CustomerOrder> getCustomerOrders(
            @PathVariable Long customerId) {

        return orderRepository.findByCustomerId(customerId);
    }

    @GetMapping("/{orderId}/items")
    public ResponseEntity<?> getOrderItems(
            @PathVariable Long orderId) {

        if (!orderRepository.existsById(orderId)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                orderItemRepository.findByOrderId(orderId));
    }

    @GetMapping("/farmer/{farmerId}")
    public ResponseEntity<?> getFarmerOrders(
            @PathVariable Long farmerId) {

        List<Map<String, Object>> farmerOrders = new ArrayList<>();

        for (CustomerOrder order : orderRepository.findAll()) {

            List<OrderItem> allItems =
                    orderItemRepository.findByOrderId(order.getId());

            List<OrderItem> farmerItems = allItems.stream()
                    .filter(item ->
                            Objects.equals(item.getFarmerId(), farmerId))
                    .toList();

            if (farmerItems.isEmpty()) {
                continue;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("orderId", order.getId());
            result.put("customerId", order.getCustomerId());
            result.put("orderStatus", order.getOrderStatus());
            result.put("paymentStatus", order.getPaymentStatus());
            result.put("paymentMethod", order.getPaymentMethod());
            result.put("createdAt", order.getCreatedAt());
            result.put("phone", order.getPhone());
            result.put("deliveryAddress", order.getDeliveryAddress());
            result.put("village", order.getVillage());
            result.put("district", order.getDistrict());
            result.put("state", order.getState());
            result.put("pincode", order.getPincode());
            result.put("items", farmerItems);

            double farmerSubtotal = farmerItems.stream()
                    .mapToDouble(OrderItem::getTotalPrice)
                    .sum();

            result.put("farmerSubtotal", farmerSubtotal);
            farmerOrders.add(result);
        }

        farmerOrders.sort((first, second) ->
                Long.compare(
                        ((Number) second.get("orderId")).longValue(),
                        ((Number) first.get("orderId")).longValue()
                ));

        return ResponseEntity.ok(farmerOrders);
    }

    @GetMapping("/farmer/{farmerId}/summary")
    public ResponseEntity<?> getFarmerOrderSummary(
            @PathVariable Long farmerId) {

        int newOrders = 0;
        int pendingOrders = 0;
        int deliveredOrders = 0;
        int totalOrders = 0;

        for (CustomerOrder order : orderRepository.findAll()) {

            List<OrderItem> items =
                    orderItemRepository.findByOrderId(order.getId());

            boolean belongsToFarmer = items.stream()
                    .anyMatch(item ->
                            Objects.equals(item.getFarmerId(), farmerId));

            if (!belongsToFarmer) {
                continue;
            }

            totalOrders++;

            String status = order.getOrderStatus() == null
                    ? ""
                    : order.getOrderStatus().toUpperCase();

            if (status.equals("PLACED")) {
                newOrders++;
            }

            if (!status.equals("DELIVERED")
                    && !status.equals("CANCELLED")) {
                pendingOrders++;
            }

            if (status.equals("DELIVERED")) {
                deliveredOrders++;
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("newOrders", newOrders);
        summary.put("pendingOrders", pendingOrders);
        summary.put("deliveredOrders", deliveredOrders);
        summary.put("totalOrders", totalOrders);

        return ResponseEntity.ok(summary);
    }

    @PutMapping("/farmer/{farmerId}/{orderId}/status")
    @Transactional
    public ResponseEntity<?> updateFarmerOrderStatus(
            @PathVariable Long farmerId,
            @PathVariable Long orderId,
            @RequestBody Map<String, String> request) {

        CustomerOrder order = orderRepository.findById(orderId)
                .orElse(null);

        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        List<OrderItem> items =
                orderItemRepository.findByOrderId(orderId);

        boolean belongsToFarmer = items.stream()
                .anyMatch(item ->
                        Objects.equals(item.getFarmerId(), farmerId));

        if (!belongsToFarmer) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "message",
                            "This order does not contain your products."
                    ));
        }

        String requestedStatus = request.get("status");

        if (requestedStatus == null || requestedStatus.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "Order status is required."));
        }

        String newStatus = requestedStatus.trim().toUpperCase();

        if (!List.of(
                "PROCESSING",
                "SHIPPED",
                "DELIVERED",
                "CANCELLED"
        ).contains(newStatus)) {
            return ResponseEntity.badRequest().body(
                    Map.of("message",
                            "Invalid status. Choose PROCESSING, SHIPPED, DELIVERED or CANCELLED."));
        }

        String currentStatus = order.getOrderStatus() == null
                ? ""
                : order.getOrderStatus().trim().toUpperCase();

        if (currentStatus.equals("CANCELLED")) {
            if (newStatus.equals("CANCELLED")) {
                return ResponseEntity.ok(Map.of(
                        "message",
                        "Order is already cancelled. Stock was not restored again.",
                        "orderId", order.getId(),
                        "orderStatus", currentStatus
                ));
            }

            return ResponseEntity.badRequest().body(
                    Map.of("message",
                            "A cancelled order cannot be updated."));
        }

        if (currentStatus.equals("DELIVERED")
                && newStatus.equals("CANCELLED")) {
            return ResponseEntity.badRequest().body(
                    Map.of("message",
                            "A delivered order cannot be cancelled."));
        }

        if (newStatus.equals("CANCELLED")) {
            for (OrderItem item : items) {
                Product product = productRepository
                        .findById(item.getProductId())
                        .orElse(null);

                if (product != null) {
                    product.setQuantity(
                            product.getQuantity() + item.getQuantity());
                    productRepository.save(product);
                }
            }
        }

        order.setOrderStatus(newStatus);
        orderRepository.save(order);

        return ResponseEntity.ok(Map.of(
                "message", newStatus.equals("CANCELLED")
                        ? "Order cancelled and stock restored successfully."
                        : "Order status updated successfully.",
                "orderId", order.getId(),
                "orderStatus", order.getOrderStatus()
        ));
    }
}
