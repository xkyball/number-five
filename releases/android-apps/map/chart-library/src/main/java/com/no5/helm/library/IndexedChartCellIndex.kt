package com.no5.helm.library

/** Immutable inclusive bbox tree. Unknown extents stay in every query.
 * It changes candidate lookup cost, never coverage or chart priority. */
class IndexedChartCellIndex(private val catalog: ChartPlanningCatalog) : ChartCellCandidateIndex {
    override val generation = catalog.generation
    private data class Box(val index: Int, val bounds: ChartSelectionExtent)
    private class Node(val bounds: ChartSelectionExtent, val leaf: List<Box>?, val left: Node?, val right: Node?)
    private val unknown = catalog.cells.indices.filter { catalog.cells[it].bounds == null }
    private val root = build(catalog.cells.flatMapIndexed { i, c -> c.bounds?.let { split(it).map { b -> Box(i, b) } }.orEmpty() })
    override fun query(bounds: ChartSelectionExtent): List<Int> {
        require(bounds.valid)
        val found = mutableSetOf<Int>(); found.addAll(unknown)
        fun visit(node: Node?, box: ChartSelectionExtent) {
            if (node == null || !overlap(node.bounds, box)) return
            if (node.leaf != null) node.leaf.forEach { if (overlap(it.bounds, box)) found.add(it.index) }
            else { visit(node.left, box); visit(node.right, box) }
        }
        split(bounds).forEach { visit(root, it) }
        return found.sorted()
    }
    private fun build(boxes: List<Box>): Node? {
        if (boxes.isEmpty()) return null
        val b = ChartSelectionExtent(boxes.minOf { it.bounds.south }, boxes.maxOf { it.bounds.north },
            boxes.minOf { it.bounds.west }, boxes.maxOf { it.bounds.east })
        if (boxes.size <= 8) return Node(b, boxes, null, null)
        val longitude = b.east - b.west >= b.north - b.south
        val sorted = boxes.sortedWith(compareBy<Box> { if (longitude) it.bounds.west + it.bounds.east else it.bounds.south + it.bounds.north }
            .thenBy { it.index })
        val middle = sorted.size / 2
        return Node(b, null, build(sorted.subList(0, middle)), build(sorted.subList(middle, sorted.size)))
    }
    private fun overlap(a: ChartSelectionExtent, b: ChartSelectionExtent) =
        a.south <= b.north && a.north >= b.south && a.west <= b.east && a.east >= b.west
    private fun split(b: ChartSelectionExtent): List<ChartSelectionExtent> = when {
        b.west > b.east -> listOf(b.copy(east = 180.0), b.copy(west = -180.0))
        b.west == -180.0 && b.east < 180.0 -> listOf(b, b.copy(west = 180.0, east = 180.0))
        b.east == 180.0 && b.west > -180.0 -> listOf(b, b.copy(west = -180.0, east = -180.0))
        else -> listOf(b)
    }
}
