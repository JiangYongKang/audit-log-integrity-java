package com.github.highcumontoa.auditlogintegrityjava.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启定时任务支持（保留调度器默认通过配置开关处于关闭状态）。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
