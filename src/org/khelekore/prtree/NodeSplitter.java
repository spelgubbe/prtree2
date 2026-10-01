package org.khelekore.prtree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

class NodeSplitter<T> {

    private record IntDouble(int i, double d) {
    }

    private final MBRConverter<T> converter;

    public NodeSplitter (MBRConverter<T> converter) {
	this.converter = converter;
    }

    private <X> void assertAllInCollectionAreNonNull (Collection<X> col) {
	for (X x : col) {
	    assert x != null;
	}
    }

    /**
     * R-tree QuadraticSplit algorithm
     * @param nodes List of nodes to split into two lists of nodes
     * @param minBranchFactor Minimum number of children of a node
     * @param mbrProvider Function to calculate an MBR given an object of type X and a MBRConverter<T>
     * @param <X> either T or Node<T>
     * @return Two lists containing at least minBranchFactor objects of type X each
     */
    public <X> Pair<List<X>> quadraticSplit (List<X> nodes, int minBranchFactor, int maxBranchFactor,
					     BiFunction<X, MBRConverter<T>, MBR> mbrProvider) {
	assert nodes.size () == maxBranchFactor + 1;
	//System.out.println("Max branch factor is = " + maxBranchFactor);
	assertAllInCollectionAreNonNull (nodes);
	//Set<X> nodeSet = new HashSet<>(nodes); // HashSet uses equals() instead of reference equals
	Set<X> nodeSet = IdentityHashSet.fromCollection (nodes);
	List<X> A = new ArrayList<> ();
	List<X> B = new ArrayList<> ();

	Pair<X> seeds = pickSeeds (nodeSet, mbrProvider);

	// this is a condition for this algorithm to do anything useful
	assert seeds.a () != null && seeds.b () != null;

	A.add (seeds.a ());
	B.add (seeds.b ());
	nodeSet.remove (seeds.a ());
	nodeSet.remove (seeds.b ());

	MBR aMbr = mbrProvider.apply (seeds.a (), converter);
	MBR bMbr = mbrProvider.apply (seeds.b (), converter);

	while (!nodeSet.isEmpty ()) {

	    int sizeA = A.size ();
	    int sizeB = B.size ();
	    int nodesLeft = nodeSet.size ();
	    // check whether the algorithm needs to exit early
	    if (sizeA + nodesLeft == minBranchFactor || sizeB + nodesLeft == minBranchFactor) {

		if (A.size () < minBranchFactor && B.size () < minBranchFactor) {
		    System.out.println (
			    "Somewhat critical error. Too small node called on to split. Should never happen. (m >= M/2)");
		}

		// one set is smaller than minBranchFactor
		if (A.size () < minBranchFactor) {
		    A.addAll (nodeSet);
		    nodeSet.clear ();
		} else if (B.size () < minBranchFactor) {
		    B.addAll (nodeSet);
		    nodeSet.clear ();
		}

		//System.out.println("Breaking the quadraticSplit loop because one set requires all remaining elements");
		//System.out.printf("sizeA = %d, sizeB = %d, nodesLeft = %d, minBranchFactor = %d\n", sizeA, sizeB, nodesLeft, minBranchFactor);
		break;
	    }

	    X e = pickNext (nodeSet, aMbr, bMbr, mbrProvider);

	    MBR eMbr = mbrProvider.apply (e, converter);
	    MBR aUe = aMbr.union (eMbr);
	    MBR bUe = bMbr.union (eMbr);

	    double aArea = aMbr.getArea ();
	    double bArea = bMbr.getArea ();
	    double aeArea = aUe.getArea ();
	    double beArea = bUe.getArea ();

	    // remove e from set as it will be added to A or B
	    nodeSet.remove (e);

	    double enlargementA = aeArea - aArea;
	    double enlargementB = beArea - bArea;

	    //rtreeSplitEntryChoice(enlargementA, enlargementB, aArea, bArea, e, A, B);

	    int choice = rtreeSplitEntryNumber (enlargementA, enlargementB, aArea, bArea, A, B);
	    if (choice == 0) {
		A.add (e);
		aMbr = aMbr.union (eMbr);
	    } else {
		B.add (e);
		bMbr = bMbr.union (eMbr);
	    }

	}

	if (A.size () < minBranchFactor || B.size () < minBranchFactor) {
	    System.out.println (
		    "Quadratic split failed, not enough entries in either A or B (B size = " + B.size () + ", A size = "
		    + A.size ());
	}

	if (A.size () + B.size () != nodes.size ()) {
	    System.out.println ("Quadratic split failed, not all nodes in the node set were added to a set");
	    System.out.printf ("numNodes = %d, sizeA = %d, sizeB = %d, nodesLeft = %d, minBranchFactor = %d\n",
			       nodes.size (), A.size (), B.size (), nodeSet.size (), minBranchFactor);
	}

	return new Pair<> (A, B);
    }

