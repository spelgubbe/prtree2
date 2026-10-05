package org.khelekore.prtree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * @param <N> the type of the child entries
 * @param <T> the type of the data entries
 */
abstract class NodeBase<N, T> implements Node<T> {
    private MBR mbr;
    private List<N> data;

    @SuppressWarnings ("unchecked")
    public NodeBase (Object[] data) {
	this.data = new ArrayList<> ();
	// look over if we need to deal with an array constructor.. (we probably do)
	for (Object item : data) {
	    this.data.add ((N) item);
	}

    }

    public NodeBase (List<N> data) {
	this.data = data;
    }

    public abstract NodeBase<N, T> create (List<N> data);

    public int size () {
	return data.size ();
    }

    public N get (int i) {
	return data.get (i);
    }

    public boolean insertItem (N item) {
	return data.add (item);
    }

    @SuppressWarnings ("unchecked")
    public void insertChild (Object child) {
	data.add ((N) child); // This design could be better
    }

    public boolean removeChild (Object ref) {
	for (int i = 0; i < size (); i++) {
	    if (ref == get (i)) {
		data.remove (i);
		return true;
	    }
	}
	return false;
    }

    // TODO: maybe return a Pair here
    public List<Node<T>> split (int minBranchFactor, int maxBranchFactor, MBRConverter<T> converter) {
	// assert basic R-tree properties
	assert minBranchFactor >= 2;
	assert maxBranchFactor > minBranchFactor;
	assert minBranchFactor <= 0.5 * maxBranchFactor;

	return splitNode (data, minBranchFactor, maxBranchFactor, converter);
    }

    public List<Node<T>> rStarSplit (int minBranchFactor, int maxBranchFactor, MBRConverter<T> converter) {
	// maybe an abstract method to get the correct comparator will be needed here
	return splitNodeRStar (data, minBranchFactor, maxBranchFactor, converter, comparators (converter));
    }

    public abstract NodeComparators<N> comparators (MBRConverter<T> converter);

    public MBR getMBR (MBRConverter<T> converter) {
	if (mbr == null)
	    mbr = computeMBR (converter);
	return mbr;
    }

    public void recomputeMBR (MBRConverter<T> converter) {
	mbr = computeMBR (converter);
    }

    public List<N> getData () {
	return data;
    }

    public MBR getMBR (T t, MBRConverter<T> converter) {
	return new SimpleMBR (t, converter);
    }

    private Pair<List<N>> quadraticSplit (List<N> nodes, int minBranchFactor, int maxBranchFactor,
					  MBRConverter<T> converter) {
	NodeSplitter<T> ns = new NodeSplitter<> (converter);
	return ns.quadraticSplit (nodes, minBranchFactor, maxBranchFactor, childMbrProvider ());
    }

    private Pair<List<N>> rStarSplit (List<N> nodes, int minBranchFactor, int maxBranchFactor,
				      MBRConverter<T> converter, NodeComparators<N> comparator) {
	NodeSplitter<T> ns = new NodeSplitter<> (converter);
	return ns.rStarSplit (nodes, minBranchFactor, maxBranchFactor, childMbrProvider (), comparator);
    }

    public List<Node<T>> splitNode (List<N> nodes, int minBranchFactor, int maxBranchFactor,
				    MBRConverter<T> converter) {
	List<Node<T>> res = new ArrayList<> ();

	Pair<List<N>> split = quadraticSplit (nodes, minBranchFactor, maxBranchFactor, converter);

	List<N> firstList = split.a ();
	List<N> secondList = split.b ();

	assert (firstList.size () + secondList.size () == nodes.size ());

	Node<T> first = create (firstList);
	Node<T> second = create (secondList);

	if (firstList.isEmpty () || secondList.isEmpty ()) {
	    throw new IllegalStateException ("At least one empty node produced by split");
	}

	res.add (first);
	res.add (second);

	return res;
    }

