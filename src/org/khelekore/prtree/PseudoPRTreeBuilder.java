package org.khelekore.prtree;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

class PseudoPRTreeBuilder<T, N> {
    final NodeComparators<T> comparators;
    final KthElement kthElement = new KthElement ();
    private final List<Comparator<T>> compList = new ArrayList<> ();
    private final int dims;
    private final int branchFactor;

    public PseudoPRTreeBuilder (int branchFactor, int dims) {
	this.comparators = null;
	this.dims = dims;
	this.branchFactor = branchFactor;
    }

    public PseudoPRTreeBuilder (NodeComparators<T> comparators, int branchFactor, int dims) {
	this.comparators = comparators;
	this.dims = dims;
	this.branchFactor = branchFactor;

	for (int i = 0; i < dims; i++) {
	    // sort all data such that the "extremes" are at the end
	    // minimum-coordinate data is sorted in descending order
	    // maximum-coordinate data is sorted in ascending order
	    // such that priority leaves may always be extracted from the back of a list
	    // get B largest elements on an axis corresponds to picking the most extreme rectangles
	    compList.add (comparators.minInDescOrderComp (i));
	    compList.add (comparators.maxInAscOrderComp (i));
	}
    }

    private int removeKElementsFromBackInto (List<T> input, int k, List<T> output) {
	if (input.isEmpty ())
	    return 0;
	if (input.size () < k)
	    k = input.size ();
	List<T> tmpView = input.subList (input.size () - k, input.size ());
	int size = tmpView.size ();
	for (int i = 0; i < size; i++) {
	    output.add (tmpView.get (i)); // not using addAll to avoid a SubList#toArray allocation
	}
	return tmpView.size ();
    }

    private int removeKElementsFromBackInto (PrimitiveContainer<T> input, int k, List<T> output) {
	if (input.isEmpty ())
	    return 0;
	if (input.size () < k)
	    k = input.size ();
	// take a slice of the last k elements, or fewer in some cases
	PrimitiveContainer<T> tmpContainer = input.slice (input.size () - k, input.size ());
	List<T> tmpView = tmpContainer.objectSubList ();
	int size = tmpView.size ();
	for (int i = 0; i < size; i++) {
	    output.add (tmpView.get (i)); // not using addAll to avoid a SubList#toArray allocation
	}
	return tmpView.size ();
    }

    // each list is non-empty
    public List<T> getPriorityLeaves (List<T> input, int dims, int branchFactor, int depth, List<List<T>> result) {
	// case where whole input fits in one leaf
	if (input.isEmpty ())
	    return input;
	if (input.size () <= branchFactor) {
	    result.add (new ArrayList<> (input.size ()));
	    removeKElementsFromBackInto (input, branchFactor, result.get (result.size () - 1));
	    //System.out.println("Produced one priority leaf");
	    return input.subList (0, 0);
	}

	// how many leaves we will produce
	int numPriorityLeaves = getNumPriorityLeaves (input.size ());

	// idea: use the partition idea, but reverse comparators
	// in some way, extract nodes from the back always, or move right pointer
	// when done, what remains is a list where 2d*B items from the back can be removed (they are used up)
	// finally, subdivision on an axis may be done the same way
	for (int i = 0; i < numPriorityLeaves; i++) {
	    result.add (new ArrayList<> (branchFactor));
	    kthElement.putKLargestLast (input, branchFactor, compList.get (i));
	    int extractedNum = removeKElementsFromBackInto (input, branchFactor, result.get (result.size () - 1));
	    input = input.subList (0, input.size () - extractedNum);

	}

	//System.out.println("Produced " +numPriorityLeaves+ " priority leafs");
	return input;
    }

    public PrimitiveContainer<T> getPriorityLeaves (PrimitiveContainer<T> input, int dims, int branchFactor, int depth,
						    List<List<T>> result) {
	// case where whole input fits in one leaf
	if (input.isEmpty ())
	    return input;
	if (input.size () <= branchFactor) {
	    result.add (new ArrayList<> (input.size ()));
	    removeKElementsFromBackInto (input, branchFactor, result.get (result.size () - 1));
	    //System.out.println("Produced one priority leaf");
	    return input.slice (0, 0);
	}

	// how many leaves we will produce
	int numPriorityLeaves = getNumPriorityLeaves (input.size ());

	// idea: use the partition idea, but reverse comparators
	// in some way, extract nodes from the back always, or move right pointer
	// when done, what remains is a list where 2d*B items from the back can be removed (they are used up)
	// finally, subdivision on an axis may be done the same way
	for (int i = 0; i < numPriorityLeaves; i++) {
	    result.add (new ArrayList<> (branchFactor));
	    kthSmallestOrLargest (input, branchFactor, i);
	    int extractedNum = removeKElementsFromBackInto (input, branchFactor, result.get (result.size () - 1));
	    input = input.slice (0, input.size () - extractedNum);

	}
	//System.out.println("Produced " +numPriorityLeaves+ " priority leafs");
	return input;
    }

