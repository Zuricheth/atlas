package com.qianyu.atlas.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianyu.atlas.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Slf4j
public class MineruClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MineruProperties properties;
    private final HttpClient httpClient;

    public MineruClient(MineruProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .build();
    }

    public ConvertedDocument convert(Long userId, Path sourcePath, String originalFilename) {
        return convert(userId, sourcePath, originalFilename, properties.getTimeoutSeconds());
    }

    public ConvertedDocument convert(Long userId, Path sourcePath, String originalFilename, long timeoutSeconds) {
        if (!StringUtils.hasText(properties.getApiToken())) {
            throw new BizException("MinerU API Token 未配置");
        }
        if (!Files.isRegularFile(sourcePath)) {
            throw new BizException("待转换文件不存在");
        }

        String filename = StringUtils.hasText(originalFilename)
                ? originalFilename
                : sourcePath.getFileName().toString();
        String dataId = "atlas_" + (userId == null ? "user" : userId) + "_" + UUID.randomUUID().toString().replace("-", "");
        FileUrlsBatch batch = createFileUrlsBatch(filename, dataId);
        uploadFile(batch.uploadUrl(), sourcePath);
        ExtractResult result = pollBatchResult(batch.batchId(), timeoutSeconds);
        String markdown = downloadZipMarkdown(result.fullZipUrl());
        if (!StringUtils.hasText(markdown)) {
            throw new BizException(500, "MinerU 没有返回可读 Markdown 文本");
        }
        return new ConvertedDocument(
                normalizeText(markdown),
                dataId,
                result.fullZipUrl(),
                "MinerU"
        );
    }

    public boolean canUseAgentLightweight(Path sourcePath) {
        try {
            return properties.isAgentEnabled()
                    && Files.isRegularFile(sourcePath)
                    && Files.size(sourcePath) <= Math.max(1, properties.getAgentMaxFileSizeBytes());
        } catch (IOException exception) {
            return false;
        }
    }

    public ConvertedDocument convertWithAgent(Long userId, Path sourcePath, String originalFilename, long timeoutSeconds) {
        if (!Files.isRegularFile(sourcePath)) {
            throw new BizException("待转换文件不存在");
        }
        String filename = StringUtils.hasText(originalFilename)
                ? originalFilename
                : sourcePath.getFileName().toString();
        AgentUpload upload = createAgentUpload(filename);
        uploadFile(upload.fileUrl(), sourcePath);
        String markdownUrl = pollAgentResult(upload.taskId(), timeoutSeconds);
        String markdown = downloadText(markdownUrl);
        if (!StringUtils.hasText(markdown)) {
            throw new BizException(500, "MinerU Agent 没有返回可读 Markdown 文本");
        }
        return new ConvertedDocument(
                normalizeText(markdown),
                upload.taskId(),
                markdownUrl,
                "MinerU Agent"
        );
    }

    private FileUrlsBatch createFileUrlsBatch(String filename, String dataId) {
        Map<String, Object> body = Map.of(
                "files", List.of(Map.of("name", filename, "data_id", dataId)),
                "enable_formula", true,
                "enable_table", true,
                "model_version", properties.getModelVersion()
        );
        JsonNode data = apiJson("POST", "file-urls/batch", body).path("data");
        String batchId = data.path("batch_id").asText("");
        String uploadUrl = data.path("file_urls").isArray() && data.path("file_urls").size() > 0
                ? data.path("file_urls").get(0).asText("")
                : "";
        if (!StringUtils.hasText(batchId) || !StringUtils.hasText(uploadUrl)) {
            throw new BizException(500, "MinerU 未返回上传地址");
        }
        return new FileUrlsBatch(batchId, uploadUrl);
    }

    private void uploadFile(String uploadUrl, Path sourcePath) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .PUT(HttpRequest.BodyPublishers.ofFile(sourcePath))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(502, "MinerU 文件上传失败：http_status=" + response.statusCode());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("MinerU 文件上传失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "MinerU 文件上传被中断");
        }
    }

    private ExtractResult pollBatchResult(String batchId, long timeoutSeconds) {
        long started = System.currentTimeMillis();
        long deadline = started + Math.max(1, timeoutSeconds) * 1000L;
        long sleepMillis = Math.max(250, properties.getPollIntervalMillis());
        while (System.currentTimeMillis() < deadline) {
            JsonNode data = apiJson("GET", "extract-results/batch/" + batchId, null).path("data");
            JsonNode results = data.path("extract_result");
            if (results.isArray() && results.size() > 0) {
                JsonNode first = results.get(0);
                String state = first.path("state").asText("");
                if ("done".equalsIgnoreCase(state)) {
                    String zipUrl = first.path("full_zip_url").asText("");
                    if (!StringUtils.hasText(zipUrl)) {
                        throw new BizException(500, "MinerU 解析完成但未返回 full_zip_url");
                    }
                    return new ExtractResult(zipUrl);
                }
                if ("failed".equalsIgnoreCase(state)) {
                    String message = first.path("err_msg").asText("unknown");
                    throw new BizException(502, "MinerU 解析失败：" + message);
                }
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BizException(500, "MinerU 轮询被中断");
            }
        }
        throw new BizException(504, "MinerU 解析超时");
    }

    private String downloadZipMarkdown(String zipUrl) {
        byte[] bytes = downloadZip(zipUrl);
        return extractMarkdownFromZip(bytes);
    }

    private byte[] downloadZip(String zipUrl) {
        RuntimeException last = null;
        int maxAttempts = Math.max(1, properties.getDownloadRetries());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(zipUrl))
                        .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                // 429 / 5xx 才值得重试, 其他 4xx 直接放弃
                if (status != 429 && status < 500) {
                    throw new BizException(502, "MinerU 结果下载失败：http_status=" + status);
                }
                last = new BizException(502, "MinerU 结果下载失败：http_status=" + status);
            } catch (IOException exception) {
                last = new UncheckedIOException("MinerU 结果下载失败", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BizException(500, "MinerU 结果下载被中断");
            }
            if (attempt < maxAttempts) {
                sleepBackoff(attempt);
            }
            log.warn("[MinerU] result download failed, attempt={}/{}, url={}", attempt, maxAttempts, zipUrl);
        }
        throw last == null ? new BizException(502, "MinerU 结果下载失败") : last;
    }

    private void sleepBackoff(int attempt) {
        long base = 500L * (long) Math.pow(2, Math.max(0, attempt - 1));
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 300);
        try {
            Thread.sleep(Math.min(10_000L, base) + jitter);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String downloadText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(502, "MinerU Markdown 下载失败：http_status=" + response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            throw new UncheckedIOException("MinerU Markdown 下载失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "MinerU Markdown 下载被中断");
        }
    }

    private AgentUpload createAgentUpload(String filename) {
        Map<String, Object> body = Map.of("file_name", filename);
        JsonNode data = agentJson("POST", "parse/file", body).path("data");
        String taskId = data.path("task_id").asText("");
        String fileUrl = data.path("file_url").asText("");
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(fileUrl)) {
            throw new BizException(500, "MinerU Agent 未返回上传地址");
        }
        return new AgentUpload(taskId, fileUrl);
    }

    private String pollAgentResult(String taskId, long timeoutSeconds) {
        long started = System.currentTimeMillis();
        long deadline = started + Math.max(1, timeoutSeconds) * 1000L;
        long sleepMillis = Math.max(250, properties.getPollIntervalMillis());
        while (System.currentTimeMillis() < deadline) {
            JsonNode data = agentJson("GET", "parse/" + taskId, null).path("data");
            String state = data.path("state").asText("");
            if ("done".equalsIgnoreCase(state)) {
                String markdownUrl = data.path("markdown_url").asText("");
                if (!StringUtils.hasText(markdownUrl)) {
                    throw new BizException(500, "MinerU Agent 解析完成但未返回 markdown_url");
                }
                return markdownUrl;
            }
            if ("failed".equalsIgnoreCase(state)) {
                throw new BizException(502, "MinerU Agent 解析失败：" + data.path("err_msg").asText("unknown"));
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BizException(500, "MinerU Agent 轮询被中断");
            }
        }
        throw new BizException(504, "MinerU Agent 解析超时");
    }

    private JsonNode apiJson(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Authorization", "Bearer " + properties.getApiToken());
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = MAPPER.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(502, "MinerU API 请求失败：http_status=" + response.statusCode() + "，body=" + safeBody(response.body()));
            }
            int code = payload.path("code").asInt(-1);
            if (code != 0) {
                throw new BizException(502, "MinerU API 返回错误：code=" + code + "，msg=" + payload.path("msg").asText(""));
            }
            return payload;
        } catch (IOException exception) {
            throw new UncheckedIOException("MinerU API 请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "MinerU API 请求被中断");
        }
    }

    private JsonNode agentJson(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(agentEndpoint(path))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = MAPPER.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(502, "MinerU Agent 请求失败：http_status=" + response.statusCode() + "，body=" + safeBody(response.body()));
            }
            int code = payload.path("code").asInt(-1);
            if (code != 0) {
                throw new BizException(502, "MinerU Agent 返回错误：code=" + code + "，msg=" + payload.path("msg").asText(""));
            }
            return payload;
        } catch (IOException exception) {
            throw new UncheckedIOException("MinerU Agent 请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "MinerU Agent 请求被中断");
        }
    }

    private URI endpoint(String path) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(base + suffix);
    }

    private URI agentEndpoint(String path) {
        String base = properties.getAgentBaseUrl().replaceAll("/+$", "");
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(base + suffix);
    }

    static String extractMarkdownFromZip(byte[] zipBytes) {
        List<String> markdownFiles = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".md")) {
                    continue;
                }
                markdownFiles.add(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("MinerU zip 结果解析失败", exception);
        }
        return markdownFiles.stream()
                .map(MineruClient::normalizeText)
                .filter(StringUtils::hasText)
                .max(Comparator.comparingInt(String::length))
                .orElseThrow(() -> new BizException(500, "MinerU zip 结果中没有 Markdown 文件"));
    }

    private static String normalizeText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String safeBody(String body) {
        if (!StringUtils.hasText(body)) return "";
        String cleaned = body.replace(properties.getApiToken(), "[redacted]").trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private record FileUrlsBatch(String batchId, String uploadUrl) {
    }

    private record ExtractResult(String fullZipUrl) {
    }

    private record AgentUpload(String taskId, String fileUrl) {
    }
}
