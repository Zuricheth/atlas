package com.qianyu.atlas.paper;

public class PaperDtos {
    public record ImportPaperResponse(
            Long attachmentId,
            Long noteId,
            String title,
            String originalFilename,
            String fileUrl,
            java.util.List<com.qianyu.atlas.ai.AiTracer.AiCall> aiTrace
    ) {
        public ImportPaperResponse(Long attachmentId, Long noteId, String title,
                                    String originalFilename, String fileUrl) {
            this(attachmentId, noteId, title, originalFilename, fileUrl, java.util.Collections.emptyList());
        }
    }
}