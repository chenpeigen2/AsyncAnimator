package com.asyncanimator.api

/**
 * 显式标记供库使用方调用的对外 API，并将该声明及其中文 KDoc 纳入编译期文档。
 * 仅标记的声明会进入文档；标记类型不会自动包含成员、继承成员或编译器生成方法。
 * 声明及所有外层类型必须公开可访问，注解不改变 Kotlin 可见性，也不自动添加混淆保留规则。
 * 注解保留在二进制中但不支持运行时反射查询；函数重载、属性和构造器需分别标记。
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class PublicApi
