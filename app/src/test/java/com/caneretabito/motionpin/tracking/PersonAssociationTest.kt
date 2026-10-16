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
        assertNull(PersonAssociation.choose(original, listOf(box(.210f, .20f), box(.212f, .20f))))
    }

    @Test fun missingPersonMustBeExplicit() {
        assertNull(PersonAssociation.choose(box(.10f, .10f), emptyList()))
        assertNull(PersonAssociation.choose(box(.10f, .10f), listOf(box(.80f, .80f))))
    }

    @Test fun missingMalformedOrNonfiniteAppearanceNeverMeansPerfectIdentityAgreement() {
        val original = box(.20f, .20f)
        val invalidSignatures = listOf(emptyList(), List(35) { .5f }, List(36) { Float.NaN },
            List(36) { Float.POSITIVE_INFINITY }, List(36) { -.1f }, List(36) { 1.1f })
        invalidSignatures.forEach { appearance ->
            assertNull(PersonAssociation.choose(original, listOf(original.copy(appearance = appearance))))
        }
        assertNull(PersonAssociation.choose(original.copy(appearance = emptyList()), listOf(original)))
    }

    @Test fun invalidSeedCannotConstructIdentityTracker() {
        assertThrows(IllegalArgumentException::class.java) {
            StablePersonMatcher(box(.20f, .20f, clothes = emptyList()), 0)
        }
    }
}
