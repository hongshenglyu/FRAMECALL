package com.footballref.assist.service;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.enums.EventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class AiClient {
    private final RestTemplate restTemplate;
    private final String aiServiceUrl;
    private final String internalToken;

    public AiClient(
            RestTemplate restTemplate,
            @Value("${app.ai-service-url}") String aiServiceUrl,
            @Value("${app.internal-token}") String internalToken
    ) {
        this.restTemplate = restTemplate;
        this.aiServiceUrl = aiServiceUrl;
        this.internalToken = internalToken;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(AnalysisTask task, String callbackUrl) {
        Map<String, Object> payload = Map.of(
                "taskId", task.getId(),
                "videoPath", task.getVideo().getOriginalPath(),
                "outputDir", task.getOutputDir(),
                "sensitivity", task.getSensitivity().name(),
                "enabledEvents", List.of(EventType.FOUL.name(), EventType.HANDBALL.name(), EventType.OFFSIDE.name()),
                "callbackUrl", callbackUrl,
                "internalToken", internalToken
        );
        return restTemplate.postForObject(aiServiceUrl + "/internal/analyze", payload, Map.class);
    }

    public void cancel(Long taskId) {
        try {
            restTemplate.postForObject(aiServiceUrl + "/internal/tasks/" + taskId + "/cancel", Map.of(), Map.class);
        } catch (Exception ignored) {
        }
    }
}

