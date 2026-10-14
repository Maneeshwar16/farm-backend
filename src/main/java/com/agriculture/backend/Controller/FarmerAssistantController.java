
package com.agriculture.backend.Controller;

import com.agriculture.backend.dto.AssistantRequest;
import com.agriculture.backend.dto.AssistantResponse;
import com.agriculture.backend.Service.FarmerAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farmer-assistant")
public class FarmerAssistantController {

    private final FarmerAssistantService assistantService;

    public FarmerAssistantController(
            FarmerAssistantService assistantService
    ) {
        this.assistantService = assistantService;
    }

    @PostMapping("/message")
    public ResponseEntity<AssistantResponse> sendMessage(
            @RequestBody AssistantRequest request
    ) {
        if (request == null || request.getMessage() == null) {
            return ResponseEntity.badRequest().body(
                    new AssistantResponse(
                            "UNKNOWN",
                            "Please enter a message.",
                            false,
                            null,
                            null
                    )
            );
        }

        AssistantResponse response =
                assistantService.processMessage(request.getMessage());

        return ResponseEntity.ok(response);
    }
}
