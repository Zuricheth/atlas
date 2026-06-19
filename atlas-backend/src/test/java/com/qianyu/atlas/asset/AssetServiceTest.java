package com.qianyu.atlas.asset;

import com.qianyu.atlas.asset.AssetDtos.BulkAssetResponse;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.deepwiki.DeepWikiPageMapper;
import com.qianyu.atlas.inbox.InboxFileMapper;
import com.qianyu.atlas.library.LibraryItem;
import com.qianyu.atlas.library.LibraryItemMapper;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.notebook.NotebookMapper;
import com.qianyu.atlas.paper.PaperAttachment;
import com.qianyu.atlas.paper.PaperAttachmentMapper;
import com.qianyu.atlas.rag.NoteChunkMapper;
import com.qianyu.atlas.vcp.VcpMemoryDraftMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {
    private final LibraryItemMapper libraryItemMapper = mock(LibraryItemMapper.class);
    private final PaperAttachmentMapper paperAttachmentMapper = mock(PaperAttachmentMapper.class);
    private final NoteMapper noteMapper = mock(NoteMapper.class);
    private final AssetService service = new AssetService(
            libraryItemMapper,
            paperAttachmentMapper,
            noteMapper,
            mock(NoteChunkMapper.class),
            mock(DeepWikiPageMapper.class),
            mock(VcpMemoryDraftMapper.class),
            mock(InboxFileMapper.class),
            mock(NotebookMapper.class)
    );

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), LibraryItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PaperAttachment.class);
    }

    @Test
    void rejectsInvalidAssetKeys() {
        assertThrows(BizException.class, () -> service.trash(7L, List.of("note:1")));
    }

    @Test
    void trashesFilesWithoutDeletingNotes() {
        LibraryItem library = libraryItem(11L, "brief.pdf", "unused");
        PaperAttachment paper = paper(22L, "brief.pdf", "unused");
        when(libraryItemMapper.selectList(any())).thenReturn(List.of(library));
        when(paperAttachmentMapper.selectList(any())).thenReturn(List.of(paper));
        when(libraryItemMapper.update(isNull(), any())).thenReturn(1);
        when(paperAttachmentMapper.update(isNull(), any())).thenReturn(1);

        BulkAssetResponse response = service.trash(7L, List.of("library:11", "paper:22"));

        assertEquals(2, response.count());
        verify(noteMapper, never()).update(any(), any());
        verify(noteMapper, never()).delete(any());
    }

    @Test
    void exportsBothAssetTypesAndRenamesDuplicates() throws Exception {
        Path tempDir = Path.of("target", "test-assets");
        Files.createDirectories(tempDir);
        Path libraryFile = tempDir.resolve("library.bin");
        Path paperFile = tempDir.resolve("paper.bin");
        try {
            Files.writeString(libraryFile, "library-content");
            Files.writeString(paperFile, "paper-content");
            when(libraryItemMapper.selectList(any())).thenReturn(List.of(libraryItem(11L, "brief.pdf", libraryFile.toString())));
            when(paperAttachmentMapper.selectList(any())).thenReturn(List.of(paper(22L, "brief.pdf", paperFile.toString())));

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            service.writeArchive(7L, List.of("library:11", "paper:22"), output);

            List<String> entries = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
            }
            assertEquals(List.of("brief.pdf", "brief (2).pdf"), entries);
        } finally {
            Files.deleteIfExists(libraryFile);
            Files.deleteIfExists(paperFile);
        }
    }

    private LibraryItem libraryItem(Long id, String filename, String storagePath) {
        LibraryItem item = new LibraryItem();
        item.setId(id);
        item.setOriginalFilename(filename);
        item.setStoragePath(storagePath);
        item.setDeleted(0);
        return item;
    }

    private PaperAttachment paper(Long id, String filename, String storagePath) {
        PaperAttachment item = new PaperAttachment();
        item.setId(id);
        item.setOriginalFilename(filename);
        item.setStoragePath(storagePath);
        item.setDeleted(0);
        return item;
    }
}
