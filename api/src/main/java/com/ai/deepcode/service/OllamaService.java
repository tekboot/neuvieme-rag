    package com.ai.deepcode.service;

    import com.ai.deepcode.dto.OllamaGenerateRequest;
    import com.ai.deepcode.dto.OllamaGenerateResponse;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.stereotype.Service;
    import org.springframework.web.reactive.function.client.WebClient;
    import org.springframework.web.reactive.function.client.WebClientResponseException;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    @Service
    public class OllamaService {

        private final WebClient webClient;
        private final String model;
        private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

        public OllamaService(
                WebClient ollamaWebClient,
                @Value("${ollama.model}") String model
        ) {
            this.webClient = ollamaWebClient;
            this.model = model;
        }

        public String generate(String prompt) {
            OllamaGenerateRequest request =
                    new OllamaGenerateRequest(model, prompt, false);

            try{
                OllamaGenerateResponse response = webClient.post()
                        .uri("/api/generate")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(OllamaGenerateResponse.class)
                        .block();
                return response == null ? "" : response.response();

            } catch (WebClientResponseException e) {
            log.error("Ollama error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }

        }
    }
