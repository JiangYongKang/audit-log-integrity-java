package com.github.highcumontoa.auditlogintegrityjava.model;

/** 哈希链中的一条条目：可以是数据记录或归档锚点。 */
public interface ChainEntry {

    long sequence();

    String prevHash();

    String entryHash();

    boolean isAnchor();
}