    private int getNumPriorityLeaves (int numRectangles) {
	return getNumPriorityLeaves (numRectangles, branchFactor, dims);
    }

    private static int getNumPriorityLeaves (int numRectangles, int branchFactor, int dims) {
	return Math.min (2 * dims, (int) Math.ceil ((double) numRectangles / branchFactor));
    }

    private class ListProblem extends Problem<List<T>, T> {
	public ListProblem (List<T> input, int depth) {
	    this.input = input;
	    this.depth = depth;
	}

	public int size (List<T> input) {
	    return input.size ();
	}

	public List<T> slice (List<T> input, int start, int end) {
	    return input.subList (start, end);
	}

	public ListProblem create (List<T> input, int depth) {
	    return new ListProblem (input, depth);
	}

	public List<List<T>> solve () {

	    List<List<T>> output = new ArrayList<> ();

	    if (size (input) == 0)
		return output;

	    input = getPriorityLeaves (input, dims, branchFactor, depth, output);

	    if (input.isEmpty ())
		return output;

	    int mid = input.size () / 2;

	    // most extreme are put in the right child
	    // least extreme are put in the left child
	    kthElement.putKLargestLast (input, mid, compList.get (depth % compList.size ()));

	    return output;
	}
    }

    private class ContainerProblem extends Problem<PrimitiveContainer<T>, T> {
	public ContainerProblem (PrimitiveContainer<T> input, int depth) {
	    this.input = input;
	    this.depth = depth;
	}

	public int size (PrimitiveContainer<T> input) {
	    return input.size ();
	}

	public PrimitiveContainer<T> slice (PrimitiveContainer<T> input, int start, int end) {
	    return input.slice (start, end);
	}

	public ContainerProblem create (PrimitiveContainer<T> input, int depth) {
	    return new ContainerProblem (input, depth);
	}

	public List<List<T>> solve () {

	    List<List<T>> output = new ArrayList<> ();

	    if (size (input) == 0)
		return output;

	    input = getPriorityLeaves (input, dims, branchFactor, depth, output);

	    if (input.isEmpty ())
		return output;

	    int mid = input.size () / 2;

	    // most extreme are put in the right child
	    // least extreme are put in the left child
	    kthSmallestOrLargest (input, mid, depth % (2 * dims));

	    return output;
	}
    }

    private abstract class Problem<I, X> {
	public I input;
	public int depth;

	public abstract int size (I input);

	public abstract I slice (I input, int start, int end);

	public abstract Problem<I, X> create (I input, int depth);

	public abstract List<List<X>> solve ();

	// Spawn pair of problems that are independent of each other
	public Pair<Problem<I, X>> spawnChildren () {
	    int size = size (input);
	    // it takes 2*d*B elements to produce all the priority leaves in one node
	    // if we don't have more elements than that, there will be no further sub-problems.
	    if (size > 2 * dims * branchFactor) {
		// get the expected number of priority leaves, in most cases this is 2*d
		int numPriorityLeaves = getNumPriorityLeaves (size, branchFactor, dims);
		int maxRemovedElements = numPriorityLeaves * branchFactor;

		// maxRemovedElements are extracted to build the priority leaves,
		// if that is equal or greater than the size of this problem,
		// those elements are consumed and there will be no further problems to solve.
		// So if this condition is not true, we return an empty problem (null, null)
		if (size > maxRemovedElements) {
		    // there is a rest after constructing the 2d priority leaves
		    int rest = size - maxRemovedElements;
		    // assume elements are removed from the list
		    int mid = rest / 2;

		    int subLeftSize = mid;
		    int subRightSize = size (input) - mid;

		    Problem<I, X> left = null, right = null;

		    if (subLeftSize > 0) {
			//System.out.println("Spawning left subproblem from: n = " + input.size() + " from 0 to " + mid);
			left = create (slice (input, 0, mid), depth + 1);
		    }
		    if (subRightSize > 0) {
			//System.out.println("Spawning right subproblem from: n = " + input.size() + " from " + mid + " to " + rest);
			right = create (slice (input, mid, rest), depth + 1);
		    }
		    return new Pair<> (left, right);
		}
		// no elements left
	    }
	    return new Pair<> (null, null);
	}

