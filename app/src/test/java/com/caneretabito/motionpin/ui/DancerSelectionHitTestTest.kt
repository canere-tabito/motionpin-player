package com.caneretabito.motionpin.ui

import com.caneretabito.motionpin.tracking.PersonBox
import org.junit.Assert.*
import org.junit.Test

class DancerSelectionHitTestTest {
    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        PersonBox(left, top, right, bottom, .8f, emptyList())

    @Test fun overlappingPeopleCanEachBePreviewedBeforeConfirmation() {
        val people = listOf(box(.2f, .1f, .7f, .9f), box(.4f, .2f, .6f, .8f))
        val hits = dancerHitCandidates(people, 50f, 50f, 100f, 100f, 48f)
        assertEquals(listOf(1, 0), hits)
        assertEquals(1, nextDancerSelection(hits, null))
        assertEquals(0, nextDancerSelection(hits, 1))
        assertEquals(1, nextDancerSelection(hits, 0))
    }

    @Test fun aSmallDancerNearTheEdgeHasAnExpandedTouchTarget() {
        val people = listOf(box(.01f, .4f, .04f, .46f))
        assertEquals(listOf(0), dancerHitCandidates(people, 20f, 44f, 100f, 100f, 48f))
        assertTrue(dancerHitCandidates(people, 80f, 44f, 100f, 100f, 48f).isEmpty())
        assertEquals(0, nextDancerSelection(emptyList(), 0))
    }

    @Test fun actualBoxTakesPriorityOverAnAdjacentExpandedTarget() {
        val people = listOf(box(.1f, .2f, .4f, .8f), box(.44f, .45f, .47f, .51f))
        assertEquals(listOf(0), dancerHitCandidates(people, 39f, 48f, 100f, 100f, 48f))
    }

    @Test fun eightCandidatesRemainDeterministicAndIndependentOfOrientation() {
        val people = (0..7).map { box(.02f + it * .12f, .3f, .08f + it * .12f, .7f) }
        listOf(200f to 400f, 400f to 200f).forEach { (width, height) ->
            people.forEachIndexed { index, person ->
                assertEquals(listOf(index), dancerHitCandidates(people, person.cx * width,
                    person.cy * height, width, height, 48f))
            }
        }
    }

    @Test fun invalidGeometryAndOffImageTouchesDoNotSelectAPerson() {
        val people = listOf(box(.2f, .1f, .7f, .9f))
        assertTrue(dancerHitCandidates(people, 20f, 20f, 0f, 100f, 48f).isEmpty())
        assertTrue(dancerHitCandidates(people, Float.NaN, 20f, 100f, 100f, 48f).isEmpty())
        assertTrue(dancerHitCandidates(people, -1f, 20f, 100f, 100f, 48f).isEmpty())
        assertNull(nextDancerSelection(emptyList(), null))
    }
}
