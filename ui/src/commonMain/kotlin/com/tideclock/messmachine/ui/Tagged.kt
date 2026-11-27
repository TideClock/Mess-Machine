package com.tideclock.messmachine.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.testTag

/** Where every tagged control sits on screen right now, so a lesson can ring the one it means. */
object TutHi {
    val rects = HashMap<String, Rect>()
    /** Which node last placed each tag: a node going away only clears its own record, never a newer copy's. */
    val owners = HashMap<String, Any>()
    fun forget(t: String, who: Any) { if (owners[t] === who) { owners.remove(t); rects.remove(t) } }
}

private class TutNode(var t: String) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) { TutHi.owners[t] = this; TutHi.rects[t] = coordinates.boundsInRoot() }
    override fun onDetach() { TutHi.forget(t, this) }
}

private data class TutElement(val t: String) : ModifierNodeElement<TutNode>() {
    override fun create() = TutNode(t)
    override fun update(node: TutNode) { if (node.t != t) { TutHi.forget(node.t, node); node.t = t } }
}

/** A test tag that lessons can also find on screen. Every control in the app is tagged with this. */
fun Modifier.tagged(t: String): Modifier = this.testTag(t).then(TutElement(t))