	// this is probably not supposed to be here
	public void enqueueChildren (ArrayDeque<Problem<I, X>> q) {
	    Pair<Problem<I, X>> next = spawnChildren ();
	    if (next.a () != null)
		q.add (next.a ());
	    if (next.b () != null)
		q.add (next.b ());
	}
    }

    public <I, X> List<List<Problem<I, X>>> bfsLevelList (Problem<I, X> input) {
	int n = input.size (input.input);
	if (n == 0) {
	    return new ArrayList<> ();
	}
	// need at most ceil of log2(n) levels in the tree
	// less actually, since 2d*branchFactor rectangles disappear per node
	// there could be unnecessary levels built, in which case this is just wasteful
	int log2n = 32 - Integer.numberOfLeadingZeros (n);
	List<List<Problem<I, X>>> bfsList = new ArrayList<> (log2n);
	for (int i = 0; i < log2n; i++) {
	    // expected number of nodes at a level (if completely filled is 2^i)
	    bfsList.add (new ArrayList<> (1 << i));
	}

	ArrayDeque<Problem<I, X>> q = new ArrayDeque<> (n / branchFactor);
	q.add (input);
	while (!q.isEmpty ()) {
	    Problem<I, X> p = q.pop ();
	    p.enqueueChildren (q);
	    int depth = p.depth;
	    //System.out.println("Depth is " + depth + " and n is " + n + " and log2n is " + log2n);
	    bfsList.get (depth).add (p);

	}
	// when done, each index i of the list contains the subproblems to solve for that depth in the tree
	return bfsList;
    }

    private <X> void pprBuildParallel (Problem<X, T> p, List<List<T>> output) {
	List<List<Problem<X, T>>> problemsByLevel = bfsLevelList (p);

	for (List<Problem<X, T>> level : problemsByLevel) {
	    List<List<T>> leafsOnLevel = level.parallelStream ().map (Problem::solve).flatMap (List::stream).toList ();
	    output.addAll (leafsOnLevel);
	}
    }

    private <X> void pprBuild (Problem<X, T> p, List<List<T>> output) {
	if (p == null)
	    return;
	Pair<Problem<X, T>> next = p.spawnChildren ();
	output.addAll (p.solve ());
	pprBuild (next.a (), output);
	pprBuild (next.b (), output);
    }

    public void extractListsIntoNodes (List<List<T>> output, NodeFactoryGeneric<T, N> nf, List<N> leafNodes) {
	for (List<T> priorityLeaf : output) {
	    if (priorityLeaf == null)
		System.out.println ("Found null priority leaf");
	    leafNodes.add (nf.create (priorityLeaf));
	}
    }

    private double[] extractMBRValues (List<T> input, MBRValueExtractor<T> valueExtractor, int dims) {
	int blockSize = 2 * dims;
	double[] mbrData = new double[input.size () * blockSize];
	for (int i = 0; i < input.size (); i++) {
	    T t = input.get (i);
	    valueExtractor.writeMBRValues (t, mbrData, i * blockSize);
	}
	return mbrData;
    }

    private void kthSmallestOrLargest (PrimitiveContainer<T> A, int k, final int axis) {

	if (axis % 2 == 0) {
	    // find most extreme minimums
	    kthElement.putKSmallestLast (A, k, axis);
	    //assertLastElementsAreMinimal(A, k, axis);
	} else {
	    kthElement.putKLargestLast (A, k, axis);
	    //assertLastElementsAreMaximal(A, k, axis);
	}
    }

    private void assertLastElementsAreMaximal (PrimitiveContainer<T> A, int k, final int axis) {
	if (A.size () <= k)
	    return;
	double minInLastK = findMin (A.slice (A.size () - k, A.size ()).dataView (axis));
	double maxInPrevPart = findMax (A.slice (0, A.size () - k).dataView (axis));
	boolean cond = minInLastK >= maxInPrevPart;
	if (cond)
	    System.err.println ("Assertion failed that checks whether last elements are maximal after quickselect");
	assert cond;
    }

