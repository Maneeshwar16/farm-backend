
package com.agriculture.backend.Controller;

import com.agriculture.backend.entity.User;
import com.agriculture.backend.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // Register a new user
    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody User user) {

        if (userRepository.existsByEmail(user.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        User savedUser = userRepository.save(user);
        return userResponse(savedUser);
    }

    // Login
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User user) {

        User existingUser =
                userRepository.findByEmail(user.getEmail());

        if (existingUser == null) {
            throw new RuntimeException("Email not registered");
        }

        if (!existingUser.getPassword().equals(user.getPassword())) {
            throw new RuntimeException("Incorrect password");
        }

        return userResponse(existingUser);
    }

    // Get farmer profile
    @GetMapping("/users/{id}/profile")
    public Map<String, Object> getProfile(@PathVariable Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("User not found"));

        return userResponse(user);
    }

    // Update farmer profile
    @PutMapping("/users/{id}/profile")
    public Map<String, Object> updateProfile(
            @PathVariable Long id,
            @RequestBody User profileData) {

        User user = userRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("User not found"));

        user.setName(profileData.getName());
        user.setPhone(profileData.getPhone());
        user.setAddress(profileData.getAddress());
        user.setVillage(profileData.getVillage());
        user.setDistrict(profileData.getDistrict());
        user.setState(profileData.getState());

        User updatedUser = userRepository.save(user);

        return userResponse(updatedUser);
    }

    // Return user details without password
    private Map<String, Object> userResponse(User user) {

        return Map.of(
                "id", user.getId(),
                "name", user.getName() == null ? "" : user.getName(),
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "role", user.getRole() == null ? "" : user.getRole(),
                "phone", user.getPhone() == null ? "" : user.getPhone(),
                "address", user.getAddress() == null ? "" : user.getAddress(),
                "village", user.getVillage() == null ? "" : user.getVillage(),
                "district", user.getDistrict() == null ? "" : user.getDistrict(),
                "state", user.getState() == null ? "" : user.getState()
        );
    }
}
