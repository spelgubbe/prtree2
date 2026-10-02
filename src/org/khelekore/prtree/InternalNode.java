package org.khelekore.prtree;

import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiFunction;
import java.util.function.Predicate;

// Internal node has children of type Node<T> (same type as itself)
// Leafs have children of type T, which are the data item data type
class InternalNode<T> extends NodeBase<Node<T>, T> {
    public InternalNode (Object[] data) {
	super (data);
    }

    public InternalNode (List<Node<T>> data) {
	super (data);
    }

    public InternalNode<T> create (List<Node<T>> data) {
	return new InternalNode<> (data);
    }

    @Override
    public MBR computeMBR (MBRConverter<T> converter) {
	List<Node<T>> data = getData ();
	if (data.isEmpty ()) {
	    return null; // probably illegal state to have a node of size 0, but ok.
	}
	int dims = converter.getDimensions ();
	double[] mbrValues = null;
	for (Node<T> child : data) {
	    MBR childMbr = child.getMBR (converter);
	    mbrValues = SimpleMBR.unionInto (childMbr, dims, mbrValues);
	}
	return new SimpleMBR (mbrValues);
    }

    private int chooseLeafLocalGuttman (MBR cmpMBR, MBRConverter<T> converter) {
	int numNodes = size ();
	double minEnlargement = Double.POSITIVE_INFINITY;
	int minEnlargementIdx = -1;
	for (int i = 0; i < numNodes; i++) {
	    Node<T> n = get (i);
	    double enlargement = n.calculateAreaEnlargement (cmpMBR, converter);
	    // choose node that leads to smallest enlargement
	    if (enlargement < minEnlargement) {
		minEnlargement = enlargement;
		minEnlargementIdx = i;
	    } else if (enlargement == minEnlargement) {

		// resolve ties by smallest area
		double prevArea = get (minEnlargementIdx).getMBR (converter).getArea ();
		double newArea = get (i).getMBR (converter).getArea ();

		if (newArea < prevArea) {
		    minEnlargement = enlargement;
		    minEnlargementIdx = i;
		}
	    }
	}
	return minEnlargementIdx;
    }

    private double calculateChildOverlap (MBR childMBR, int childIdx, MBRConverter<T> converter) {
	double overlap = 0.0;

	for (int i = 0; i < size (); i++) {
	    if (i != childIdx) {
		overlap += childMBR.getIntersectionArea (get (i).getMBR (converter));
	    }
	}
	return overlap;
    }

    // R*-tree takes overlap between entries into account when choosing a leaf
    private int chooseLeafRStar (MBR cmpMBR, MBRConverter<T> converter) {
	int numNodes = size ();
	double optNodeAreaEnlargement = Double.POSITIVE_INFINITY;
	double optNodeOverlapEnlargement = Double.POSITIVE_INFINITY;
	int optimalNodeIdx = -1;
	// O(M^2)
	for (int i = 0; i < numNodes; i++) {
	    Node<T> n = get (i);
	    // find how much more overlap between leafs is added by inserting the MBR
	    // linear cost in number of children
	    MBR currentChildMBR = n.getMBR (converter);
	    double currentOverlap = calculateChildOverlap (currentChildMBR, i, converter);
	    MBR newChildMBR = currentChildMBR.union (cmpMBR);
	    double newOverlap = calculateChildOverlap (newChildMBR, i, converter);

	    double overlapEnlargement = newOverlap - currentOverlap;

	    // find how much the area increases if the MBR is included in n's MBR

	    double areaEnlargement = n.calculateAreaEnlargement (cmpMBR, converter);

	    // priority is: smallest overlapEnlargement -> smallest areaEnlargement -> smallest area
	    if (overlapEnlargement < optNodeOverlapEnlargement) {
		optNodeOverlapEnlargement = overlapEnlargement;
		optNodeAreaEnlargement = areaEnlargement;
		optimalNodeIdx = i;
	    } else if (overlapEnlargement == optNodeOverlapEnlargement) {
		// choose node that leads to smallest areaEnlargement
		if (areaEnlargement < optNodeAreaEnlargement) {
		    optNodeAreaEnlargement = areaEnlargement;
		    optimalNodeIdx = i;
		} else if (areaEnlargement == optNodeAreaEnlargement) {
		    // resolve ties by smallest area
		    double prevArea = get (optimalNodeIdx).getMBR (converter).getArea ();
		    double newArea = get (i).getMBR (converter).getArea ();

		    if (newArea < prevArea) {
			optimalNodeIdx = i;
		    }
		}
	    }
	}
	return optimalNodeIdx;
    }

