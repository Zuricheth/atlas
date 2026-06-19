package com.qianyu.atlas.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MineruClientTest {

    @Test
    void extractsLongestMarkdownFromMineruZip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("images/ignored.txt"));
            zip.write("ignored".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("short.md"));
            zip.write("short".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("full/document.md"));
            zip.write("Title\r\n\r\n\r\nBody   text".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        String markdown = MineruClient.extractMarkdownFromZip(out.toByteArray());

        assertEquals("Title\n\nBody text", markdown);
    }
}