    // Side-effects on A, B
    private <X> void rtreeSplitEntryChoice (double e1, double e2, double a1, double a2, X e, List<X> A, List<X> B) {
	if (e1 < e2) {
	    A.add (e);
	} else if (e2 < e1) {
	    B.add (e);
	} else {
	    // equality
	    if (a1 < a2) {
		A.add (e);
	    } else if (a2 < a1) {
		B.add (e);
	    } else {
		// equal enlargement and area
		// then prioritize size
		if (A.size () < B.size ()) {
		    A.add (e);
		} else {
		    B.add (e);
		}
	    }
	}
    }

    private <X> int rtreeSplitEntryNumber (double e1, double e2, double a1, double a2, List<X> A, List<X> B) {
	if (e1 < e2) {
	    return 0;
	} else if (e2 < e1) {
	    return 1;
	} else {
	    // equality
	    if (a1 < a2) {
		return 0;
	    } else if (a2 < a1) {
		return 1;
	    } else {
		// equal enlargement and area
		// then prioritize size
		if (A.size () < B.size ()) {
		    return 0;
		} else {
		    return 1;
		}
	    }
	}
    }

    /**
     * R-tree PickNext algorithm
     *
     * Called to determine which node has the highest preference of being added to any of the two groups
     * @param nodes List of objects of type X to choose from
     * @param aMbr Minimum bounding rectangle of the first group of nodes
     * @param bMbr Minimum bounding rectangle of the second group of nodes
     * @param mbrProvider Function to calculate an MBR given an object of type X and a MBRConverter<T>
     * @param <X> either T or Node<T>
     * @return The node with the highest preference of being added to any of the two groups
     */
    public <X> X pickNext (Collection<X> nodes, MBR aMbr, MBR bMbr, BiFunction<X, MBRConverter<T>, MBR> mbrProvider) {
	X entry = null;
	double diff = -1;
	for (X node : nodes) {
	    MBR eMbr = mbrProvider.apply (node, converter);
	    MBR aUe = aMbr.union (eMbr);
	    MBR bUe = bMbr.union (eMbr);

	    double dA = aUe.getArea () - aMbr.getArea ();
	    double dB = bUe.getArea () - bMbr.getArea ();

	    if (Math.abs (dA - dB) > diff) {
		diff = Math.abs (dA - dB); // dont need to call abs
		entry = node;
	    }
	}
	return entry;
    }

    // fever dream provider
    public <X> X pickNext2 (Collection<X> nodes, MBR aMbr, MBR bMbr,
			    BiFunction<X, MBRConverter<T>, Function<MBR, Double>> fastAreaProvider) {
	X entry = null;
	double diff = -1;
	for (X node : nodes) {
	    Function<MBR, Double> unionAreaFunc = fastAreaProvider.apply (node, converter);

	    double dA = unionAreaFunc.apply (aMbr) - aMbr.getArea ();
	    double dB = unionAreaFunc.apply (bMbr) - bMbr.getArea ();

	    if (Math.abs (dA - dB) > diff) {
		diff = Math.abs (dA - dB); // dont need to call abs
		entry = node;
	    }
	}
	return entry;
    }

