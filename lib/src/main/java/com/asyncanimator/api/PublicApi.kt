package com.asyncanimator.api

/**
 * 显式标记供库使用方调用的对外 API，并将该声明及其中文 KDoc 纳入编译期文档。
 * 仅标记的声明会进入文档；标记类型不会自动包含成员、继承成员或编译器生成方法。
 * 声明及所有外层类型必须公开可访问，注解不改变 Kotlin 可见性，也不自动添加混淆保留规则。
 * 注解保留在二进制中但不支持运行时反射查询；函数重载、属性、枚举项和显式构造器需分别标记。
 * 本库启用公开源码声明完整性校验，新增公开声明漏标时编译失败；不自动展开继承或合成成员。
 */
@PublicApi
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.FIELD
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class PublicApi