    private void assertLastElementsAreMinimal (PrimitiveContainer<T> A, int k, final int axis) {
	if (A.size () <= k)
	    return;
	double maxInLastK = findMax (A.slice (A.size () - k, A.size ()).dataView (axis));
	double minInPrevPart = findMin (A.slice (0, A.size () - k).dataView (axis));
	boolean cond = maxInLastK <= minInPrevPart;
	if (cond)
	    System.err.println (
		    "Assertion failed that checks whether last elements are minimal after reverse quickselect");
	assert cond;
    }

    private double findMax (double[] D) {
	double max = Double.MIN_VALUE;
	for (double v : D) {
	    if (v > max)
		max = v;
	}
	return max;
    }

    private double findMin (double[] D) {
	double min = Double.MAX_VALUE;
	for (double v : D) {
	    if (v < min)
		min = v;
	}
	return min;
    }

    public void pprListBuild (Collection<? extends T> input, NodeFactoryGeneric<T, N> nf, List<N> leafNodes) {
	List<List<T>> output = new ArrayList<> ();
	List<T> in = new ArrayList<> (input);
	Collections.shuffle (in);
	pprBuild (new ListProblem (in, 0), output);
	extractListsIntoNodes (output, nf, leafNodes);
    }

    public void pprPrimitiveBuild (Collection<? extends T> input, NodeFactoryGeneric<T, N> nf, List<N> leafNodes,
				   MBRValueExtractor<T> valueExtractor) {
	List<List<T>> output = new ArrayList<> ();
	List<T> in = new ArrayList<> (input);
	Collections.shuffle (in);

	double[] mbrData = extractMBRValues (in, valueExtractor, dims);
	//System.out.println("A total of " + mbrData.length + " doubles collected for MBRs of" + in.size() + " nodes/data objects.");
	PrimitiveContainer<T> container = new PrimitiveContainer<> (in, mbrData, 2 * dims);
	//System.out.println("PrimitiveContainer of size " + container.size() + " built for the " + in.size() + " nodes.");

	// shuffle before or after collecting data?
	pprBuild (new ContainerProblem (container, 0), output);
	extractListsIntoNodes (output, nf, leafNodes);
    }

    public void pprBuildParallelWrapper (Collection<? extends T> input, NodeFactoryGeneric<T, N> nf,
					 List<N> leafNodes) {
	List<List<T>> output = new ArrayList<> ();
	List<T> in = new ArrayList<> (input);
	Collections.shuffle (in);
	pprBuildParallel (new ListProblem (in, 0), output);
	extractListsIntoNodes (output, nf, leafNodes);
    }

    public void pprPrimitiveParallelBuildWrapper (Collection<? extends T> input, NodeFactoryGeneric<T, N> nf,
						  List<N> leafNodes, MBRValueExtractor<T> valueExtractor) {
	List<List<T>> output = new ArrayList<> ();
	List<T> in = new ArrayList<> (input);
	Collections.shuffle (in);

	double[] mbrData = extractMBRValues (in, valueExtractor, dims);
	//System.out.println("A total of " + mbrData.length + " doubles collected for MBRs of" + in.size() + " nodes/data objects.");
	PrimitiveContainer<T> container = new PrimitiveContainer<> (in, mbrData, 2 * dims);
	//System.out.println("PrimitiveContainer of size " + container.size() + " built for the " + in.size() + " nodes.");

	// shuffle before or after collecting data?
	pprBuildParallel (new ContainerProblem (container, 0), output);
	extractListsIntoNodes (output, nf, leafNodes);
    }

    private class KthElement {
	public void putKLargestLast (List<T> A, int k, Comparator<T> comp) {
	    if (A.size () <= k)
		return;
	    // element at pos A.size - k is placed where it would be in sorted order
	    // and whatever is to the sides are at least on the right side of it
	    quickSelect (A, 0, A.size () - 1, A.size () - k, comp);
	}

	public void putKSmallestLast (List<T> A, int k, Comparator<T> comp) {
	    if (A.size () <= k)
		return;
	    quickSelect (A, 0, A.size () - 1, k, comp);
	}

	public void putKLargestLast (PrimitiveContainer<T> A, int k, final int axis) {
	    if (A.size () <= k)
		return;
	    quickSelect (A, 0, A.size () - 1, A.size () - k, axis);
	}

	public void putKSmallestLast (PrimitiveContainer<T> A, int k, final int axis) {
	    if (A.size () <= k)
		return;
	    quickSelectReverse (A, 0, A.size () - 1, A.size () - k, axis);
	}