    /**
     * R-tree PickSeeds algorithm
     *
     * Finds two entries to be the first entries added to the two groups, respectively
     * @param nodes List of objects of type X to choose from
     * @param mbrProvider Function to calculate an MBR given an object of type X and a MBRConverter<T>
     * @param <X> either T or Node<T>
     * @return
     */
    public <X> Pair<X> pickSeeds (Collection<X> nodes, BiFunction<X, MBRConverter<T>, MBR> mbrProvider) {
	double dMax = Double.NEGATIVE_INFINITY;
	X i = null, j = null;
	//System.out.println("Size of nodes collection: " + nodes.size());
	for (X a : nodes) {
	    for (X b : nodes) {
		if (a != b) {
		    // TODO: refactor to cache MBRs, or 90% of time will be spent creating objects in leafs
		    MBR aMbr = mbrProvider.apply (a, converter);
		    MBR bMbr = mbrProvider.apply (b, converter);
		    MBR J = aMbr.union (bMbr);
		    // double-checked this against pseudocode in publication
		    double d = J.getArea () - aMbr.getArea () - bMbr.getArea ();

		    //System.out.println("d = " + d);
		    if (d > dMax) {
			dMax = d;
			i = a;
			j = b;
		    }
		}
	    }
	}
	return new Pair<> (i, j);
    }

    /**
     * @param nodes List of maxBranchFactor+1 nodes to split into two lists
     * @param minBranchFactor Minimum number of children of a node
     * @param maxBranchFactor Maximum number of children of a node
     * @param mbrProvider Function to calculate an MBR given an object of type X and a MBRConverter<T>
     * @param comparator NodeComparators<X> that allows comparison on min/max of objects of type X along different axes
     * @param <X> either T or Node<T>
     * @return Pair of Lists with elements of type X, representing the split of nodes into two
     */
    public <X> Pair<List<X>> rStarSplit (List<X> nodes, int minBranchFactor, int maxBranchFactor,
					 BiFunction<X, MBRConverter<T>, MBR> mbrProvider,
					 NodeComparators<X> comparator) {
	int axis = chooseSplitAxis (nodes, minBranchFactor, maxBranchFactor, mbrProvider, comparator);
	int dims = converter.getDimensions (); // all places that need dimensions should probably use this
	Pair<List<X>> split = chooseSplitIndex (nodes, axis, minBranchFactor, maxBranchFactor, dims, mbrProvider,
						comparator);

	assert split.a ().size () > 0;
	assert split.b ().size () > 0;

	assert split.a ().size () >= minBranchFactor;
	assert split.a ().size () <= maxBranchFactor;

	assert split.b ().size () >= minBranchFactor;
	assert split.b ().size () <= maxBranchFactor;
	return split;
    }

    /**
     * @param nodes List of maxBranchFactor+1 nodes to split into two lists
     * @param minBranchFactor Minimum number of children of a node
     * @param maxBranchFactor Maximum number of children of a node
     * @param mbrProvider Function to calculate an MBR given an object of type X and a MBRConverter<T>
     * @param comparator NodeComparators<X> that allows comparison on min/max of objects of type X along different axes
     * @param <X> either T or Node<T>
     * @return integer representing the chosen axis, corresponding to the index taken by the comparators
     */
    private <X> int chooseSplitAxis (List<X> nodes, int minBranchFactor, int maxBranchFactor,
				     BiFunction<X, MBRConverter<T>, MBR> mbrProvider, NodeComparators<X> comparator) {
	// determine the distribution with smallest sum of margins
	// cycle through distributions in a sequential fashion
	int D = converter.getDimensions ();
	// Assumption in R*-tree paper: we have M+1 nodes, this should be true in all splits
	assert nodes.size () == maxBranchFactor + 1;

	// we do not want to sort the input list, we want to sort a copy
	List<X> nodesCopy = new ArrayList<> (nodes); // redundant copy atm
	//List<MBR> mbrList = nodesCopy.stream().map(n -> mbrProvider.apply(n, converter)).toList();

	double minSumOfMargins = Double.POSITIVE_INFINITY;
	int minSumOfMarginsAxis = -1;

	for (int axis = 0; axis < D; axis++) {
	    // for each axis sort on minimum and maximum in the axis respectively
	    // for each sort choose the distribution into two node sets that have the lowest margin sum
	    // set the split axis to be the axis where this margin sum was the lowest
	    nodesCopy.sort (comparator.getMinComparator (axis));
	    List<MBR> mbrList = nodesCopy.stream ().map (n -> mbrProvider.apply (n, converter)).toList ();
	    double minSortedMarginSum = sumOfDistributionMargins (minBranchFactor, maxBranchFactor, mbrList);

	    nodesCopy.sort (comparator.getMaxComparator (axis));
	    mbrList = nodesCopy.stream ().map (n -> mbrProvider.apply (n, converter)).toList ();
	    double maxSortedMarginSum = sumOfDistributionMargins (minBranchFactor, maxBranchFactor, mbrList);

	    // choose the axis which has the minimum sum of margins (sum over the two sorts)
	    // this is the approach they choose to use in the R*-tree paper
	    double marginSum = minSortedMarginSum + maxSortedMarginSum;
	    if (marginSum < minSumOfMargins) {
		minSumOfMargins = marginSum; // bugfix: forgot to update value here
		minSumOfMarginsAxis = axis;
	    }
	}
	assert minSumOfMarginsAxis != -1;
	return minSumOfMarginsAxis;
    }

