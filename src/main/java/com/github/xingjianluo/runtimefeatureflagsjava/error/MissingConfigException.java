package com.github.xingjianluo.runtimefeatureflagsjava.error;

/** 配置在默认值、启动配置、运行时覆盖三层中均缺失时抛出。 */
public class MissingConfigException extends ConfigException {
    public MissingConfigException(String message) {
        super(message);
    }
}
