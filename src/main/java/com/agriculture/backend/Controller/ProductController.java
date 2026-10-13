
package com.agriculture.backend.Controller;

import com.agriculture.backend.entity.Product;
import com.agriculture.backend.entity.User;
import com.agriculture.backend.repository.ProductRepository;
import com.agriculture.backend.repository.UserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/products")

public class ProductController {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public ProductController(
            ProductRepository productRepository,
            UserRepository userRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    // Add a product
    @PostMapping
    public Product addProduct(@RequestBody Product product) {
        return productRepository.save(product);
    }

    // Get all products, including the farmer's name
    @GetMapping
    public List<Map<String, Object>> getAllProducts() {
        List<Product> products = productRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Product product : products) {
            result.add(productResponse(product));
        }

        return result;
    }

    // Get a single product, including the farmer's name
    @GetMapping("/{id}")
    public Map<String, Object> getProductById(
            @PathVariable Long id) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Product not found with ID: " + id));

        return productResponse(product);
    }

    // Delete a product
    @DeleteMapping("/{id}")
    public void deleteProduct(@PathVariable Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Product not found");
        }

        productRepository.deleteById(id);
    }

    // Build a product response with public farmer information
    private Map<String, Object> productResponse(Product product) {
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("id", product.getId());
        response.put("productName", product.getProductName());
        response.put("category", product.getCategory());
        response.put("quantity", product.getQuantity());
        response.put("price", product.getPrice());
        response.put("description", product.getDescription());
        response.put("farmerId", product.getFarmerId());

        User farmer = product.getFarmerId() == null
                ? null
                : userRepository.findById(product.getFarmerId())
                .orElse(null);

        response.put(
                "farmerName",
                farmer != null && farmer.getName() != null
                        && !farmer.getName().isBlank()
                        ? farmer.getName()
                        : "Farmer"
        );

        response.put(
                "farmerVillage",
                farmer != null ? farmer.getVillage() : null
        );

        response.put(
                "farmerDistrict",
                farmer != null ? farmer.getDistrict() : null
        );

        response.put(
                "farmerState",
                farmer != null ? farmer.getState() : null
        );

        return response;
    }
}