    /**
     * @param nodes
     * @param axis
     * @param m
     * @param M
     * @param dimensions
     * @param mbrProvider Function to calculate an MBR given an object of type X and a MBRConverter<T>
     * @param comparator NodeComparators<X> that allows comparison on min/max of objects of type X along different axes
     * @param <X> either T or Node<T>
     * @return
     */
    private <X> Pair<List<X>> chooseSplitIndex (List<X> nodes, int axis, int m, int M, int dimensions,
						BiFunction<X, MBRConverter<T>, MBR> mbrProvider,
						NodeComparators<X> comparator) {

	// it is clear that the overlap-value should be minimized here
	List<X> nodesSortedOnMin = nodes.stream ().sorted (comparator.getMinComparator (axis)).toList ();
	List<MBR> minSorted = nodesSortedOnMin.stream ().map (n -> mbrProvider.apply (n, converter)).toList ();

	List<X> nodesSortedOnMax = nodes.stream ().sorted (comparator.maxInAscOrderComp (axis)).toList ();
	List<MBR> maxSorted = nodesSortedOnMax.stream ().map (n -> mbrProvider.apply (n, converter)).toList ();

	IntDouble indexValMin = splitMinSumOfOverlaps (m, M, minSorted);
	IntDouble indexValMax = splitMinSumOfOverlaps (m, M, maxSorted);

	double overlapMin = indexValMin.d ();
	double overlapMax = indexValMax.d ();

	// index refers to actual split index in the list

	//System.out.println("OverlapMin: " + overlapMin + " OverlapMax: " + overlapMax);

	if (overlapMin < overlapMax) {
	    return nodeSplitPair (indexValMin.i, nodesSortedOnMin);
	} else if (overlapMax < overlapMin) {
	    return nodeSplitPair (indexValMax.i, nodesSortedOnMax);
	} else {
	    // prioritize area-value of split
	    Pair<MBR> minSplitMBRs = getSplitMBRs (indexValMin.i, dimensions, minSorted);
	    Pair<MBR> maxSplitMBRs = getSplitMBRs (indexValMax.i, dimensions, maxSorted);

	    double areaValue1 = getAreaValue (minSplitMBRs.a (), minSplitMBRs.b ());
	    double areaValue2 = getAreaValue (maxSplitMBRs.a (), maxSplitMBRs.b ());

	    if (areaValue1 < areaValue2) {
		return nodeSplitPair (indexValMin.i, nodesSortedOnMin);
	    } else {
		return nodeSplitPair (indexValMax.i, nodesSortedOnMax);
	    }
	}
    }

    private <X> Pair<List<X>> nodeSplitPair (int splitIdx, List<X> nodes) {
	// previous bug: only returning views here caused trouble as lists were assigned to nodes
	// then the nodes could not be added into, as they had been assigned List references without support for add
	return new Pair<> (new ArrayList<> (nodes.subList (0, splitIdx)),
			   new ArrayList<> (nodes.subList (splitIdx, nodes.size ())));
    }

