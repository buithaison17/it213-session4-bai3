package com.example.bai3;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "incident_reports")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class IncidentReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String orderCode;
    private String licensePlate;
    private String incidentType;
    private String urgency;
}
