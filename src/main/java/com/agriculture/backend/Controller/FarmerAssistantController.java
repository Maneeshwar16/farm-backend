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
            FarmerAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/message")
    public ResponseEntity<AssistantResponse> sendMessage(
            @RequestBody AssistantRequest request) {

        if (request == null
                || request.getMessage() == null
                || request.getMessage().isBlank()) {

            AssistantResponse response = new AssistantResponse(
                    "UNKNOWN",
                    "Please enter a message.",
                    false,
                    null,
                    null
            );

            return ResponseEntity.badRequest().body(response);
        }

        if (request.getFarmerId() == null
                || request.getFarmerId() <= 0) {

            AssistantResponse response = new AssistantResponse(
                    "UNKNOWN",
                    "Please log in as a farmer before continuing.",
                    false,
                    null,
                    null
            );

            return ResponseEntity.badRequest().body(response);
        }

        AssistantResponse response =
                assistantService.processMessage(
                        request.getMessage(),
                        request.getFarmerId(),
                        request.getConversationId(),
                        request.isConfirmed(),
                        request.isCancelled()
                );

        return ResponseEntity.ok(response);
    }
}
