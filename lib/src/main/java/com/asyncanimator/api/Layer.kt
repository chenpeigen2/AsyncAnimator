package com.asyncanimator.api

/**
 * 标记顶层类型所属的技术分层，使库的层次结构显式可见并被编译期强制。
 * 规则由 KSP 校验执行：lib 内每个顶层类、对象、接口或枚举声明都必须标注本注解（api 包豁免），
 * 且 import 或全限定引用不得指向更高层的包；同层互引与向下引用允许。
 * 注解仅用于类目标，编译期可见，不保留进二进制，可在生成文档中阅读到层级归属。
 */
@PublicApi
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class Layer @PublicApi constructor(
    /**
     * 该类型所属的分层，取自 [LayerId]，编号越小层越低。
     */
    @property:PublicApi
    val value: LayerId
)