    private int chooseSubtreeLocalRStar (MBR cmpMBR, MBRConverter<T> converter) {
	// check if child is a leaf node then calcualte overlap etc..
	if (getData ().get (0) instanceof LeafNode<T>) {
	    // temporary mess
	    return chooseLeafRStar (cmpMBR, converter);
	} else {
	    return chooseLeafLocalGuttman (cmpMBR, converter);
	}
    }

    // assumes mbrToInsert isn't already in the node, or results are wrong by definition in R*-tree
    // not meant to be used in internal nodes (not in R*-tree at least)
    public double calculateOverlapEnlargement (MBR mbrToInsert, MBRConverter<T> converter) {
	double overlap = 0.0;

	SimpleMBR xMbr = new SimpleMBR (mbrToInsert); // temporary mess

	for (int i = 0; i < size (); i++) {
	    overlap += xMbr.getIntersectionArea (get (i).getMBR (converter));
	}
	return overlap;
    }

    // ChooseLeaf for internal node
    public Node<T> chooseLeaf (MBR cmpMBR, MBRConverter<T> converter) {
	int minEnlargementIdx = chooseLeafLocalGuttman (cmpMBR, converter);
	if (minEnlargementIdx == -1) {
	    System.err.println ("Empty node found, chooseLeaf found nothing");
	    return null;
	}
	Node<T> minNode = get (minEnlargementIdx);
	return minNode.chooseLeaf (cmpMBR, converter);
    }

    public void chooseLeafPath (MBR cmpMBR, MBRConverter<T> converter, List<Node<T>> path) {
	int minEnlargementIdx = chooseLeafLocalGuttman (cmpMBR, converter);
	if (minEnlargementIdx == -1) {
	    System.err.println ("Empty node found, chooseLeaf found nothing");
	    return;
	}
	Node<T> minNode = get (minEnlargementIdx);
	path.add (this); // bug before: minNode was added
	minNode.chooseLeafPath (cmpMBR, converter, path);
    }

    public Node<T> RStarChooseSubtree (MBR cmpMBR, MBRConverter<T> converter) {
	int optNodeIdx = chooseSubtreeLocalRStar (cmpMBR, converter);
	if (optNodeIdx == -1) {
	    System.err.println ("Empty node found, chooseLeaf found nothing");
	    return null;
	}
	Node<T> optNode = get (optNodeIdx);
	return optNode.RStarChooseSubtree (cmpMBR, converter);
    }

    public void RStarChooseSubtreePath (MBR cmpMBR, MBRConverter<T> converter, List<Node<T>> path) {
	int optNodeIdx = chooseSubtreeLocalRStar (cmpMBR, converter);
	if (optNodeIdx == -1) {
	    System.err.println ("Empty node found, chooseLeaf found nothing");
	    return;
	}
	Node<T> optNode = get (optNodeIdx);
	path.add (this);
	optNode.RStarChooseSubtreePath (cmpMBR, converter, path);
    }

    public Node<T> findLeaf (T toFind, MBRConverter<T> converter) {
	// we need to search all nodes that may contain toFind (this is linear in N)
	// MBR doesnt change, it can be passed instead of recalculated
	MBR toFindMBR = getMBR (toFind, converter);

	// for each child whose MBR overlaps the MBR to find, we do a recursive call
	for (int i = 0; i < size (); i++) {
	    Node<T> child = get (i);
	    MBR childMBR = child.getMBR (converter);

	    // thought results needed to be accumulated
	    // seems like dfs-ing works as any non-null is a match
	    Node<T> nodeWithNeedle = null;
	    if (toFindMBR.intersects (childMBR)) {
		//System.out.println("Found child intersecting MBR of sought after object");
		nodeWithNeedle = child.findLeaf (toFind, converter);
	    }
	    if (nodeWithNeedle != null) {
		//System.out.println("Returning match from internal node.");
		return nodeWithNeedle;
	    }
	}
	// null to represent no match
	return null;
    }

