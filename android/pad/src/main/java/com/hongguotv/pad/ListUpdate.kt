package com.hongguotv.pad

/**
 * 原地更新列表，尽量不动已有条目。
 *
 * 为什么不能直接 `clear()` + `addAll()`：Lazy 列表的 item 节点是按 key 和数量维护的，
 * 一瞬间清空会让所有 item 当场销毁（哪怕下一行又把同样的 key 加回来，也是新节点），
 * 落在那一条上的焦点和滚动位置一起丢。用户看到的就是「过一两秒焦点突然被拉回第一项」。
 *
 * 这里按 [key] 对齐：key 序列完全一致时只替换真正变化的那几项，让 Compose 复用节点；
 * 只有真的增删了条目（次数变了、顺序变了）才退回整表重建。
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
