package com.qianyu.atlas.vcp;

import com.qianyu.atlas.common.BizException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VcpNotebookServiceSecurityTest {

    @Test
    void rejectsDotDotNotebookNameBeforeFilesystemOperations() throws Exception {
        Path root = Path.of("target", "vcp-security-test").toAbsolutePath().normalize();
        Files.createDirectories(root);
        VcpProperties properties = new VcpProperties();
        properties.setDailyNoteRoot(root.toString());
        VcpNotebookService service = new VcpNotebookService(properties);

        assertThrows(BizException.class, () -> service.notebookPath(".."));
        assertThrows(BizException.class, () -> service.deleteNotebook("..", true));
    }

    @Test
    void rejectsDotDotFilenameBeforeResolvingPath() throws Exception {
        Path root = Path.of("target", "vcp-security-test-files").toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("memory"));
        VcpProperties properties = new VcpProperties();
        properties.setDailyNoteRoot(root.toString());
        VcpNotebookService service = new VcpNotebookService(properties);

        assertThrows(BizException.class, () -> service.filePath("memory", ".."));
        assertThrows(BizException.class, () -> service.saveFile("memory", "..", "content"));
    }

    @Test
    void userScopedRootSeparatesNotebookFilesByUser() throws Exception {
        Path root = Path.of("target", "vcp-user-scope-test-" + System.nanoTime()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        VcpProperties properties = new VcpProperties();
        properties.setDailyNoteRoot(root.toString());
        properties.setUserScopedRoot(true);
        VcpNotebookService service = new VcpNotebookService(properties);

        service.createNotebook(1L, "用户一");
        service.saveFile(1L, "用户一", "memory", "private-one");
        service.createNotebook(2L, "用户二");
        service.saveFile(2L, "用户二", "memory", "private-two");

        assertEquals(List.of("用户一"), service.listNotebooks(1L).stream().map(VcpDtos.NotebookView::name).toList());
        assertEquals(List.of("用户二"), service.listNotebooks(2L).stream().map(VcpDtos.NotebookView::name).toList());
        assertThrows(BizException.class, () -> service.readFile(2L, "用户一", "memory.md"));
    }
}
