package com.kaixinguo.pc

/**
 * 原地更新列表：key 序列一致时只替换变化项（让 Compose 复用节点，保焦点/滚动）；
 * 数量/顺序变了才整表重建。与安卓 ListUpdate.diffUpdate 同口径。
 */
inline fun <T> MutableList<T>.diffUpdate(next: List<T>, key: (T) -> Any?) {
    if (size == next.size && indices.all { key(this[it]) == key(next[it]) }) {
        for (i in next.indices) {
            if (this[i] != next[i]) this[i] = next[i]
        }
        return
    }
    clear()
    addAll(next)
}
