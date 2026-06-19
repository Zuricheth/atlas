package com.qianyu.atlas.ai;

public record ActiveModelInfo(
        AiProvider provider,
        AiModel model
) {
    public String displayName() {
        if (model == null) return "(none)";
        String alias = model.getAlias();
        return (alias != null && !alias.isBlank()) ? alias : model.getName();
    }
}