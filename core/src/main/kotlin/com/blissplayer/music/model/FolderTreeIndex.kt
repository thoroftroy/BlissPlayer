package com.blissplayer.music.model

/** Index folder parents and song counts without comparing every path with every other path. */
object FolderTreeIndex {
    data class Index(val children: Map<String?, List<String>>, val songCounts: Map<String, Int>)

    fun build(paths: Set<String>, songPaths: Iterable<String>): Index {
        val children = mutableMapOf<String?, MutableList<String>>()
        for (path in paths.sorted()) {
            var slash = path.lastIndexOf('/')
            var parent: String? = null
            while (slash >= 0) {
                val candidate = path.substring(0, slash)
                if (candidate in paths) {
                    parent = candidate
                    break
                }
                slash = path.lastIndexOf('/', slash - 1)
            }
            children.getOrPut(parent) { mutableListOf() }.add(path)
        }
        val counts = mutableMapOf<String, Int>()
        for (file in songPaths) {
            var path = file
            while (true) {
                if (path in paths) counts[path] = (counts[path] ?: 0) + 1
                val slash = path.lastIndexOf('/')
                if (slash < 0) break
                path = path.substring(0, slash)
            }
        }
        return Index(children, counts)
    }
}
