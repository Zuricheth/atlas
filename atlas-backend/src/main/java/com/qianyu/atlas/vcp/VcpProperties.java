package com.qianyu.atlas.vcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atlas.vcp")
public class VcpProperties {
    private String dailyNoteRoot;
    private String defaultTargetNotebook;
    private String deepwikiAgentNotebook;
    private String syncAgentNotebook;
    private String publicMemoryDsl;
    private String deepwikiWorkDsl;
    private String syncWorkDsl;
    private boolean userScopedRoot;

    public String getDailyNoteRoot() {
        return dailyNoteRoot;
    }

    public void setDailyNoteRoot(String dailyNoteRoot) {
        this.dailyNoteRoot = dailyNoteRoot;
    }

    public String getDefaultTargetNotebook() {
        return defaultTargetNotebook;
    }

    public void setDefaultTargetNotebook(String defaultTargetNotebook) {
        this.defaultTargetNotebook = defaultTargetNotebook;
    }

    public String getDeepwikiAgentNotebook() {
        return deepwikiAgentNotebook;
    }

    public void setDeepwikiAgentNotebook(String deepwikiAgentNotebook) {
        this.deepwikiAgentNotebook = deepwikiAgentNotebook;
    }

    public String getSyncAgentNotebook() {
        return syncAgentNotebook;
    }

    public void setSyncAgentNotebook(String syncAgentNotebook) {
        this.syncAgentNotebook = syncAgentNotebook;
    }

    public String getPublicMemoryDsl() {
        return publicMemoryDsl;
    }

    public void setPublicMemoryDsl(String publicMemoryDsl) {
        this.publicMemoryDsl = publicMemoryDsl;
    }

    public String getDeepwikiWorkDsl() {
        return deepwikiWorkDsl;
    }

    public void setDeepwikiWorkDsl(String deepwikiWorkDsl) {
        this.deepwikiWorkDsl = deepwikiWorkDsl;
    }

    public String getSyncWorkDsl() {
        return syncWorkDsl;
    }

    public void setSyncWorkDsl(String syncWorkDsl) {
        this.syncWorkDsl = syncWorkDsl;
    }

    public boolean isUserScopedRoot() {
        return userScopedRoot;
    }

    public void setUserScopedRoot(boolean userScopedRoot) {
        this.userScopedRoot = userScopedRoot;
    }
}
