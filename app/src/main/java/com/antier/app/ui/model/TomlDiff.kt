package com.antier.app.ui.model

/** TOML 文本的行级差异，用于展示“编辑 TOML”保存后新增/删除/修改的行。 */
data class TomlDiff(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val changed: List<Pair<String, String>> = emptyList()
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/** 基于最长公共子序列的行级 diff（配置文件通常很小，O(n*m) 足够）。 */
fun diffToml(before: String, after: String): TomlDiff {
    val a = before.lines()
    val b = after.lines()
    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }

    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()
    val changed = mutableListOf<Pair<String, String>>()
    var pendingRemoved = mutableListOf<String>()
    var pendingAdded = mutableListOf<String>()

    fun flushPending() {
        while (pendingRemoved.isNotEmpty() && pendingAdded.isNotEmpty()) {
            changed.add(pendingRemoved.removeAt(0) to pendingAdded.removeAt(0))
        }
        removed.addAll(pendingRemoved)
        added.addAll(pendingAdded)
        pendingRemoved = mutableListOf()
        pendingAdded = mutableListOf()
    }

    var i = 0
    var j = 0
    while (i < n || j < m) {
        when {
            i < n && j < m && a[i] == b[j] -> {
                flushPending()
                i++
                j++
            }
            j < m && (i == n || dp[i][j + 1] >= dp[i + 1][j]) -> {
                pendingAdded.add(b[j])
                j++
            }
            else -> {
                pendingRemoved.add(a[i])
                i++
            }
        }
    }
    flushPending()
    return TomlDiff(added, removed, changed)
}
