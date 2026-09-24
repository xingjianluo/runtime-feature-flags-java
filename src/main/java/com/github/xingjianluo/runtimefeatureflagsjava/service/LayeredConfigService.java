package com.github.xingjianluo.runtimefeatureflagsjava.service;

import com.github.xingjianluo.runtimefeatureflagsjava.error.MissingConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.UnknownConfigKeyException;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigDefinition;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 分层配置合并服务。
 *
 * <p>优先级从低到高（数字越大优先级越高，高优先级非空值整体覆盖低优先级）：
 * <ol>
 *   <li>层 0 默认值：注册表 {@link ConfigDefinition#defaultValue()}</li>
 *   <li>层 1 启动配置：{@link StartupConfigSource}（application.properties / 环境变量 / JVM 参数）</li>
 *   <li>层 2 运行时覆盖：已发布版本中的配置</li>
 * </ol>
 *
 * <p>生效时机：默认值与启动配置在进程启动时确定且只读；运行时覆盖直接取自
 * 版本存储的当前快照（与开关判定读取同一快照），因此发布一旦提交，配置与开关
 * 在同一瞬间对后续所有读取原子生效，不存在两个组件先后切换的中间窗口；
 * 发布失败则旧快照保持不变。
 *
 * <p>错误处理（互不混淆，绝不静默采用）：键未注册抛
 * {@link UnknownConfigKeyException}；三层均无值抛
 * {@link com.github.xingjianluo.runtimefeatureflagsjava.error.MissingConfigException}；
 * 类型不符抛 {@link com.github.xingjianluo.runtimefeatureflagsjava.error.TypeMismatchConfigException}；
 * 取值越界抛 {@link com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException}。
 */
public class LayeredConfigService {
    /** 配置来源层，数值越大优先级越高。 */
    public enum Layer {
        DEFAULT(0, "默认值"),
        STARTUP(1, "启动配置"),
        RUNTIME(2, "运行时覆盖");

        private final int precedence;
        private final String displayName;

        Layer(int precedence, String displayName) {
            this.precedence = precedence;
            this.displayName = displayName;
        }

        public int precedence() {
            return precedence;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 一次解析结果：原始取值与命中的层，便于解释「这个值从哪来」。 */
    public record ResolvedConfig(String key, String rawValue, Layer layer) {
    }

    private final ConfigRegistry registry;
    private final Map<String, String> startup;
    /**
     * 运行时覆盖层事实源：返回当前已发布版本的配置快照（不可变）。
     * 与开关层共用同一版本快照引用，保证配置与开关原子可见。
     */
    private final Supplier<Map<String, String>> runtimeSource;

    public LayeredConfigService(ConfigRegistry registry, StartupConfigSource startupSource) {
        this(registry, startupSource, Map::of);
    }

    public LayeredConfigService(ConfigRegistry registry, StartupConfigSource startupSource,
                                Supplier<Map<String, String>> runtimeSource) {
        this.registry = registry;
        this.runtimeSource = runtimeSource;
        Map<String, String> boot = new HashMap<>(startupSource.values());
        // 启动期快速失败：未知键 / 类型不符 / 越界的启动配置直接阻止上下文就绪
        for (Map.Entry<String, String> entry : boot.entrySet()) {
            ConfigDefinition definition = registry.find(entry.getKey())
                    .orElseThrow(() -> new UnknownConfigKeyException(
                            "启动配置中存在未注册的配置键: " + entry.getKey()));
            definition.validateRaw(entry.getValue());
        }
        this.startup = Map.copyOf(boot);
    }

    /** 解析配置：返回原始值与命中层，并完成类型/范围校验。 */
    public ResolvedConfig resolve(String key) {
        return resolve(key, runtimeSource.get());
    }

    /**
     * 基于指定的运行时快照解析配置。一次读取若需要同时访问多个键（或配置+开关），
     * 可先取一个版本快照再调用本方法，保证同一观测内绝不跨版本。
     */
    public ResolvedConfig resolve(String key, Map<String, String> runtimeSnapshot) {
        ConfigDefinition definition = registry.find(key)
                .orElseThrow(() -> new UnknownConfigKeyException("未注册的配置键: " + key));
        String runtimeValue = runtimeSnapshot.get(key);
        if (runtimeValue != null) {
            definition.validateRaw(runtimeValue);
            return new ResolvedConfig(key, runtimeValue, Layer.RUNTIME);
        }
        String startupValue = startup.get(key);
        if (startupValue != null) {
            definition.validateRaw(startupValue);
            return new ResolvedConfig(key, startupValue, Layer.STARTUP);
        }
        if (definition.defaultValue() != null) {
            definition.validateRaw(definition.defaultValue());
            return new ResolvedConfig(key, definition.defaultValue(), Layer.DEFAULT);
        }
        throw new MissingConfigException(
                "配置 " + key + " 在默认值、启动配置、运行时覆盖三层中均缺失");
    }

    /** 取配置的字符串形态（读取即按声明类型与范围触发校验）。 */
    public String getString(String key) {
        return resolve(key).rawValue();
    }
}
