package com.github.highcumontoa.auditlogintegrityjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地审计模块配置。 */
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    /** 流水本地存储目录。 */
    private String storageDir = "data/audit";

    /** 默认保留时长（毫秒）；<=0 表示不自动清理。 */
    private long retentionMillis = 0L;

    /** 到期处理方式：ARCHIVE 或 DELETE。 */
    private String retentionMode = "ARCHIVE";

    /** 是否开启定时保留任务。 */
    private boolean retentionScheduleEnabled = false;

    /** 定时保留任务间隔（毫秒）。 */
    private long retentionScheduleMillis = 3_600_000L;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }

    public long getRetentionMillis() { return retentionMillis; }
    public void setRetentionMillis(long retentionMillis) { this.retentionMillis = retentionMillis; }

    public String getRetentionMode() { return retentionMode; }
    public void setRetentionMode(String retentionMode) { this.retentionMode = retentionMode; }

    public boolean isRetentionScheduleEnabled() { return retentionScheduleEnabled; }
    public void setRetentionScheduleEnabled(boolean retentionScheduleEnabled) {
        this.retentionScheduleEnabled = retentionScheduleEnabled;
    }

    public long getRetentionScheduleMillis() { return retentionScheduleMillis; }
    public void setRetentionScheduleMillis(long retentionScheduleMillis) {
        this.retentionScheduleMillis = retentionScheduleMillis;
    }
}
