package com.example.bai3;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class IncidentETLController {
    private final IncidentETLService incidentETLService;

    @GetMapping
    public IncidentReport incidentReport(@RequestParam String rawText) {
        return incidentETLService.processReport(rawText);
    }
}
