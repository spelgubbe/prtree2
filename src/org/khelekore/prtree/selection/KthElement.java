package org.khelekore.prtree.selection;

import org.khelekore.prtree.PrimitiveContainer;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Collection of functions that partition a list/array.
 */
public class KthElement<T> {

    public void putKLargestLast (List<T> A, int k, Comparator<T> comp) {
	// comparator decides the order, the name "put k largest last" is a bit misleading because of this.
	if (A.size () <= k)
	    return;
	// element at pos A.size - k is placed where it would be in sorted order
	// and whatever is to the sides are at least on the right side of it
	QuickSelect.quickSelect (A, 0, A.size () - 1, A.size () - k, comp);
    }

    public void putKLargestLast (PrimitiveContainer<T> A, int k, final int axis) {
	if (A.size () <= k)
	    return;
	QuickSelect.quickSelect (A, 0, A.size () - 1, A.size () - k, axis);
    }

    public void putKSmallestLast (PrimitiveContainer<T> A, int k, final int axis) {
	if (A.size () <= k)
	    return;
	QuickSelect.quickSelectReverse (A, 0, A.size () - 1, A.size () - k, axis);
    }
}
