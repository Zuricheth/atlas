package com.qianyu.atlas.paper;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.paper-storage")
public class PaperStorageProperties {
    private String root = "data/papers";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}