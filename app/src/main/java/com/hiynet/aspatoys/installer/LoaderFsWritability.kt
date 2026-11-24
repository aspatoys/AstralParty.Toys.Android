package com.hiynet.aspatoys.installer

/**
 * 加载器目录权限不足时的**统一说明文案**。
 *
 * 如果加载器目录是**游戏进程**先建出来的，权限会是 `drwxr-s---`（2750）：`ext_data_rw` 组只有读和执行位，
 * 于是 `rm -rf` 会 `Permission denied`，`chmod` 也会 `Operation not permitted`（shell 不是 owner）。
 * 表现就是安装写不进去、卸载删不掉，而且**重试永远不会成功**。
 *
 * 这里刻意**不做写探针**：探针本身会往加载器目录里写文件（安装/卸载之外的副作用），
 * 也会让所有用测试替身的用例在预检处直接失败。判断留给真正的操作，失败时用这段文案说清原因与出路。
 */
internal object LoaderFsWritability {

    fun explain(root: String): String =
        "加载器目录由游戏进程创建（$root），Shizuku 身份没有写权限：既删不掉里面的文件，" +
            "也改不了目录权限，所以安装 / 卸载都会失败且重试无效。" +
            "请先在系统设置里清除该游戏的数据（或先在游戏内启动一次，让加载器自己整理目录），然后再操作。"
}
