
package com.agriculture.backend.Controller;

import com.agriculture.backend.entity.Crop;
import com.agriculture.backend.entity.Harvest;
import com.agriculture.backend.repository.CropRepository;
import com.agriculture.backend.repository.HarvestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/harvests")
@CrossOrigin(origins = "http://localhost:5173")
public class HarvestController {

    private final HarvestRepository harvestRepository;
    private final CropRepository cropRepository;

    public HarvestController(
            HarvestRepository harvestRepository,
            CropRepository cropRepository) {
        this.harvestRepository = harvestRepository;
        this.cropRepository = cropRepository;
    }

    @PostMapping
    @Transactional
    public Harvest addHarvest(@RequestBody Harvest harvest) {

        if (harvest.getCropId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Crop ID is required");
        }

        Crop crop = cropRepository.findById(harvest.getCropId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Crop not found"));

        if ("Harvested".equalsIgnoreCase(crop.getCropStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This crop has already been harvested");
        }

        if (harvest.getQuantity() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Quantity must be greater than zero");
        }

        if (harvest.getSellingPrice() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Selling price cannot be negative");
        }

        harvest.setCropName(crop.getCropName());
        harvest.setVariety(crop.getVariety());
        harvest.setPlantingDate(crop.getPlantingDate());
        harvest.setFarmerId(crop.getFarmerId());
        harvest.setRevenue(
                harvest.getQuantity() * harvest.getSellingPrice()
        );

        Harvest savedHarvest = harvestRepository.save(harvest);

        crop.setCropStatus("Harvested");
        cropRepository.save(crop);

        return savedHarvest;
    }

    @GetMapping
    public List<Harvest> getAllHarvests() {
        return harvestRepository.findAll();
    }

    @GetMapping("/farmer/{farmerId}")
    public List<Harvest> getHarvestsByFarmer(
            @PathVariable Long farmerId) {
        return harvestRepository.findByFarmerId(farmerId);
    }

    @GetMapping("/{id}")
    public Harvest getHarvestById(@PathVariable Long id) {
        return harvestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Harvest not found"));
    }
}
