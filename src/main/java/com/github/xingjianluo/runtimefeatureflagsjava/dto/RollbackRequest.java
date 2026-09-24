package com.github.xingjianluo.runtimefeatureflagsjava.dto;

/** 回滚请求：恢复到指定版本，本身也生成一个新版本。 */
public record RollbackRequest(long targetVersion, String publishedBy) {
}
