package com.qianyu.atlas.document;

import com.qianyu.atlas.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
public class DocumentConversionService {
    private final MineruClient mineruClient;
    private final MineruProperties properties;
    private final DocxMarkdownExtractor docxMarkdownExtractor;

    public DocumentConversionService(MineruClient mineruClient,
                                     MineruProperties properties,
                                     DocxMarkdownExtractor docxMarkdownExtractor) {
        this.mineruClient = mineruClient;
        this.properties = properties;
        this.docxMarkdownExtractor = docxMarkdownExtractor;
    }

    public boolean shouldConvert(String extension, String contentType) {
        String ext = normalizeExtension(extension);
        if (docxMarkdownExtractor.supports(ext)) {
            return true;
        }
        if (!properties.isEnabled()) {
            return false;
        }
        if (properties.supportedExtensionSet().contains(ext)) {
            return true;
        }
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return lowerType.contains("pdf")
                || lowerType.contains("wordprocessingml")
                || lowerType.equals("application/msword");
    }

    public ConvertedDocument convert(Long userId,
                                     Path sourcePath,
                                     String originalFilename,
                                     String extension,
                                     String contentType) {
        String ext = normalizeExtension(extension);
        try {
            if (!properties.isEnabled() && docxMarkdownExtractor.supports(ext)) {
                return docxMarkdownExtractor.extract(sourcePath, originalFilename);
            }
            if (!shouldConvert(extension, contentType)) {
                throw new BizException("MinerU 未启用或当前文件类型未配置为可转换");
            }
            if (isOfficeDocument(ext)) {
                ConvertedDocument converted;
                if (mineruClient.canUseAgentLightweight(sourcePath)) {
                    converted = mineruClient.convertWithAgent(userId, sourcePath, originalFilename, properties.getOfficeAttemptTimeoutSeconds());
                } else {
                    converted = mineruClient.convert(userId, sourcePath, originalFilename, properties.getOfficeAttemptTimeoutSeconds());
                }
                if (looksGarbled(converted.text())) {
                    log.warn("[DocumentConversion] MinerU office conversion looks garbled, fallback to local docx extraction, userId={}, file={}",
                            userId, originalFilename);
                    return docxMarkdownExtractor.extract(sourcePath, originalFilename);
                }
                return converted;
            }
            return mineruClient.convert(userId, sourcePath, originalFilename);
        } catch (BizException exception) {
            if (docxMarkdownExtractor.supports(ext)) {
                log.warn("[DocumentConversion] MinerU docx conversion failed, fallback to local docx extraction, userId={}, file={}",
                        userId, originalFilename, exception);
                return docxMarkdownExtractor.extract(sourcePath, originalFilename);
            }
            throw exception;
        } catch (Exception exception) {
            log.warn("[DocumentConversion] MinerU conversion failed, userId={}, file={}",
                    userId, originalFilename, exception);
            if (docxMarkdownExtractor.supports(ext)) {
                return docxMarkdownExtractor.extract(sourcePath, originalFilename);
            }
            throw new BizException(500, "MinerU 转换失败：" + exception.getMessage());
        }
    }

    private String normalizeExtension(String extension) {
        if (!StringUtils.hasText(extension)) return "";
        return extension.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
    }

    private boolean isOfficeDocument(String extension) {
        return "doc".equals(extension) || "docx".equals(extension);
    }

    private boolean looksGarbled(String text) {
        if (!StringUtils.hasText(text)) return false;
        int garbledRuns = 0;
        for (String token : text.split("\\s+")) {
            if (token.contains("???")) {
                garbledRuns++;
                if (garbledRuns >= 2) return true;
            }
        }
        return text.contains("??????");
    }
}
