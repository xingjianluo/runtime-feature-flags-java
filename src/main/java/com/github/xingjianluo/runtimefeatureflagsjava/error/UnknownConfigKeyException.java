package com.github.xingjianluo.runtimefeatureflagsjava.error;

/** 发布批次中出现注册表未定义的配置键或开关键时抛出。 */
public class UnknownConfigKeyException extends ConfigException {
    public UnknownConfigKeyException(String message) {
        super(message);
    }
}