    private Pair<MBR> getSplitMBRs (int splitIdx, int dims, List<MBR> mbrs) {
	MBR first = new SimpleMBR (mbrs.subList (0, splitIdx), dims);
	MBR second = new SimpleMBR (mbrs.subList (splitIdx, mbrs.size ()), dims);
	return new Pair<> (first, second);
    }

    // R*-tree specific
    private double getAreaValue (MBR a, MBR b) {
	return a.getArea () + b.getArea ();
    }

    private double getMarginValue (MBR a, MBR b) {
	return a.margin () + b.margin ();
    }

    private double getOverlapValue (MBR a, MBR b) {
	return a.getIntersectionArea (b);
    }

    private IntDouble splitMinSumOfOverlaps (int m, int M, List<MBR> mbrList) {
	int numDistributions = M - 2 * m + 2; // >= 2
	double minSumOfOverlaps = Double.POSITIVE_INFINITY;
	int minOverlapSplitIdx = -1;
	double minAreaValue = Double.POSITIVE_INFINITY;
	// try to do this in O(d * M)
	// the prefix / suffix arrays are larger than required, but for simplicity
	int nMbrs = mbrList.size ();
	MBR[] prefixes = new MBR[nMbrs];
	MBR[] suffixes = new MBR[nMbrs];

	prefixes[0] = mbrList.get (0);
	for (int i = 1; i < nMbrs; i++) {
	    prefixes[i] = prefixes[i - 1].union (mbrList.get (i));
	}
	suffixes[nMbrs - 1] = mbrList.get (nMbrs - 1);
	for (int i = nMbrs - 2; i >= 0; i--) {
	    suffixes[i] = suffixes[i + 1].union (mbrList.get (i));
	}

	for (int k = 1; k <= numDistributions; k++) {
	    int splitIdx = m - 1 + k;
	    // first group contains (m-1)+k entries
	    // second group contains the rest (M+1 - (m-1+k) = M+2-m-k) >= m+1
	    // O(1) work below
	    MBR firstMbr = prefixes[splitIdx - 1]; // union of mbrs index 0 to splitIdx-1
	    MBR secondMbr = suffixes[splitIdx]; // union of mbrs index splitIdx to mbrs.size

	    double sumOfOverlaps = firstMbr.getIntersectionArea (secondMbr);

	    if (sumOfOverlaps < minSumOfOverlaps) {
		minSumOfOverlaps = sumOfOverlaps;
		minOverlapSplitIdx = splitIdx;
		minAreaValue = getAreaValue (firstMbr, secondMbr);
	    } else if (sumOfOverlaps == minSumOfOverlaps) {
		double areaValueCurrent = getAreaValue (firstMbr, secondMbr);
		if (areaValueCurrent < minAreaValue) {
		    minOverlapSplitIdx = splitIdx;
		    minAreaValue = areaValueCurrent;
		}
	    }
	}
	return new IntDouble (minOverlapSplitIdx, minSumOfOverlaps);
    }

    private double sumOfDistributionMargins (int m, int M, List<MBR> mbrList) {
	int numDistributions = M - 2 * m + 2;
	double totalSumOfMargins = 0;
	// O (d * M)
	int nMbrs = mbrList.size ();
	MBR[] prefixes = new MBR[nMbrs];
	MBR[] suffixes = new MBR[nMbrs];
	prefixes[0] = mbrList.get (0);
	for (int i = 1; i < nMbrs; i++) {
	    prefixes[i] = prefixes[i - 1].union (mbrList.get (i));
	}
	suffixes[nMbrs - 1] = mbrList.get (nMbrs - 1);
	for (int i = nMbrs - 2; i >= 0; i--) {
	    suffixes[i] = suffixes[i + 1].union (mbrList.get (i));
	}

	for (int k = 1; k <= numDistributions; k++) {
	    // first group contains (m-1)+k entries
	    // second group contains the test (M+1 - (m-1+k) = M+2-m-k)
	    int mid = m - 1 + k;
	    MBR firstMbr = prefixes[mid - 1];
	    MBR secondMbr = suffixes[mid];

	    totalSumOfMargins += firstMbr.margin () + secondMbr.margin ();
	}
	return totalSumOfMargins;
    }
}
