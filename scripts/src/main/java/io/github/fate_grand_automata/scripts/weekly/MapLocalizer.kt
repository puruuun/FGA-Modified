package io.github.fate_grand_automata.scripts.weekly

import io.github.lib_automata.ImageFeatureMatch
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import kotlin.math.*
import kotlin.random.Random

/** Axis-aligned map pixels -> 720p game area, independently fitted x/y scales. */
data class MapFit(val score: Double, val scaleX: Double, val scaleY: Double, val originX: Double, val originY: Double,
    val inliers: Int, val rms: Double, val spanX: Double, val spanY: Double) {
    fun screenX(mapX: Double) = originX + scaleX * mapX
    fun screenY(mapY: Double) = originY + scaleY * mapY
    fun mapX(screenX: Double) = (screenX-originX)/scaleX
    fun mapY(screenY: Double) = (screenY-originY)/scaleY
}

/**
 * SIFT correspondences between the Atlas background and the visible screen, then deterministic RANSAC and least squares.
 * Icons/roads/labels are occluders, never anchors. Fits with too little evidence, repeated motifs, large residuals, negative
 * scales, or insufficient 2-D support fail closed. No map coordinate can be tapped on the strength of a low NCC alone.
 */
class MapLocalizer {
    companion object {
        const val MIN_INLIERS = 18
        const val MIN_RATIO = 0.55
        const val MAX_RMS = 2.0
        const val INLIER_DISTANCE = 3.0
        fun accepts(f: MapFit?): Boolean = f != null && listOf(f.score,f.scaleX,f.scaleY,f.originX,f.originY,f.rms).all { it.isFinite() } &&
            f.score >= MIN_RATIO && f.inliers >= MIN_INLIERS && f.rms <= MAX_RMS && f.spanX >= 160 && f.spanY >= 100 &&
            f.scaleX in 0.35..3.5 && f.scaleY in 0.35..3.5 && abs(f.scaleX/f.scaleY-1) <= 0.15

        /** Public pure math, exercised with known transformations and adversarial/degenerate correspondences. */
        fun fit(points: List<ImageFeatureMatch>, range: ClosedFloatingPointRange<Double> = 0.35..3.5): MapFit? {
            val p = points.filter { listOf(it.mapX,it.mapY,it.screenX,it.screenY).all(Double::isFinite) }
            if (p.size < MIN_INLIERS) return null
            data class Axis(val scale: Double, val offset: Double)
            fun axis(q: List<ImageFeatureMatch>, x: Boolean): Axis? {
                val a = q.map { if(x) it.mapX else it.mapY }; val b = q.map { if(x) it.screenX else it.screenY }
                val am=a.average(); val bm=b.average(); val variance=a.sumOf { (it-am).pow(2) }
                if(variance < 1e-8) return null
                val scale=a.indices.sumOf { (a[it]-am)*(b[it]-bm) }/variance
                return Axis(scale,bm-scale*am)
            }
            fun compatible(x: Axis,y: Axis) = x.scale in range && y.scale in range && abs(x.scale/y.scale-1) <= 0.15
            fun residual(e: ImageFeatureMatch,x: Axis,y: Axis) = hypot(e.screenX-x.offset-x.scale*e.mapX,e.screenY-y.offset-y.scale*e.mapY)
            val random=Random(9009)
            var best=emptyList<ImageFeatureMatch>()
            repeat(1000) {
                val a=p[random.nextInt(p.size)];val b=p[random.nextInt(p.size)]
                if(abs(a.mapX-b.mapX)<30 || abs(a.mapY-b.mapY)<30) return@repeat
                val pair=listOf(a,b);val x=axis(pair,true) ?: return@repeat; val y=axis(pair,false) ?: return@repeat
                if(!compatible(x,y)) return@repeat
                val inliers=p.filter { residual(it,x,y)<=INLIER_DISTANCE }
                if(inliers.size>best.size) best=inliers
            }
            if(best.size<MIN_INLIERS) return null
            var x=axis(best,true) ?: return null;var y=axis(best,false) ?: return null
            repeat(2) {
                best=p.filter { residual(it,x,y)<=INLIER_DISTANCE }
                if(best.size<MIN_INLIERS) return null
                x=axis(best,true) ?: return null;y=axis(best,false) ?: return null
            }
            if(!compatible(x,y)) return null
            val f=MapFit(best.size.toDouble()/p.size,x.scale,y.scale,x.offset,y.offset,best.size,
                sqrt(best.sumOf { residual(it,x,y).pow(2) }/best.size),
                best.maxOf { it.screenX }-best.minOf { it.screenX },best.maxOf { it.screenY }-best.minOf { it.screenY })
            return f.takeIf(::accepts)
        }
    }
    fun templateRegion(width: Int,height: Int) = Region(width*185/1280,height*110/720,width*1005/1280,height*480/720)
    fun localize(screen: Pattern,map: Pattern,scaleRange: ClosedFloatingPointRange<Double> = 0.35..3.5): MapFit? =
        fit(map.featureMatches(screen,templateRegion(screen.width,screen.height)),scaleRange)
}
