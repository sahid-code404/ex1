package com.sahidcode404.camera.core.discovery

import com.sahidcode404.camera.core.model.CameraRoute
import com.sahidcode404.camera.core.model.CanonicalLens
import kotlin.math.abs

/**
 * Reconciles the lens that is already visibly previewing with the richer post-first-frame topology.
 * It never opens or closes a camera; callers can replace only their UI/canonical identity while the
 * current session keeps streaming.
 */
object TopologyReconciler {
    fun reconcileVisibleLens(
        currentLens: CanonicalLens?,
        openedRoute: CameraRoute?,
        observedFocalLengthMm: Float?,
        fullTopology: List<CanonicalLens>,
    ): CanonicalLens? {
        if (fullTopology.isEmpty()) return null

        currentLens?.let { current ->
            fullTopology.firstOrNull { it.id == current.id }?.let { return it }
        }

        val route = openedRoute
        if (route != null) {
            val exactRouteCandidates = fullTopology.filter { lens ->
                lens.routes.any { it.routeKey == route.routeKey }
            }
            chooseByObservedFocal(exactRouteCandidates, observedFocalLengthMm)?.let { return it }

            fullTopology.firstOrNull { lens ->
                lens.routes.any { it.opticalFingerprint == route.opticalFingerprint }
            }?.let { return it }
        }

        currentLens?.let { current ->
            val sameFacing = fullTopology.filter { it.facing == current.facing }
            chooseByObservedFocal(sameFacing, observedFocalLengthMm)?.let { return it }
        }

        return chooseByObservedFocal(fullTopology, observedFocalLengthMm) ?: fullTopology.first()
    }

    private fun chooseByObservedFocal(
        candidates: List<CanonicalLens>,
        observedFocalLengthMm: Float?,
    ): CanonicalLens? {
        if (candidates.isEmpty()) return null
        val focal = observedFocalLengthMm?.takeIf { it.isFinite() && it > 0f }
            ?: return candidates.minByOrNull { it.id }

        return candidates.minWithOrNull(
            compareBy<CanonicalLens>(
                { lens -> focalDistance(lens, focal) },
                { lens -> lens.id },
            ),
        )
    }

    private fun focalDistance(lens: CanonicalLens, focal: Float): Float {
        val physicalPreferred = lens.preferredRoute.focalLengthsMm
        val all = if (physicalPreferred.isNotEmpty()) {
            physicalPreferred
        } else {
            lens.routes.flatMap { it.focalLengthsMm }
        }
        return all.minOfOrNull { abs(it - focal) } ?: Float.MAX_VALUE
    }
}
