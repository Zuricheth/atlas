package com.qianyu.atlas.paper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qianyu.atlas.chat.ChatClientFactory;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.document.DocumentConversionService;
import com.qianyu.atlas.note.NoteService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperServiceSecurityTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PaperAttachment.class);
    }

    @Test
    void rejectsDownloadedPaperPathOutsideUserStorageRoot() throws Exception {
        Path root = Path.of("target", "paper-security-test").toAbsolutePath().normalize();
        Path outside = root.resolve("outside.pdf").normalize();
        Files.createDirectories(root);
        Files.writeString(outside, "not actually a pdf");
        try {
            PaperStorageProperties properties = new PaperStorageProperties();
            properties.setRoot(root.resolve("papers").toString());
            PaperAttachmentMapper mapper = mock(PaperAttachmentMapper.class);
            when(mapper.selectOne(any())).thenReturn(attachment(7L, 1L, outside));
            PaperService service = new PaperService(
                    mapper,
                    properties,
                    mock(NoteService.class),
                    mock(ChatClientFactory.class),
                    mock(DocumentConversionService.class)
            );

            BizException exception = assertThrows(BizException.class, () -> service.getPaperFile(1L, 7L));

            assertEquals("论文原文路径无效", exception.getMessage());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private PaperAttachment attachment(Long id, Long userId, Path storagePath) {
        PaperAttachment attachment = new PaperAttachment();
        attachment.setId(id);
        attachment.setUserId(userId);
        attachment.setOriginalFilename("paper.pdf");
        attachment.setStoragePath(storagePath.toString());
        attachment.setContentType("application/pdf");
        attachment.setDeleted(0);
        return attachment;
    }
}
