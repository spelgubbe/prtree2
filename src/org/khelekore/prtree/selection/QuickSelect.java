package org.khelekore.prtree.selection;

import org.khelekore.prtree.PrimitiveContainer;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Collection of quickselect implementations for pr-tree building.
 */
public class QuickSelect {

    public static <T> void quickSelect (PrimitiveContainer<T> A, int left, int right, int k, final int axis) {
	if (left >= right)
	    return;

	// generate a pivot index that is less than right
	int pIndex = ThreadLocalRandom.current ().nextInt (left, right);

	// inclusive index that is the last index of the left partition.
	int leftBoundary = Partition.partitionHoare (A, left, right, pIndex, axis);

	// simply returns if leftBoundary == k - 1, because then the array is partitioned with
	// k smallest elements in the left partition.
	if (leftBoundary < k - 1) {
	    quickSelect (A, leftBoundary + 1, right, k, axis);
	} else if (leftBoundary > k - 1) {
	    quickSelect (A, left, leftBoundary, k, axis);
	}
    }

    public static <T> void quickSelect (List<T> A, int left, int right, int k, Comparator<T> comp) {
	if (left >= right)
	    return;

	int pivotIndex = ThreadLocalRandom.current ().nextInt (left, right);
	int leftBoundary = Partition.partitionHoare (A, left, right, pivotIndex, comp);

	if (leftBoundary < k - 1)
	    quickSelect (A, leftBoundary + 1, right, k, comp);
	else if (leftBoundary > k - 1) {
	    quickSelect (A, left, leftBoundary, k, comp);
	}
    }

    public static <T> void quickSelectReverse (PrimitiveContainer<T> A, int left, int right, int k, final int axis) {
	if (left >= right)
	    return;
	// left and right are inclusive
	// generate a partition index that is never exactly right, to never produce an empty right partition.
	int pivotIndex = ThreadLocalRandom.current ().nextInt (left, right);
	// left boundary returned means the array is partitioned so that the left boundary index
	// (inclusive) is the last element of the left partition.
	int leftBoundary = Partition.partitionHoareReverse (A, left, right, pivotIndex, axis);

	// we want the left boundary to be exactly k - 1, that is the stop condition.
	if (leftBoundary < k - 1) {
	    // enlarging the left partition by accepting more elements to the left boundary.
	    quickSelectReverse (A, leftBoundary + 1, right, k, axis);
	} else if (leftBoundary > k - 1) {
	    // shrinking the left partition.
	    quickSelectReverse (A, left, leftBoundary, k, axis);
	}

    }
}
