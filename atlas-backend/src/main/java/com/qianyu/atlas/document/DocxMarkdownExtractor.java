package com.qianyu.atlas.document;

import com.qianyu.atlas.common.BizException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class DocxMarkdownExtractor {

    public boolean supports(String extension) {
        return "docx".equalsIgnoreCase(extension);
    }

    public ConvertedDocument extract(Path path, String originalFilename) {
        String documentXml = readDocumentXml(path);
        String markdown = normalizeText(extractParagraphs(documentXml));
        if (!StringUtils.hasText(markdown)) {
            throw new BizException(500, "Word 文档未提取到可读文本");
        }
        return new ConvertedDocument(markdown, originalFilename, "", "DOCX local fallback");
    }

    private String readDocumentXml(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new BizException("不是有效的 docx 文件");
            }
            return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(500, "Word 文档读取失败：" + exception.getMessage());
        }
    }

    private String extractParagraphs(String documentXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(documentXml)));
            NodeList paragraphs = document.getElementsByTagNameNS("*", "p");
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                String text = paragraphText(paragraphs.item(i));
                if (StringUtils.hasText(text)) {
                    lines.add(text.trim());
                }
            }
            return String.join("\n\n", lines);
        } catch (Exception exception) {
            throw new BizException(500, "Word 文档解析失败：" + exception.getMessage());
        }
    }

    private String paragraphText(Node paragraph) {
        StringBuilder builder = new StringBuilder();
        collectText(paragraph, builder);
        return builder.toString();
    }

    private void collectText(Node node, StringBuilder builder) {
        if (node == null) return;
        if ("t".equals(node.getLocalName())) {
            builder.append(node.getTextContent());
        } else if ("tab".equals(node.getLocalName())) {
            builder.append('\t');
        } else if ("br".equals(node.getLocalName())) {
            builder.append('\n');
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectText(children.item(i), builder);
        }
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
