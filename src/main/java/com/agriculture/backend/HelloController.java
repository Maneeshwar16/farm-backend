package com.agriculture.backend;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@CrossOrigin(origins = "http://localhost:5173")
@RestController
public class HelloController {
    @GetMapping("/hello")
    public String Hello(){
        return "hello from backend intellij";
    }

}
