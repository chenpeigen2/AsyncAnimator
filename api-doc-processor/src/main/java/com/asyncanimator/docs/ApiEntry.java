package com.asyncanimator.docs;

/** 已解析的文档快照，不在 KSP 轮次之间保存可能失效的符号对象。 */
record ApiEntry(String name, String signature, String documentation, String source, int line) {
    /** 使用完整签名区分同名重载，并使文档不依赖源文件的遍历顺序。 */
    String key() {
        return name + "\n" + signature;
    }
}
