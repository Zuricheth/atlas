package com.qianyu.atlas.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxMarkdownExtractorTest {
    @Test
    void extractsParagraphsFromDocxDocumentXml() throws Exception {
        Path dir = Path.of("target", "docx-extractor-test").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path docx = dir.resolve("sample.docx");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(docx), StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                          <w:body>
                            <w:p><w:r><w:t>第一段 Word 内容</w:t></w:r></w:p>
                            <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                          </w:body>
                        </w:document>
                        """.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            ConvertedDocument converted = new DocxMarkdownExtractor().extract(docx, "sample.docx");

            assertTrue(converted.text().contains("第一段 Word 内容"));
            assertTrue(converted.text().contains("Second paragraph"));
        } finally {
            Files.deleteIfExists(docx);
        }
    }
}
