package com.github.xingjianluo.runtimefeatureflagsjava.error;

/** 配置相关错误的基类，错误类型可通过子类区分。 */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
