package com.guardianangel.dronetelementry.map

import com.guardianangel.dronetelementry.data.model.BuildingDto
import com.guardianangel.dronetelementry.data.model.ColorPathwayChunkDto
import com.guardianangel.dronetelementry.data.model.ColorPathwayDto
import com.guardianangel.dronetelementry.data.model.ColorPathwayPointDto
import com.guardianangel.dronetelementry.data.model.FloorDto
import com.guardianangel.dronetelementry.data.model.MapConfigResponseDto
import com.guardianangel.dronetelementry.data.model.ThreatDto
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

    /**
     * Threat_3 regression: "Floor 1" landed on FL 2 because FL 2's 0-based level is 1 and the
     * old single pass matched label/id/level together (and digits inside floor UUIDs).
     */
    @Test
    fun floorNameMatchesLabelBeforeLevelOrUuidDigits() {
        val bid = "ab1a97a0-1e18-46d3-8817-9ce65771c2e9"
        val cfg = MapConfigResponseDto(
            campusId = campusId,
            buildings = listOf(
                BuildingDto(
                    id = bid,
                    name = "Tower",
                    floors = listOf(
                        FloorDto(floorId = "9e1c-fl2", floorLabel = "FL 2", level = 1),
                        FloorDto(floorId = "7a3d-fl1", floorLabel = "FL 1", level = 0),
                    ),
                ),
            ),
        )
        fun resolve(floor: String) = ThreatDto(threatId = "t", building = listOf(bid), floors = listOf(floor))
            .toMapThreat(cfg, campusId).floorId
        assertEquals("7a3d-fl1", resolve("Floor 1"))
        assertEquals("9e1c-fl2", resolve("Floor 2"))
        assertEquals("7a3d-fl1", resolve("1st Floor"))
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
        assertEquals(1, pathway.segmentsForLayer(config, "bld-b", "f-b-0").size)
        assertTrue(pathway.segmentsForLayer(config, "bld-a", "f-a-1").isEmpty())

        val xf = FloorPlanTransform(-100f, 0f, 0.5f, 1000f, 1000f)
        val render = pathway.toRenders(xf, pathway.segmentsForLayer(config, OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID)).single()
        assertEquals(listOf(50f, 0f, 100f, 25f), render.points.toList())
        assertEquals(10f, render.strokeWorldPx)
    }

    @Test
    fun pathwayDrawsOnlyChunksOnSelectedFloor() {
        // 0..2 on floor 1, 2..4 on floor 2, 4..5 back on floor 1.
        val pathway = ColorPathwayDto(
            splineId = "s2",
            splineChunks = listOf(
                ColorPathwayChunkDto(endpointIndex = 2, floorIds = listOf("f-a-1"), buildingIds = listOf("bld-a")),
                ColorPathwayChunkDto(endpointIndex = 4, floorIds = listOf("f-a-2"), buildingIds = listOf("bld-a")),
                ColorPathwayChunkDto(endpointIndex = 5, floorIds = listOf("Floor 1"), buildingIds = listOf("bld-a")),
            ),
            splinePoints = (0..5).map { ColorPathwayPointDto(it.toDouble(), 0.0) },
        ).toColorPathway(config, campusId)!!

        val floor1 = pathway.segmentsForLayer(config, "bld-a", "f-a-1")
        assertEquals(listOf(listOf(0f, 1f, 2f), listOf(4f, 5f)), floor1.map { run -> run.map { it.x } })
        val floor2 = pathway.segmentsForLayer(config, "bld-a", "f-a-2")
        assertEquals(listOf(listOf(2f, 3f, 4f)), floor2.map { run -> run.map { it.x } })
        assertTrue(pathway.segmentsForLayer(config, "bld-a", "f-a-0").isEmpty())
        assertEquals(6, pathway.segmentsForLayer(config, OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID).single().size)
    }

    /**
     * Live payload shape: floor only in top-level `spline_floors`, chunks carry just
     * `chunk_end_point_index` (no floor_ids). Must draw on that floor, not only outdoor.
     */
    @Test
    fun legacyFloorsApplyToChunksWithoutFloorIds() {
        val pathway = ColorPathwayDto(
            splineId = "41C5",
            splineColor = 2,
            splineFloors = listOf("Floor 1"),
            splineBuildings = listOf("bld-a"),
            splineChunks = listOf(
                ColorPathwayChunkDto(chunkIndex = 1, chunkEndPointIndex = 2),
                ColorPathwayChunkDto(chunkIndex = 2, chunkEndPointIndex = 3),
            ),
            splinePoints = (0..3).map { ColorPathwayPointDto(it.toDouble(), 0.0) },
        ).toColorPathway(config, campusId)!!

        assertEquals(listOf("f-a-1"), pathway.floorIds)
        assertEquals(4, pathway.segmentsForLayer(config, "bld-a", "f-a-1").single().size)
        assertTrue(pathway.segmentsForLayer(config, "bld-a", "f-a-2").isEmpty())
        assertEquals(1, pathway.segmentsForLayer(config, OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID).size)
    }

    @Test
    fun outdoorOnlyPathwayIsHiddenIndoors() {
        val pathway = ColorPathwayDto(
            splineId = "s3",
            splinePoints = listOf(ColorPathwayPointDto(0.0, 0.0), ColorPathwayPointDto(1.0, 1.0)),
        ).toColorPathway(config, campusId)!!
        assertTrue(pathway.segmentsForLayer(config, "bld-a", "f-a-1").isEmpty())
        assertEquals(1, pathway.segmentsForLayer(config, OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID).size)
    }

    @Test
    fun pathwayWithoutIdIsDroppedAndUnknownColorIsRed() {
        assertNull(ColorPathwayDto(splineId = "").toColorPathway(config, campusId))
        assertEquals(0xB3FF0002.toInt(), pathwayColorArgb(7))
    }
}