    public Node<T> findLeafPath (T toFind, MBRConverter<T> converter, List<Node<T>> path) {
	// we need to search all nodes that may contain toFind
	MBR toFindMBR = getMBR (toFind, converter);

	// for each child whose MBR overlaps the MBR to find, we do a recursive call
	// may also use the approach where we expand the search in a loop
	for (int i = 0; i < size (); i++) {
	    Node<T> child = get (i);
	    MBR childMBR = child.getMBR (converter);

	    // thought results needed to be accumulated
	    // seems like dfs-ing works as any non-null is a match
	    Node<T> nodeWithNeedle = null;
	    if (toFindMBR.intersects (childMBR)) {
		nodeWithNeedle = child.findLeafPath (toFind, converter, path);
	    }
	    if (nodeWithNeedle != null) {
		// here we know we found toFind
		path.add (0, this);
		return nodeWithNeedle;
	    }
	}
	// null to represent no match
	return null;
    }

    public BiFunction<Node<T>, MBRConverter<T>, MBR> childMbrProvider () {
	return Node::getMBR;
    }

    public double distanceFromCenter (Node<T> child, MBRConverter<T> converter) {
	return getMBR (converter).centerDistance (child.getMBR (converter));
    }

    // Used for R*-tree sorting children by their centers' distance from the node's MBR center
    // in descending order
    public NodeComparators<Node<T>> comparators (MBRConverter<T> converter) {
	return new InternalNodeComparators<> (converter);
    }

    public int calculateHeight () {
	int size = size ();
	int[] depths = new int[size];
	for (int i = 0; i < size; i++) {
	    depths[i] = get (i).calculateHeight ();

	    if (i > 0) {
		assert depths[i] == depths[i - 1];
	    }
	}

	return depths[0] + 1;
    }

    public int internalNodeCount () {
	int size = size ();
	int count = 1;
	for (int i = 0; i < size; i++) {
	    count += get (i).internalNodeCount ();
	}
	return count;
    }

    public int leafNodeCount () {
	int size = size ();
	int count = 0;
	for (int i = 0; i < size; i++) {
	    count += get (i).leafNodeCount ();
	}
	return count;
    }

    // Get MBRs in postorder
    public void getMBRs (List<MBR> acc, MBRConverter<T> converter) {
	int size = size ();
	for (int i = 0; i < size; i++) {
	    get (i).getMBRs (acc, converter);
	}
	acc.add (getMBR (converter));
    }

    public void getLeafNodeMBRs (List<MBR> acc, MBRConverter<T> converter) {
	int size = size ();
	for (int i = 0; i < size; i++) {
	    get (i).getLeafNodeMBRs (acc, converter);
	}
    }

    public void getNodeMBRs (List<MBR> acc, MBRConverter<T> converter) {
	int size = size ();
	for (int i = 0; i < size; i++) {
	    get (i).getNodeMBRs (acc, converter);
	}
    }

    public void expand (MBR mbr, Predicate<T> filter, MBRConverter<T> converter, List<T> found,
			List<Node<T>> nodesToExpand) {
	for (int i = 0, s = size (); i < s; i++) {
	    Node<T> n = get (i);
	    if (mbr.intersects (n.getMBR (converter)))
		nodesToExpand.add (n);
	}
    }

    public void find (MBR mbr, MBRConverter<T> converter, List<T> result, Predicate<T> filter) {
	for (int i = 0, s = size (); i < s; i++) {
	    Node<T> n = get (i);
	    if (mbr.intersects (n.getMBR (converter)))
		n.find (mbr, converter, result, filter);
	}
    }

    public void nnExpand (DistanceCalculator<T> dc, Predicate<T> filter, List<DistanceResult<T>> drs, int maxHits,
			  PriorityQueue<Node<T>> queue, MinDistComparator<T, Node<T>> mdc) {
	int s = size ();
	for (int i = 0; i < s; i++) {
	    Node<T> n = get (i);
	    MBR mbr = n.getMBR (mdc.converter);
	    double minDist = MinDist.get (mbr, mdc.p);
	    int t = drs.size ();
	    // drs is sorted so we can check only the last entry
	    if (t < maxHits || minDist <= Math.pow (drs.get (t - 1).getDistance (), 2))
		queue.add (n);
	}
    }
}
