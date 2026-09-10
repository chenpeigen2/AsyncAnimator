package com.asyncanimator.docs;

import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;

/** KSP 的服务加载入口，处理器仅在构建期运行，不打包进库的运行时依赖。 */
public final class PublicApiProcessorProvider implements SymbolProcessorProvider {
    /** 为本次编译创建独立处理器，避免跨变体共享声明或输出状态。 */
    @Override
    public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return new PublicApiProcessor(environment);
    }
}
