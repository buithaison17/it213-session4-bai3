package com.example.bai3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentETLService {
    private final ChatModel chatModel;
    private final IncidentRepository incidentRepository;

    @Transactional
    public IncidentReport processReport(String rawMessage) {
        BeanOutputConverter<IncidentExtraction> converter = new BeanOutputConverter<>(IncidentExtraction.class);
        String template = """
                Hãy phân tích tin nhắn sau {rawMessage} sau đó trả về dữ liệu JSON có
                định dạng {format}
                Chỉ trả về định dạng JSON không dùng định dạng khác
                Chỉ sử dụng ngôn ngôn ngữ tiếng việt
                """;
        Prompt prompt = new PromptTemplate(template).create(
                Map.of(
                        "rawMessage", rawMessage,
                        "format", converter.getFormat()
                )
        );
        log.info("AI nhận tin nhắn");
        String response = chatModel.call(prompt).getResult().getOutput().getText();
        IncidentExtraction extraction;
        try {
            extraction = converter.convert(response);
            log.info("Parse dữ liệu thành công");
        } catch (RuntimeException e) {
            log.error(e.getMessage());
            throw new RuntimeException(e);
        }

        if (extraction.orderCode() == null || extraction.orderCode().isBlank()) {
            throw new IllegalArgumentException("Order code không được để trống");
        }

        IncidentReport incidentReport = IncidentReport.builder()
                .incidentType(extraction.incidentType())
                .orderCode(extraction.orderCode())
                .licensePlate(extraction.licensePlate())
                .urgency(extraction.urgency())
                .build();

        return incidentRepository.save(incidentReport);
    }
}
