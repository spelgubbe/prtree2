package org.khelekore.prtree.selection;

import org.khelekore.prtree.PrimitiveContainer;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Collection of quickselect implementations for pr-tree building.
 */
public class QuickSelect {
    public static <T> double quickSelect (PrimitiveContainer<T> A, int left, int right, int k, final int axis) {
	if (left == right)
	    return A.getD (left, axis);

	int pIndex = ThreadLocalRandom.current ().nextInt (left, right + 1);
	pIndex = Partition.partitionHoare (A, left, right, pIndex, axis);

	if (pIndex == k - 1)
	    return A.getD (pIndex, axis);
	else if (pIndex < k - 1) {
	    // don't want left pointer to cross right
	    int newLeft = Math.min (right, pIndex + 1);
	    return quickSelect (A, newLeft, right, k, axis);
	}
	// don't want right pointer to cross left
	int newRight = Math.max (left, pIndex - 1);
	return quickSelect (A, left, newRight, k, axis);
    }

    public static <T> T quickSelect (List<T> A, int left, int right, int k, Comparator<T> comp) {
	if (left == right)
	    return A.get (left);

	int pIndex = ThreadLocalRandom.current ().nextInt (left, right + 1);
	pIndex = Partition.partitionHoare (A, left, right, pIndex, comp);

	if (pIndex == k - 1)
	    return A.get (pIndex);
	else if (pIndex < k - 1)
	    return quickSelect (A, pIndex + 1, right, k, comp);
	return quickSelect (A, left, pIndex - 1, k, comp);
    }

    public static <T> double quickSelectReverse (PrimitiveContainer<T> A, int left, int right, int k, final int axis) {
	if (left == right)
	    return A.getD (left, axis);

	int pIndex = ThreadLocalRandom.current ().nextInt (left, right + 1);
	pIndex = Partition.partitionHoareReverse (A, left, right, pIndex, axis);

	if (pIndex == k - 1)
	    return A.getD (pIndex, axis);
	else if (pIndex < k - 1) {
	    // don't want left pointer to cross right
	    int newLeft = Math.min (right, pIndex + 1);
	    return quickSelectReverse (A, newLeft, right, k, axis);
	}
	// don't want right pointer to cross left
	int newRight = Math.max (left, pIndex - 1);
	return quickSelectReverse (A, left, newRight, k, axis);
    }
}
