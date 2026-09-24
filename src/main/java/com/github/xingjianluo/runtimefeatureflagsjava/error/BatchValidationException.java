package com.github.xingjianluo.runtimefeatureflagsjava.error;

import java.util.List;

/** 整批发布校验失败的聚合异常，包含每个条目的独立错误，绝不允许部分生效。 */
public class BatchValidationException extends ConfigException {
    private final transient List<ConfigException> errors;

    public BatchValidationException(List<ConfigException> errors) {
        super("批次校验失败，共 " + errors.size() + " 处错误");
        this.errors = List.copyOf(errors);
    }

    public List<ConfigException> getErrors() {
        return errors;
    }
}
