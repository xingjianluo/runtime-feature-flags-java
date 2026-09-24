package com.github.xingjianluo.runtimefeatureflagsjava.dto;

import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;

import java.util.Map;

/** 整批发布请求：configs 与 flags 在同一批次内原子生效。 */
public record PublishRequest(
        Map<String, String> configs,
        Map<String, FlagRule> flags,
        String publishedBy,
        String note
) {
}
