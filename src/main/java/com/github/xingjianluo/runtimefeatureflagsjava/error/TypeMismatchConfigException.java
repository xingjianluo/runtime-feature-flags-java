package com.github.xingjianluo.runtimefeatureflagsjava.error;

/** 配置取值无法解析为声明类型时抛出。 */
public class TypeMismatchConfigException extends ConfigException {
    public TypeMismatchConfigException(String message) {
        super(message);
    }

    public TypeMismatchConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