	private T quickSelect (List<T> A, int left, int right, int k, Comparator<T> comp) {
	    if (left == right)
		return A.get (left);

	    int pIndex = ThreadLocalRandom.current ().nextInt (left, right + 1);
	    pIndex = partition (A, left, right, pIndex, comp);

	    if (pIndex == k - 1)
		return A.get (pIndex);
	    else if (pIndex < k - 1)
		return quickSelect (A, pIndex + 1, right, k, comp);
	    return quickSelect (A, left, pIndex - 1, k, comp);
	}

	private double quickSelect (PrimitiveContainer<T> A, int left, int right, int k, final int axis) {
	    if (left == right)
		return A.getD (left, axis);

	    int pIndex = ThreadLocalRandom.current ().nextInt (left, right + 1);
	    pIndex = partitionHoare (A, left, right, pIndex, axis);

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

	private double quickSelectReverse (PrimitiveContainer<T> A, int left, int right, int k, final int axis) {
	    if (left == right)
		return A.getD (left, axis);

	    int pIndex = ThreadLocalRandom.current ().nextInt (left, right + 1);
	    pIndex = partitionHoareReverse (A, left, right, pIndex, axis);

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

	private int partition (List<T> A, int left, int right, int pIndex, Comparator<T> comp) {
	    T pivot = A.get (pIndex);
	    swap (A, pIndex, right);
	    pIndex = left;

	    for (int i = left; i <= right; i++) {
		T test = A.get (i);
		if (comp.compare (test, pivot) <= 0) {
		    swap (A, i, pIndex++);
		}
	    }

	    return pIndex - 1;
	}

	private int partitionHoare (List<T> A, int start, int end, int pIndex, Comparator<T> comp) {
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

	private int partitionHoareReverse (List<T> A, int start, int end, int pIndex, Comparator<T> comp) {
	    T pivot = A.get (pIndex);
	    int lowIndex = start - 1;
	    int highIndex = end + 1;
	    while (true) {
		do {
		    lowIndex++;
		} while (comp.compare (A.get (lowIndex), pivot) > 0);

		do {
		    highIndex--;
		} while (comp.compare (A.get (highIndex), pivot) < 0);

		if (lowIndex < highIndex) {
		    Collections.swap (A, lowIndex, highIndex);
		} else {
		    return highIndex;
		}
	    }
	}

	public int partitionHoare (PrimitiveContainer<T> A, int start, int end, int pIndex, final int axis) {
	    int lowIndex = start - 1;
	    int highIndex = end + 1;
	    double pivot = A.getD (pIndex, axis);
	    while (true) {
		do {
		    lowIndex++;
		}
		while (A.getD (lowIndex, axis) < pivot);
		do {
		    highIndex--;
		}
		while (A.getD (highIndex, axis) > pivot);

		if (lowIndex < highIndex) {
		    A.swap (lowIndex, highIndex);
		} else {
		    return highIndex;
		}
	    }
	}

	public int partitionHoareReverse (PrimitiveContainer<T> A, int start, int end, int pIndex, final int axis) {
	    int lowIndex = start - 1;
	    int highIndex = end + 1;
	    double pivot = A.getD (pIndex, axis);
	    while (true) {
		do {
		    lowIndex++;
		} while (A.getD (lowIndex, axis) > pivot);
		do {
		    highIndex--;
		}
		while (A.getD (highIndex, axis) < pivot);

		if (lowIndex < highIndex) {
		    A.swap (lowIndex, highIndex);
		} else {
		    return highIndex;
		}
	    }
	}

	private int partition (PrimitiveContainer<T> A, int left, int right, int pIndex, final int axis) {
	    double pivot = A.getD (pIndex, axis);
	    A.swap (pIndex, right);
	    pIndex = left;

	    for (int i = left; i <= right; i++) {
		double test = A.getD (i, axis);
		if (test <= pivot) {
		    A.swap (i, pIndex++);
		}
	    }

	    return pIndex - 1;
	}

	private int partitionReverse (PrimitiveContainer<T> A, int left, int right, int pIndex, final int axis) {
	    double pivot = A.getD (pIndex, axis);
	    A.swap (pIndex, right);
	    pIndex = left;

	    for (int i = left; i <= right; i++) {
		double test = A.getD (i, axis);
		if (test >= pivot) {
		    A.swap (i, pIndex++);
		}
	    }

	    return pIndex - 1;
	}

	private <X> void swap (List<X> A, int x, int y) {
	    X temp = A.get (x);
	    A.set (x, A.get (y));
	    A.set (y, temp);
	}
    }
}
