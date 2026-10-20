package io.github.fate_grand_automata.accessibility

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import io.github.lib_automata.PinchPlan

/** API 24 two-pointer gesture; construction is separately verified without dispatching input. */
object PinchGestureBuilder {
    fun build(plan: PinchPlan): GestureDescription {
        require(plan.durationMs in 1..60_000)
        fun stroke(a: io.github.lib_automata.Location,b: io.github.lib_automata.Location) = GestureDescription.StrokeDescription(
            Path().apply { moveTo(a.x.toFloat(),a.y.toFloat());lineTo(b.x.toFloat(),b.y.toFloat()) },0,plan.durationMs)
        return GestureDescription.Builder().addStroke(stroke(plan.startA,plan.endA)).addStroke(stroke(plan.startB,plan.endB)).build()
    }
}
