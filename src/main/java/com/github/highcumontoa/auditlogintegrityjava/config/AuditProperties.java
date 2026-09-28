package com.github.highcumontoa.auditlogintegrityjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地审计流水的配置项。
 */
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    private final Storage storage = new Storage();
    private final Segment segment = new Segment();
    private final Retention retention = new Retention();

    public Storage getStorage() { return storage; }
    public Segment getSegment() { return segment; }
    public Retention getRetention() { return retention; }

    public static class Storage {
        /** 审计流水根目录（segment 文件、归档、检查点都在其下） */
        private String dir = System.getProperty("java.io.tmpdir") + "/audit-log-store";
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    public static class Segment {
        /** 单个段最多容纳的记录数，达到后滚动出新段 */
        private int maxRecords = 1000;
        public int getMaxRecords() { return maxRecords; }
        public void setMaxRecords(int maxRecords) { this.maxRecords = maxRecords; }
    }

    public static class Retention {
        /** 保留期天数；maxEventTime 早于 now - retention 的整段可被清理/归档 */
        private int days = 90;
        /** ARCHIVE：移入 archive/ 目录仍可查可校验；DELETE：删除数据只留检查点边界 */
        private String mode = "ARCHIVE";
        private boolean sweepEnabled = true;
        private long sweepFixedDelayMs = 3_600_000L;
        public int getDays() { return days; }
        public void setDays(int days) { this.days = days; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public boolean isSweepEnabled() { return sweepEnabled; }
        public void setSweepEnabled(boolean sweepEnabled) { this.sweepEnabled = sweepEnabled; }
        public long getSweepFixedDelayMs() { return sweepFixedDelayMs; }
        public void setSweepFixedDelayMs(long d) { this.sweepFixedDelayMs = d; }
    }
}
