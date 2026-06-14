package com.example.jactl;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
