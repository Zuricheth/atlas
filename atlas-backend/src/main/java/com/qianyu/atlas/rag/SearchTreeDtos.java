package com.qianyu.atlas.rag;

import java.util.List;

public class SearchTreeDtos {
    public record SearchTreeResponse(
            String query,
            List<SearchTreeNode> roots,
            List<String> expandedKeys
    ) {
    }

    public record SearchTreeNode(
            String key,
            String name,
            String type,
            String reason,
            Long noteId,
            Long itemId,
            String fileUrl,
            List<SearchTreeNode> children
    ) {
    }
}
