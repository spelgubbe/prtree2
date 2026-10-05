package org.khelekore.prtree.selection;

import org.khelekore.prtree.PrimitiveContainer;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Partition algorithms. Only Hoare's seems safe in practice.
 */
public class Partition {
    public static <T> int partitionHoare (List<T> A, int start, int end, int pIndex, Comparator<T> comp) {
	T pivot = A.get (pIndex);
	int lowIndex = start - 1;
	int highIndex = end + 1;
	while (true) {
	    do {
		lowIndex++;
	    } while (comp.compare (A.get (lowIndex), pivot) < 0);

	    do {
		highIndex--;
	    } while (comp.compare (A.get (highIndex), pivot) > 0);

	    if (lowIndex < highIndex) {
		Collections.swap (A, lowIndex, highIndex);
	    } else {
		return highIndex;
	    }
	}
    }

    public static <T> int partitionHoare (PrimitiveContainer<T> A, int start, int end, int pIndex, final int axis) {
	int lowIndex = start - 1;
	int highIndex = end + 1;
	double pivot = A.getD (pIndex, axis);
	while (true) {
	    do {
		lowIndex++;
	    } while (A.getD (lowIndex, axis) < pivot);
	    do {
		highIndex--;
	    } while (A.getD (highIndex, axis) > pivot);

	    if (lowIndex < highIndex) {
		A.swap (lowIndex, highIndex);
	    } else {
		return highIndex;
	    }
	}
    }

    public static <T> int partitionHoareReverse (PrimitiveContainer<T> A, int start, int end, int pIndex,
						 final int axis) {
	int lowIndex = start - 1;
	int highIndex = end + 1;
	double pivot = A.getD (pIndex, axis);
	while (true) {
	    do {
		lowIndex++;
	    } while (A.getD (lowIndex, axis) > pivot);
	    do {
		highIndex--;
	    } while (A.getD (highIndex, axis) < pivot);

	    if (lowIndex < highIndex) {
		A.swap (lowIndex, highIndex);
	    } else {
		return highIndex;
	    }
	}
    }
}
