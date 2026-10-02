package org.khelekore.prtree;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiFunction;
import java.util.function.Predicate;

// LeafNode has children of type T (data item data type)
class LeafNode<T> extends NodeBase<T, T> {

    public LeafNode (Object[] data) {
	super (data);
    }

    public LeafNode (List<T> data) {
	super (data);
    }

    public LeafNode<T> create (List<T> data) {
	return new LeafNode<> (data);
    }

    // this seems a bit troll
    public Node<T> chooseLeaf (MBR cmpMBR, MBRConverter<T> converter) {
	return this;
    }

    public void chooseLeafPath (MBR cmpMBR, MBRConverter<T> converter, List<Node<T>> path) {
	path.add (this);
    }

    public Node<T> RStarChooseSubtree (MBR cmpMBR, MBRConverter<T> converter) {
	return this;
    }

    public void RStarChooseSubtreePath (MBR cmpMBR, MBRConverter<T> converter, List<Node<T>> path) {
	path.add (this);
    }

    // from overlap definition in R*-tree paper
    public double overlap (T x, MBRConverter<T> converter) {
	double overlap = 0.0;

	SimpleMBR xMbr = new SimpleMBR (x, converter);
	for (int i = 0; i < size (); i++) {
	    if (get (i) != x) {
		overlap += xMbr.getIntersectionArea (getMBR (get (i), converter));
	    }
	}
	return overlap;
    }

    // assumes mbrToInsert isn't already in the node, or results are wrong by definition in R*-tree
    public double calculateOverlapEnlargement (MBR mbrToInsert, MBRConverter<T> converter) {
	double overlap = 0.0;

	SimpleMBR xMbr = new SimpleMBR (mbrToInsert); // temporary mess

	for (int i = 0; i < size (); i++) {
	    overlap += xMbr.getIntersectionArea (getMBR (get (i), converter));
	}
	return overlap;
    }

    public Node<T> findLeaf (T toFind, MBRConverter<T> converter) {
	// return first matching child
	for (int i = 0; i < size (); i++) {
	    if (get (i) == toFind) {
		//System.out.println("findLeaf: Found match in leaf.");
		return this;
	    }
	}
	// null to represent no match
	return null;
    }

    public Node<T> findLeafPath (T toFind, MBRConverter<T> converter, List<Node<T>> path) {
	// return first matching child
	for (int i = 0; i < size (); i++) {
	    if (get (i) == toFind) {
		path.add (0, this);
		return this;
	    }
	}
	// null to represent no match
	return null;
    }

    public BiFunction<T, MBRConverter<T>, MBR> childMbrProvider () {
	return SimpleMBR::new;
    }

    public double distanceFromCenter (T child, MBRConverter<T> converter) {
	return getMBR (converter).centerDistance (new SimpleMBR (child, converter));
    }

    public NodeComparators<T> comparators (MBRConverter<T> converter) {
	return new DataComparators<> (converter);
    }

    public int calculateHeight () {
	return 1;
    }

    public int internalNodeCount () {
	return 0;
    }

    public int leafNodeCount () {
	return 1;
    }

    // Get MBRs in postorder
    public void getMBRs (List<MBR> acc, MBRConverter<T> converter) {
	int size = size ();
	for (int i = 0; i < size; i++) {
	    acc.add (getMBR (get (i), converter));
	}
	acc.add (getMBR (converter));
    }

    public void getLeafNodeMBRs (List<MBR> acc, MBRConverter<T> converter) {
	acc.add (getMBR (converter));
    }

    public void getNodeMBRs (List<MBR> acc, MBRConverter<T> converter) {
	acc.add (getMBR (converter));
    }

    // computing the MBR by creating O(size) MBRs
    public MBR computeMBR (MBRConverter<T> converter) {
	List<T> data = getData ();
	if (data.isEmpty ()) {
	    return null; // probably illegal state to have a node of size 0, but ok.
	}
	int dims = converter.getDimensions ();
	double[] mbrValues = null;
	for (T child : data) {
	    mbrValues = SimpleMBR.unionInto (child, converter, dims, mbrValues);
	}
	return new SimpleMBR (mbrValues);
    }

    public void expand (MBR mbr, Predicate<T> filter, MBRConverter<T> converter, List<T> found,
			List<Node<T>> nodesToExpand) {
	find (mbr, converter, found, filter);
    }

    public void find (MBR mbr, MBRConverter<T> converter, List<T> result, Predicate<T> filter) {
	for (int i = 0, s = size (); i < s; i++) {
	    T t = get (i);
	    if (mbr.intersects (t, converter) && filter.test (t))
		result.add (t);
	}
    }

    public void nnExpand (DistanceCalculator<T> dc, Predicate<T> filter, List<DistanceResult<T>> drs, int maxHits,
			  PriorityQueue<Node<T>> queue, MinDistComparator<T, Node<T>> mdc) {
	for (int i = 0, s = size (); i < s; i++) {
	    T t = get (i);
	    if (filter.test (t)) {
		double dist = dc.distanceTo (t, mdc.p);
		int n = drs.size ();
		if (n < maxHits || dist < drs.get (n - 1).getDistance ()) {
		    add (drs, new DistanceResult<> (t, dist), maxHits);
		}
	    }
	}
    }

    private void add (List<DistanceResult<T>> drs, DistanceResult<T> dr, int maxHits) {
	int n = drs.size ();
	if (n == maxHits)
	    drs.remove (n - 1);
	int pos = Collections.binarySearch (drs, dr, comp);
	if (pos < 0) {
	    // binarySearch return -(pos + 1) for new entries
	    pos = -(pos + 1);
	}
	drs.add (pos, dr);
    }

    private static final Comparator<DistanceResult<?>> comp = Comparator.comparingDouble (DistanceResult::getDistance);
}
