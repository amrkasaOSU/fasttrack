package com.fasttrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Every tunable the application reads, bound from {@code app.*}.
 *
 * <p>Each nested group exposes an {@code isConfigured()} method; the API
 * surfaces those through {@code GET /api/config} so the frontend can render
 * honest "not configured" states instead of broken features.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String frontendUrl = "http://localhost:3000";
    private String backendUrl = "http://localhost:8080";
    private boolean secureCookies = false;
    private boolean demoEnabled = true;
    /** Seed the demo account's data at start-up when it is empty. */
    private boolean demoSeedOnStartup = true;
    /** Base64-encoded 32-byte key for AES-256-GCM encryption of refresh tokens. */
    private String tokenEncryptionKey = "";

    private Google google = new Google();
    private Gmail gmail = new Gmail();
    private Llm llm = new Llm();
    private Sync sync = new Sync();
    private RateLimit rateLimit = new RateLimit();

    public boolean isTokenEncryptionConfigured() {
        return StringUtils.hasText(tokenEncryptionKey);
    }

    public static class Google {
        private String clientId = "";
        private String clientSecret = "";

        public boolean isConfigured() {
            return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
        }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    }

    /**
     * Gmail endpoints. Overridable so the sync can be tested end to end
     * against a stub; the defaults are Google's real endpoints.
     */
    public static class Gmail {
        private String rootUrl = "https://gmail.googleapis.com/";
        private String tokenUri = "https://oauth2.googleapis.com/token";

        public String getRootUrl() { return rootUrl; }
        public void setRootUrl(String rootUrl) { this.rootUrl = rootUrl; }
        public String getTokenUri() { return tokenUri; }
        public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }
    }

    public static class Llm {
        private String baseUrl = "https://api.groq.com/openai/v1";
        private String apiKey = "";
        private String model = "llama-3.3-70b-versatile";
        private int timeoutSeconds = 30;
        private int maxRetries = 1;
        /** Time-to-live for cached classification results, in days. */
        private int cacheTtlDays = 30;

        public boolean isConfigured() {
            return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
        }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getCacheTtlDays() { return cacheTtlDays; }
        public void setCacheTtlDays(int cacheTtlDays) { this.cacheTtlDays = cacheTtlDays; }
    }

    public static class Sync {
        private int initialDays = 120;
        private String cron = "0 */15 * * * *";
        private int batchSize = 50;
        private int maxMessagesPerRun = 2000;

        public int getInitialDays() { return initialDays; }
        public void setInitialDays(int initialDays) { this.initialDays = initialDays; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxMessagesPerRun() { return maxMessagesPerRun; }
        public void setMaxMessagesPerRun(int maxMessagesPerRun) { this.maxMessagesPerRun = maxMessagesPerRun; }
    }

    public static class RateLimit {
        private int syncPerHour = 12;
        private int fitChecksPerHour = 30;
        private int llmCallsPerHour = 300;
        private int uploadsPerHour = 10;

        public int getSyncPerHour() { return syncPerHour; }
        public void setSyncPerHour(int syncPerHour) { this.syncPerHour = syncPerHour; }
        public int getFitChecksPerHour() { return fitChecksPerHour; }
        public void setFitChecksPerHour(int fitChecksPerHour) { this.fitChecksPerHour = fitChecksPerHour; }
        public int getLlmCallsPerHour() { return llmCallsPerHour; }
        public void setLlmCallsPerHour(int llmCallsPerHour) { this.llmCallsPerHour = llmCallsPerHour; }
        public int getUploadsPerHour() { return uploadsPerHour; }
        public void setUploadsPerHour(int uploadsPerHour) { this.uploadsPerHour = uploadsPerHour; }
    }

    public String getFrontendUrl() { return frontendUrl; }
    public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }
    public String getBackendUrl() { return backendUrl; }
    public void setBackendUrl(String backendUrl) { this.backendUrl = backendUrl; }
    public boolean isSecureCookies() { return secureCookies; }
    public void setSecureCookies(boolean secureCookies) { this.secureCookies = secureCookies; }
    public boolean isDemoEnabled() { return demoEnabled; }
    public void setDemoEnabled(boolean demoEnabled) { this.demoEnabled = demoEnabled; }
    public boolean isDemoSeedOnStartup() { return demoSeedOnStartup; }
    public void setDemoSeedOnStartup(boolean demoSeedOnStartup) { this.demoSeedOnStartup = demoSeedOnStartup; }
    public String getTokenEncryptionKey() { return tokenEncryptionKey; }
    public void setTokenEncryptionKey(String tokenEncryptionKey) { this.tokenEncryptionKey = tokenEncryptionKey; }
    public Google getGoogle() { return google; }
    public void setGoogle(Google google) { this.google = google; }
    public Gmail getGmail() { return gmail; }
    public void setGmail(Gmail gmail) { this.gmail = gmail; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Sync getSync() { return sync; }
    public void setSync(Sync sync) { this.sync = sync; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
}
