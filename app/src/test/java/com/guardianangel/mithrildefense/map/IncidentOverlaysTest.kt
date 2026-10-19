package com.guardianangel.mithrildefense.map

import com.guardianangel.mithrildefense.data.model.BuildingDto
import com.guardianangel.mithrildefense.data.model.ColorPathwayChunkDto
import com.guardianangel.mithrildefense.data.model.ColorPathwayDto
import com.guardianangel.mithrildefense.data.model.ColorPathwayPointDto
import com.guardianangel.mithrildefense.data.model.FloorDto
import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.data.model.ThreatDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentOverlaysTest {

    private val campusId = "campus-1"
    private val config = MapConfigResponseDto(
        campusId = campusId,
        buildings = listOf(
            BuildingDto(
                id = "bld-a",
                name = "Main Hall",
                floors = listOf(
                    FloorDto(floorId = "f-a-0", floorLabel = "Ground Floor", level = 0),
                    FloorDto(floorId = "f-a-1", floorLabel = "Floor 1", level = 1),
                    FloorDto(floorId = "f-a-2", floorLabel = "Floor 2", level = 2),
                ),
            ),
            BuildingDto(
                id = "bld-b",
                name = "Gym",
                floors = listOf(FloorDto(floorId = "f-b-0", floorLabel = "Ground", level = 0)),
            ),
        ),
    )

    @Test
    fun threatWithCampusIdIsOutdoor() {
        val threat = ThreatDto(threatId = "t1", building = listOf(campusId), floors = listOf("Floor 2"))
            .toMapThreat(config, campusId)
        assertEquals(OUTDOOR_BUILDING_ID, threat.buildingId)
        assertEquals(OUTDOOR_FLOOR_ID, threat.floorId)
    }

    @Test
    fun threatFloorNameResolvesToFloorId() {
        val threat = ThreatDto(threatId = "t1", building = listOf("bld-a"), floors = listOf("floor_2"))
            .toMapThreat(config, campusId)
        assertEquals("bld-a", threat.buildingId)
        assertEquals("f-a-2", threat.floorId)
    }

    @Test
    fun threatBuildingNameAndGroundResolve() {
        val threat = ThreatDto(threatId = "t1", building = listOf("Main Hall"), floors = listOf("Ground"))
            .toMapThreat(config, campusId)
        assertEquals("bld-a", threat.buildingId)
        assertEquals("f-a-0", threat.floorId)
    }

    @Test
    fun threatVisibilityFollowsFloor() {
        val threat = ThreatDto(threatId = "t1", building = listOf("bld-a"), floors = listOf("f-a-1"))
            .toMapThreat(config, campusId)
        assertTrue(threat.isVisibleOnLayer(config, OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID))
        assertTrue(threat.isVisibleOnLayer(config, "bld-a", "f-a-1"))
        assertFalse(threat.isVisibleOnLayer(config, "bld-a", "f-a-2"))
    }

    @Test
    fun pathwayResolvesChunkFloorsAndColor() {
        val pathway = ColorPathwayDto(
            splineId = "s1",
            splineColor = 1,
            splineChunks = listOf(
                ColorPathwayChunkDto(endpointIndex = 1, floorIds = listOf("f-b-0"), buildingIds = listOf("bld-b")),
            ),
            splinePoints = listOf(ColorPathwayPointDto(0.0, 0.0), ColorPathwayPointDto(100.0, 50.0)),
        ).toColorPathway(config, campusId)!!
        assertEquals(listOf("f-b-0"), pathway.floorIds)
        assertEquals(0xB3FFE001.toInt(), pathway.colorArgb)
        assertEquals(DEFAULT_PATHWAY_THICKNESS, pathway.thickness)
        assertTrue(pathway.isVisibleOnLayer())

        val render = pathway.toRender(FloorPlanTransform(-100f, 0f, 0.5f, 1000f, 1000f))
        assertEquals(listOf(50f, 0f, 100f, 25f), render.points.toList())
        assertEquals(10f, render.strokeWorldPx)
    }

    @Test
    fun pathwayWithoutIdIsDroppedAndUnknownColorIsRed() {
        assertNull(ColorPathwayDto(splineId = "").toColorPathway(config, campusId))
        assertEquals(0xB3FF0002.toInt(), pathwayColorArgb(7))
    }
}
