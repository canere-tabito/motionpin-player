package com.caneretabito.motionpin.tracking

import org.junit.Assert.*
import org.junit.Test

class PersonAssociationTest {
    private val clothes = List(36) { .5f }
    private fun box(x: Float, y: Float, w: Float = .2f, h: Float = .5f, clothes: List<Float> = this.clothes) =
        PersonBox(x, y, x + w, y + h, .9f, clothes)

    @Test fun followsSamePersonInsteadOfAnotherPerson() {
        val original = box(.20f, .20f)
        val same = box(.22f, .20f)
        val other = box(.72f, .20f)
        assertEquals(same, PersonAssociation.choose(original, listOf(other, same)))
    }

    @Test fun ambiguityDoesNotSilentlySwapDancers() {
        val original = box(.20f, .20f)
        assertNull(PersonAssociation.choose(original, listOf(box(.21f, .20f), box(.22f, .20f))))
    }

    @Test fun missingPersonMustBeExplicit() {
        assertNull(PersonAssociation.choose(box(.10f, .10f), emptyList()))
        assertNull(PersonAssociation.choose(box(.10f, .10f), listOf(box(.80f, .80f))))
    }
}
