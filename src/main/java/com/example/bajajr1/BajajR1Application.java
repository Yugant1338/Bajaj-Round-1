package com.bajaj;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
public class BajajR1Application implements CommandLineRunner {

    private final RestTemplate restTemplate;

    private static final String NAME = "Yugant Chaudhury";
    private static final String REGNO = "22BRS1338"; // your regNo
    private static final String EMAIL = "yugant.chaudhury2022@vitstudent.ac.in";

    public BajajR1Application(RestTemplateBuilder b) {
        this.restTemplate = b
                .setConnectTimeout(Duration.ofSeconds(15))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(BajajR1Application.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        String generateUrl = System.getenv().getOrDefault("BFHL_GENERATE_URL",
                "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook/JAVA");
        Map<String, String> payload = new HashMap<>();
        payload.put("name", NAME);
        payload.put("regNo", REGNO);
        payload.put("email", EMAIL);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> req = new HttpEntity<>(payload, headers);

        System.out.println("Calling generateWebhook endpoint: " + generateUrl);
        ResponseEntity<String> genRespEntity = restTemplate.postForEntity(generateUrl, req, String.class);

        if (!genRespEntity.getStatusCode().is2xxSuccessful() || genRespEntity.getBody() == null) {
            System.err.println("generateWebhook failed: status=" + genRespEntity.getStatusCode() + ", body=" + genRespEntity.getBody());
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        GenerateResponse genResp = mapper.readValue(genRespEntity.getBody(), GenerateResponse.class);

        if (!StringUtils.hasText(genResp.getWebhook()) || !StringUtils.hasText(genResp.getAccessToken())) {
            System.err.println("Invalid response from generateWebhook: " + genRespEntity.getBody());
            return;
        }

        System.out.println("Received webhook: " + genResp.getWebhook());
        System.out.println("Received accessToken (truncated): " + (genResp.getAccessToken().length() > 40 ? genResp.getAccessToken().substring(0,40) + "..." : genResp.getAccessToken()));

        int lastTwo = extractLastTwoDigits(REGNO);
        boolean isOdd = (lastTwo % 2) == 1;
        String questionUrl = isOdd
                ? "https://drive.google.com/file/d/1IeSI6l6KoSQAFfRihIT9tEDICtoz-G/view?usp=sharing"
                : "https://drive.google.com/file/d/143MR5cLFrlNEuHzzWJ5RHnEWuijuM9X/view?usp=sharing";
        System.out.println("Determined question: " + (isOdd ? "Question 1 (odd)" : "Question 2 (even)"));
        System.out.println("Question file URL (for your reference): " + questionUrl);

        String downloadDir = System.getProperty("user.dir") + "/downloaded-questions";
        try {
            Files.createDirectories(Path.of(downloadDir));
            String saved = attemptDownloadGoogleDriveFile(genRespEntity.getBody(), questionUrl, Path.of(downloadDir));
            if (saved != null) System.out.println("Saved question to: " + saved);
        } catch (Exception e) {
            // ignore download errors — not critical
        }

        String finalQuery = System.getenv("FINAL_QUERY");
        if (!StringUtils.hasText(finalQuery)) {
            try {
                ClassPathResource res = new ClassPathResource("solution.sql");
                if (res.exists()) {
                    try (InputStream in = res.getInputStream()) {
                        finalQuery = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                        System.out.println("Loaded finalQuery from solution.sql");
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!StringUtils.hasText(finalQuery)) {
            System.out.println("No FINAL_QUERY env var or solution.sql found. Using placeholder query.");
            finalQuery = "SELECT 'NO_SOLUTION_PROVIDED' AS note;";
        }

        HttpHeaders submitHeaders = new HttpHeaders();
        submitHeaders.setContentType(MediaType.APPLICATION_JSON);
        String authScheme = System.getenv().getOrDefault("AUTH_SCHEME", "Bearer"); // set to "" if API expects token without prefix
        String authHeaderValue = (authScheme == null || authScheme.isBlank()) ? genResp.getAccessToken() : authScheme + " " + genResp.getAccessToken();
        submitHeaders.set("Authorization", authHeaderValue);

        Map<String, String> submitBody = new HashMap<>();
        submitBody.put("finalQuery", finalQuery);

        HttpEntity<Map<String, String>> submitReq = new HttpEntity<>(submitBody, submitHeaders);

        System.out.println("Submitting finalQuery to webhook...");
        try {
            ResponseEntity<String> submitResp = restTemplate.postForEntity(genResp.getWebhook(), submitReq, String.class);
            System.out.println("Submission status: " + submitResp.getStatusCode());
            System.out.println("Submission response body: " + submitResp.getBody());
        } catch (Exception e) {
            System.err.println("Failed to submit to webhook: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Flow complete.");
    }

    private static int extractLastTwoDigits(String regNo) {
        if (!StringUtils.hasText(regNo)) return 0;
        Pattern p = Pattern.compile("(\\d+)");
        Matcher m = p.matcher(regNo);
        StringBuilder b = new StringBuilder();
        while (m.find()) b.append(m.group(1));
        String digits = b.toString();
        if (digits.length() >= 2) {
            return Integer.parseInt(digits.substring(digits.length() - 2));
        } else if (digits.length() > 0) {
            return Integer.parseInt(digits);
        } else {
            return 0;
        }
    }
    private static String attemptDownloadGoogleDriveFile(String responseBody, String driveViewUrl, Path saveDir) {
        try {
            String fileId = null;
            Pattern p = Pattern.compile("/d/([a-zA-Z0-9_-]+)");
            Matcher m = p.matcher(driveViewUrl);
            if (m.find()) fileId = m.group(1);

            if (fileId == null) return null;
            String downloadUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
            byte[] bytes = new RestTemplate().getForObject(downloadUrl, byte[].class);
            if (bytes == null || bytes.length == 0) return null;
            Path out = saveDir.resolve("question-" + fileId + ".pdf");
            Files.write(out, bytes);
            return out.toAbsolutePath().toString();
        } catch (Exception e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenerateResponse {
        @JsonProperty("webhook")
        private String webhook;
        @JsonProperty("accessToken")
        private String accessToken;

        public String getWebhook() { return webhook; }
        public void setWebhook(String webhook) { this.webhook = webhook; }

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    }
}