    public List<Node<T>> splitNodeRStar (List<N> nodes, int minBranchFactor, int maxBranchFactor,
					 MBRConverter<T> converter, NodeComparators<N> comparator) {
	List<Node<T>> res = new ArrayList<> ();

	Pair<List<N>> split = rStarSplit (nodes, minBranchFactor, maxBranchFactor, converter, comparator);
	List<N> firstList = split.a ();
	List<N> secondList = split.b ();

	assert (firstList.size () + secondList.size () == nodes.size ());

	Node<T> first = create (firstList);
	Node<T> second = create (secondList);

	if (firstList.isEmpty () || secondList.isEmpty ()) {
	    throw new IllegalStateException ("At least one empty node produced by split");
	}

	res.add (first);
	res.add (second);

	return res;
    }

    public abstract MBR computeMBR (MBRConverter<T> converter);

    //public abstract MBR childMbrProvider(N child, MBRConverter<T> converter);

    public abstract BiFunction<N, MBRConverter<T>, MBR> childMbrProvider ();

    public double calculateAreaEnlargement (MBR mbrToInsert, MBRConverter<T> converter) {
	MBR iNodeMBR = getMBR (converter);
	// idea: union the MBRs, then check the enlargement by area diff
	MBR unionMBR = iNodeMBR.union (mbrToInsert);

	double oldArea = iNodeMBR.getArea ();
	double newArea = unionMBR.getArea ();

	return newArea - oldArea;
    }

    public abstract double distanceFromCenter (N child, MBRConverter<T> converter);

    // sort in desc order by distance from center of node MBR center
    public Comparator<N> distFromCenterComparator (MBRConverter<T> converter) {
	return (a, b) -> Double.compare (distanceFromCenter (b, converter), distanceFromCenter (a, converter));
    }

    // R*-tree O(MlogM) finds out what nodes to reinsert instead of performing a split
    public List<N> getReinsertionCandidates (float p, int minBranchFactor, int maxBranchFactor,
					     MBRConverter<T> converter) {
	// Assumption: node contains M+1 children (or this shouldn't have been called)
	//System.out.printf("getReinsertionCandidates: size =  %d\n", size());
	assert size () == maxBranchFactor + 1 && p > 0.0 && p <= 0.5;

	// ceil the number so that it may never be 0 (or the problem of an overfull node isn't solved)
	int reinsertCandidates = (int) Math.ceil (p * size ());
	// make sure that the number of reinserted nodes don't cause this node to be underfull
	// in other words need to leave minBranchFactor nodes at least
	int maxCandidates = (maxBranchFactor + 1) - minBranchFactor;
	// +1 because we know that only a node with max + 1 children will have children reinserted

	reinsertCandidates = Math.min (reinsertCandidates, maxCandidates);

	// sort children by distance from center to the full node MBR

	List<N> nodesCopy = new ArrayList<> (getData ());
	nodesCopy.sort (distFromCenterComparator (converter));

	return nodesCopy.subList (0, reinsertCandidates);
    }

    // Finds nodes for reinsertion and then removes them from this node
    // returns the nodes flagged for reinsertion (so they have to be reinserted)
    public List<N> getAndRemoveReinsertionCandidates (float p, int minBranchFactor, int maxBranchFactor,
						      MBRConverter<T> converter) {
	List<N> reinsertList = getReinsertionCandidates (p, minBranchFactor, maxBranchFactor, converter);
	Set<N> toRemove = IdentityHashSet.fromCollection (reinsertList);
	bulkRemove (toRemove, converter);
	return reinsertList;
    }

    // O(M) bulk remove, in case it is needed
    public void bulkRemove (Set<N> removeSet, MBRConverter<T> converter) {
	//System.out.printf("bulkRemove: starting with size = %d\n", size());
	int size = size ();
	List<N> children = new ArrayList<> (size);
	for (int i = 0; i < size; i++) {
	    N child = get (i);
	    // this needs to be a reference equals check (not equals)
	    // so the removeSet can't be a HashSet
	    if (!removeSet.contains (child)) {
		children.add (child);
	    }
	}
	// reassign data to all children not in remove set
	data = children;
	//System.out.printf("bulkRemove: ending with size = %d\n", size());
	// recompute MBR as children may have been removed
	recomputeMBR (converter);
    }
}
