package com.qianyu.atlas.document;

public record ConvertedDocument(
        String text,
        String documentId,
        String normalizedDocumentRef,
        String converter
) {
}
