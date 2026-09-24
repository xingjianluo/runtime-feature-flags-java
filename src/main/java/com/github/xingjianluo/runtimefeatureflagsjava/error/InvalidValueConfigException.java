package com.github.xingjianluo.runtimefeatureflagsjava.error;

/** 配置取值类型正确但不满足取值约束时抛出。 */
public class InvalidValueConfigException extends ConfigException {
    public InvalidValueConfigException(String message) {
        super(message);
    }
}
