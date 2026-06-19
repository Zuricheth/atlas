package com.qianyu.atlas.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "atlas.mineru")
public class MineruProperties {
    private boolean enabled = true;
    private String baseUrl = "https://mineru.net/api/v4";
    private String agentBaseUrl = "https://mineru.net/api/v1/agent";
    private boolean agentEnabled = true;
    private String apiToken = "";
    private String modelVersion = "pipeline";
    private long timeoutSeconds = 300;
    private long connectTimeoutSeconds = 30;
    private long pollIntervalMillis = 5000;
    private long officeAttemptTimeoutSeconds = 90;
    private long agentMaxFileSizeBytes = 10L * 1024L * 1024L;
    private int downloadRetries = 3;
    private String supportedExtensions = "pdf,doc,docx";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public void setAgentBaseUrl(String agentBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
    }

    public boolean isAgentEnabled() {
        return agentEnabled;
    }

    public void setAgentEnabled(boolean agentEnabled) {
        this.agentEnabled = agentEnabled;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    public void setPollIntervalMillis(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
    }

    public long getOfficeAttemptTimeoutSeconds() {
        return officeAttemptTimeoutSeconds;
    }

    public void setOfficeAttemptTimeoutSeconds(long officeAttemptTimeoutSeconds) {
        this.officeAttemptTimeoutSeconds = officeAttemptTimeoutSeconds;
    }

    public long getAgentMaxFileSizeBytes() {
        return agentMaxFileSizeBytes;
    }

    public void setAgentMaxFileSizeBytes(long agentMaxFileSizeBytes) {
        this.agentMaxFileSizeBytes = agentMaxFileSizeBytes;
    }

    public int getDownloadRetries() {
        return downloadRetries;
    }

    public void setDownloadRetries(int downloadRetries) {
        this.downloadRetries = downloadRetries;
    }

    public String getSupportedExtensions() {
        return supportedExtensions;
    }

    public void setSupportedExtensions(String supportedExtensions) {
        this.supportedExtensions = supportedExtensions;
    }

    public Set<String> supportedExtensionSet() {
        if (!StringUtils.hasText(supportedExtensions)) return Set.of();
        return Arrays.stream(supportedExtensions.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }
}
