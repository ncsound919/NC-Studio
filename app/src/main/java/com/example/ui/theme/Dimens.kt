package com.example.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Studio spacing scale. Every gap, inset, and section offset should come from
 * this 4dp scale instead of ad-hoc literals.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Documented corner radii. */
object Radius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val pill = 100.dp
}

/** Documented icon sizes. */
object IconSize {
    val xs = 12.dp
    val sm = 16.dp
    val md = 20.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** Minimum interactive target for custom studio controls. */
object TouchTarget {
    val min = 48.dp
}
