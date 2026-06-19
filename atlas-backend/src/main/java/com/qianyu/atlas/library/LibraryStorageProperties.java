package com.qianyu.atlas.library;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.library-storage")
public class LibraryStorageProperties {
    private String root = "data/library";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
