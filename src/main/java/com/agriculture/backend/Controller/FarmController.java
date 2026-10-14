
package com.agriculture.backend.Controller;

import com.agriculture.backend.entity.Farm;
import com.agriculture.backend.repository.FarmRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/farms")

public class FarmController {

    private final FarmRepository farmRepository;

    public FarmController(FarmRepository farmRepository) {
        this.farmRepository = farmRepository;
    }

    // Add a farm
    @PostMapping
    public Farm addFarm(@RequestBody Farm farm) {

        if (farm.getFarmerId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Farmer ID is required"
            );
        }

        farm.setId(null);
        return farmRepository.save(farm);
    }

    // Get farms belonging to one farmer only
    @GetMapping("/farmer/{farmerId}")
    public List<Farm> getFarmsByFarmer(
            @PathVariable Long farmerId) {

        return farmRepository.findByFarmerId(farmerId);
    }

    // Get a farm by ID
    @GetMapping("/{id}")
    public Farm getFarmById(@PathVariable Long id) {
        return farmRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Farm not found with ID: " + id));
    }

    // Delete a farm by ID
    @DeleteMapping("/{id}")
    public void deleteFarm(@PathVariable Long id) {
        if (!farmRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Farm not found with ID: " + id);
        }
        farmRepository.deleteById(id);
    }
}
