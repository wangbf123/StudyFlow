package com.studyflow.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReciprocalRankFusionTest {
    @Test
    void itemSupportedByBothListsOutranksFirstItemOfOneList() {
        var scores = ReciprocalRankFusion.scores(List.of(1L, 2L), List.of(3L, 2L), 60);
        assertEquals(2.0 / 62, scores.get(2L), 1e-12);
        assertTrue(scores.get(2L) > scores.get(1L));
    }

    @Test
    void duplicateHitCannotContributeTwiceAndMissingRouteStillWorks() {
        var scores = ReciprocalRankFusion.scores(List.of(1L, 1L, 2L), List.of(), 60);
        assertEquals(1.0 / 61, scores.get(1L), 1e-12);
        assertEquals(1.0 / 63, scores.get(2L), 1e-12);
    }

    @Test
    void rejectsInvalidConstant() {
        assertThrows(IllegalArgumentException.class,
                () -> ReciprocalRankFusion.scores(List.of(1L), List.of(), 0));
    }
}
