package com.fsrs.optimizer;

import java.util.List;

/**
 * The complete review history of a single card, as consumed by the optimizer.
 *
 * <p>Both lists are parallel and ordered oldest-first, so entry {@code i} describes
 * the {@code i}-th review of that card. The record is the optimizer's only input,
 * which is what lets the fit run offline against {@code review_log} rows without
 * pulling in any persistence or framework type.
 *
 * <h2>The first review has no measurable gap</h2>
 * A card's very first review has no previous review to measure against, so
 * {@code elapsedDaysBeforeReview.get(0)} cannot be predicted from and is ignored
 * when the loss is computed; that review only seeds the memory state through
 * {@code initialState(rating)}. It is still required to be present and valid,
 * because keeping the two lists exactly aligned is what makes the record safe to
 * iterate by index.
 *
 * @param elapsedDaysBeforeReview days between the previous review and this one;
 *                                fractional values are allowed and are used as-is,
 *                                because {@code review_log.elapsed_days} is a real
 *                                value, not a rounded day count
 * @param ratings                 1 Again, 2 Hard, 3 Good, 4 Easy
 */
public record CardHistory(List<Double> elapsedDaysBeforeReview, List<Integer> ratings) {

    /** Compact constructor: validate once, then freeze, so the optimizer can trust its input. */
    public CardHistory {
        if (elapsedDaysBeforeReview == null || ratings == null) {
            throw new IllegalArgumentException("elapsedDaysBeforeReview and ratings must not be null");
        }
        if (elapsedDaysBeforeReview.size() != ratings.size()) {
            throw new IllegalArgumentException("elapsedDaysBeforeReview and ratings must have the same size but were "
                    + elapsedDaysBeforeReview.size() + " and " + ratings.size());
        }
        for (int i = 0; i < ratings.size(); i++) {
            Integer rating = ratings.get(i);
            if (rating == null || rating < 1 || rating > 4) {
                throw new IllegalArgumentException("rating at index " + i + " must be within 1..4 but was " + rating);
            }
            Double elapsed = elapsedDaysBeforeReview.get(i);
            if (elapsed == null || !Double.isFinite(elapsed) || elapsed < 0.0) {
                throw new IllegalArgumentException(
                        "elapsedDaysBeforeReview at index " + i + " must be finite and >= 0 but was " + elapsed);
            }
        }
        elapsedDaysBeforeReview = List.copyOf(elapsedDaysBeforeReview);
        ratings = List.copyOf(ratings);
    }

    /** Number of reviews in this history. */
    public int size() {
        return ratings.size();
    }

    /** A card that has never been reviewed. Safe to pass in; the optimizer skips it. */
    public static CardHistory empty() {
        return new CardHistory(List.of(), List.of());
    }

    /**
     * Primitive-array convenience factory for callers that read columns out of a
     * database rather than out of a list.
     *
     * @throws IllegalArgumentException when the two arrays differ in length
     */
    public static CardHistory of(double[] elapsedDaysBeforeReview, int[] ratings) {
        if (elapsedDaysBeforeReview.length != ratings.length) {
            throw new IllegalArgumentException("elapsedDaysBeforeReview and ratings must have the same length but were "
                    + elapsedDaysBeforeReview.length + " and " + ratings.length);
        }
        List<Double> elapsed = new java.util.ArrayList<>(ratings.length);
        List<Integer> grades = new java.util.ArrayList<>(ratings.length);
        for (int i = 0; i < ratings.length; i++) {
            elapsed.add(elapsedDaysBeforeReview[i]);
            grades.add(ratings[i]);
        }
        return new CardHistory(elapsed, grades);
    }
}
