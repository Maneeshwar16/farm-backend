
package com.agriculture.backend.Service;

import com.agriculture.backend.dto.AssistantResponse;
import org.springframework.stereotype.Service;

@Service
public class FarmerAssistantService {

    private final FarmerTextParser parser;

    public FarmerAssistantService(FarmerTextParser parser) {
        this.parser = parser;
    }

    public AssistantResponse processMessage(String message) {
        return parser.parse(message);
    }
}
