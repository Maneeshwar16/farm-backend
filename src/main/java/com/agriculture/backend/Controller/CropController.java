
package com.agriculture.backend.Controller;

import com.agriculture.backend.entity.Crop;
import com.agriculture.backend.repository.CropRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/crops")

public class CropController {

    private final CropRepository cropRepository;

    public CropController(CropRepository cropRepository) {
        this.cropRepository = cropRepository;
    }

    // Compatibility endpoint for existing pages that request /crops
    @GetMapping
    public List<Crop> getAllCrops(
            @RequestParam(required = false) Long farmerId) {

        if (farmerId != null) {
            return cropRepository.findByFarmerId(farmerId);
        }

        return cropRepository.findAll();
    }

    // Save a new crop
    @PostMapping
    public Crop addCrop(@RequestBody Crop crop) {

        if (crop.getFarmerId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Farmer ID is required"
            );
        }

        crop.setId(null);
        return cropRepository.save(crop);
    }

    // Retrieve crops belonging to one farmer
    @GetMapping("/farmer/{farmerId}")
    public List<Crop> getCropsByFarmer(
            @PathVariable Long farmerId) {

        return cropRepository.findByFarmerId(farmerId);
    }

    // Retrieve one crop by ID
    @GetMapping("/{id}")
    public Crop getCropById(@PathVariable Long id) {

        return cropRepository.findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Crop not found with ID: " + id
                        ));
    }

    // Update an existing crop, including its harvest status
    @PutMapping("/{id}")
    public Crop updateCrop(
            @PathVariable Long id,
            @RequestBody Crop updatedCrop) {

        Crop existingCrop = cropRepository.findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Crop not found with ID: " + id
                        ));

        if (updatedCrop.getFarmerId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Farmer ID is required"
            );
        }

        // Prevent the update from creating a new crop ID.
        updatedCrop.setId(existingCrop.getId());

        return cropRepository.save(updatedCrop);
    }
}
