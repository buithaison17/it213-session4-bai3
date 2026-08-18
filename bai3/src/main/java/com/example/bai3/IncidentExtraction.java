package com.example.bai3;

public record IncidentExtraction(
        String orderCode,
        String licensePlate,
        String incidentType,
        String urgency
) {
}
