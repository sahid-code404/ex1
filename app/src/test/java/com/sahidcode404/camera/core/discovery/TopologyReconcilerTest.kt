package com.sahidcode404.camera.core.discovery

import com.sahidcode404.camera.core.model.CameraRoute
import com.sahidcode404.camera.core.model.CanonicalLens
import com.sahidcode404.camera.core.model.LensFacing
import com.sahidcode404.camera.core.model.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TopologyReconcilerTest {
    @Test
    fun `logical startup route maps to physical lens nearest observed focal without reopening`() {
        val logical = route(openId = "logical", physicalId = null, focal = listOf(1.8f, 4.5f), optical = "logical")
        val widePhysical = route(openId = "logical", physicalId = "wide", focal = listOf(1.8f), optical = "wide")
        val telePhysical = route(openId = "logical", physicalId = "tele", focal = listOf(4.5f), optical = "tele")

        val startup = CanonicalLens("startup", "Back", LensFacing.BACK, listOf(logical))
        val wide = CanonicalLens("wide", "24mm", LensFacing.BACK, listOf(widePhysical, logical))
        val tele = CanonicalLens("tele", "70mm", LensFacing.BACK, listOf(telePhysical, logical))

        val resolved = TopologyReconciler.reconcileVisibleLens(
            currentLens = startup,
            openedRoute = logical,
            observedFocalLengthMm = 4.4f,
            fullTopology = listOf(wide, tele),
        )

        assertEquals("tele", resolved?.id)
    }

    @Test
    fun `existing canonical identity wins when full topology already contains it`() {
        val physical = route(openId = "logical", physicalId = "wide", focal = listOf(1.8f), optical = "wide")
        val current = CanonicalLens("wide", "24mm", LensFacing.BACK, listOf(physical))
        val other = CanonicalLens(
            "other",
            "70mm",
            LensFacing.BACK,
            listOf(route("logical", "tele", listOf(4.5f), "tele")),
        )

        val resolved = TopologyReconciler.reconcileVisibleLens(
            currentLens = current,
            openedRoute = physical,
            observedFocalLengthMm = 4.5f,
            fullTopology = listOf(other, current),
        )

        assertEquals("wide", resolved?.id)
    }

    private fun route(
        openId: String,
        physicalId: String?,
        focal: List<Float>,
        optical: String,
    ) = CameraRoute(
        openId = openId,
        physicalId = physicalId,
        kind = if (physicalId == null) RouteKind.PUBLIC else RouteKind.LOGICAL_PHYSICAL,
        facing = LensFacing.BACK,
        focalLengthsMm = focal,
        sensorPhysicalWidthMm = null,
        sensorPhysicalHeightMm = null,
        sensorOrientation = 90,
        previewSizes = emptyList(),
        rawSizes = emptyList(),
        supportsManualSensor = false,
        cfa = null,
        blackLevels = intArrayOf(0, 0, 0, 0),
        whiteLevel = null,
        opticalFingerprint = optical,
    )
}
