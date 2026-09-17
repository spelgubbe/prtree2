package org.khelekore.prtree;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * This class is a dynamic data structure that may be a PR-tree (if {@link #load} or similar constructed the tree).
 * It may also be modified using the insert, delete and update methods.
 * <p>
 * The primary use case for this class is to perform fast spatial queries, more specifically rectangle-rectangle
 * intersection queries and point-rectangle intersection queries. A PRTree built using {@link #load} is guaranteed
 * to have optimal query time for rectangle-rectangle intersection queries. The nearest-neighbor queries are probably
 * optimal too.
 * <p>
 * This tree may be modified using the insert, delete and update methods, but there is no guarantee that queries
 * are optimal after modifications (it may, however, be likely).
 * <p>
 * This tree is thread-safe by default, meaning you may insert, delete and update from any thread at any time,
 * while performing queries like {@link #nearestNeighbour} or {@link #find}. Thread-safety may be turned off by calling
 * {@link #setConcurrencyPolicy}. Thread-safety is implemented with a {@link ReentrantReadWriteLock}. This allows
 * multiple threads to perform queries simultaneously but only allows one thread at a time to make modifications
 * (modifications being {@link #insert}, {@link #delete} or {@link #update} calls).
 *
 * @param <T> the data type stored in the PRTree
 */
public class PRTree<T> {

    private ConcurrencyPolicy concurrencyPolicy = ConcurrencyPolicy.MULTI_THREAD_SAFE;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock (false);
    // count modifications in order to realize when iterators are invalid
    private int modificationCount = 0;

    private final MBRConverter<T> converter;
    /**
     * The upper bound of the branch factor. The largest number of children allowed for a node.
     * This upper bound is respected in all cases in this class.
     */
    private final int branchFactor;
    /**
     * Lower bound of the branch factor. Simply the smallest number of children allowed for a node.
     * There is no guarantee that this lower bound is respected after having built a PR-tree.
     */
    private int minBranchFactor;

    private Node<T> root;
    private int numLeafs;
    private int height;

    // public data for debugging
    private int numRootSplitsCausedByLastOperation = 0;
    private int numRootShrinksCausedByLastOperation = 0;

    private TreeUpdater<T> updater;

    public enum UpdatePolicy {
	RStarTree, RTree
    }

    /**
     * Enums that decide how concurrency is supported (or not)
     */
    public enum ConcurrencyPolicy {
	/**
	 * Using this policy, the tree will provide no guarantees for concurrent access.
	 */
	SINGLE_THREAD_ONLY_UNSAFE,
	/**
	 * Using this policy, the tree will allow concurrent reads and sequential writes, but never both at the same
	 * time. An unfair reentrant lock is used (a fair lock generally has horrendous performance).
	 */
	MULTI_THREAD_SAFE
    }

    /**
     * Create a new PRTree using the specified branch factor.
     * @param converter the MBRConverter to use for this tree
     * @param branchFactor the number of child nodes for each internal node.
     */
    public PRTree (MBRConverter<T> converter, int branchFactor) {
	this.converter = converter;
	this.branchFactor = branchFactor;
	this.minBranchFactor = branchFactor / 4;
	root = new LeafNode<> (new Object[0]); // new: Initialize root always to an empty leaf node
	height = 1;
	updater = new RStarTreeUpdater ();

    }

    public PRTree (MBRConverter<T> converter, int branchFactor, int minBranchFactor) {
	this (converter, branchFactor);
	this.minBranchFactor = minBranchFactor;
    }

    public PRTree (MBRConverter<T> converter, int branchFactor, int minBranchFactor, UpdatePolicy updatePolicy) {
	this (converter, branchFactor, minBranchFactor);
	if (updatePolicy == UpdatePolicy.RTree) {
	    updater = new RTreeUpdater ();
	} else {
	    updater = new RStarTreeUpdater ();
	}
    }

    public void setConcurrencyPolicy (ConcurrencyPolicy c) {
	// don't change concurrency policy while concurrent reads/writes are happening
	write (() -> concurrencyPolicy = c);
    }

    protected void assertAllLeavesAreOnTheSameLevel () {
	root.calculateHeight ();
    }

    protected int getNumRootSplitsCausedByLastOperation () {
	return numRootSplitsCausedByLastOperation;
    }

    protected int getNumRootShrinksCausedByLastOperation () {
	return numRootShrinksCausedByLastOperation;
    }

    /**
     * Bulk load data into this tree.
     *
     * Create the leaf nodes that each hold (up to) branchFactor data entries. Then use the leaf nodes as data until we
     * can fit all nodes into the root node.
     * @param data the collection of data to store in the tree.
     */
    public void loadOriginal (Collection<? extends T> data) {
	numLeafs = data.size ();
	LeafBuilder lb = new LeafBuilder (converter.getDimensions (), branchFactor);

	List<LeafNode<T>> leafNodes = new ArrayList<> (estimateSize (numLeafs));

	lb.buildLeafs (data, new DataComparators<> (converter), LeafNode::new, leafNodes);

	height = 1;
	List<? extends Node<T>> nodes = leafNodes;
	while (nodes.size () > branchFactor) {
	    height++;
	    List<InternalNode<T>> internalNodes = new ArrayList<> (estimateSize (nodes.size ()));
	    lb.buildLeafs (nodes, new InternalNodeComparators<> (converter), InternalNode::new, internalNodes);
	    nodes = internalNodes;
	}
	setRoot (nodes);
    }

    private void loadParallel2 (Collection<? extends T> data) {
	numLeafs = data.size ();
	NodeComparators<T> dataComp = new DataComparators<> (converter);
	NodeComparators<Node<T>> internalComp = new InternalNodeComparators<> (converter);
	PseudoPRTreeBuilder<T, LeafNode<T>> leafBuilder = new PseudoPRTreeBuilder<> (dataComp, branchFactor,
										     converter.getDimensions ());
	PseudoPRTreeBuilder<Node<T>, InternalNode<T>> internalBuilder = new PseudoPRTreeBuilder<> (internalComp,
												   branchFactor,
												   converter.getDimensions ());

	List<LeafNode<T>> leafNodes = new ArrayList<> (estimateSize (numLeafs));

	leafBuilder.pprBuildParallelWrapper (data, LeafNode::new, leafNodes);

	height = 1;
	List<? extends Node<T>> nodes = leafNodes;
	while (nodes.size () > branchFactor) {
	    height++;
	    List<InternalNode<T>> internalNodes = new ArrayList<> (estimateSize (nodes.size ()));
	    internalBuilder.pprBuildParallelWrapper (nodes, InternalNode::new, internalNodes);

	    nodes = internalNodes;
	}
	setRoot (nodes);
    }

    private void buildPRTree (Collection<? extends T> data,
			      Function<Collection<? extends T>, List<LeafNode<T>>> leafFunc,
			      Function<List<? extends Node<T>>, List<InternalNode<T>>> nodeFunc) {
	numLeafs = data.size ();
	List<LeafNode<T>> leafNodes = leafFunc.apply (data);
	height = 1;
	List<? extends Node<T>> nodes = leafNodes;
	while (nodes.size () > branchFactor) {
	    height++;
	    nodes = nodeFunc.apply (nodes);
	}
	setRoot (nodes);
    }

    private void buildPRTreeR (Collection<? extends T> data,
			       Function<Collection<? extends T>, List<LeafNode<T>>> leafFunc,
			       Function<List<? extends Node<T>>, List<InternalNode<T>>> nodeFunc) {
	numLeafs = data.size ();
	height = 1;
	setRoot (buildPRTreeR (leafFunc.apply (data), nodeFunc));
    }

    private List<? extends Node<T>> buildPRTreeR (List<? extends Node<T>> nodes,
						  Function<List<? extends Node<T>>, List<InternalNode<T>>> nodeFunc) {
	if (nodes.size () > branchFactor) {
	    height++;
	    nodes = nodeFunc.apply (nodes);
	    return buildPRTreeR (nodes, nodeFunc);
	}
	return nodes;
    }

    private void buildPRTree (Collection<? extends T> data,
			      BiConsumer<Collection<? extends T>, List<LeafNode<T>>> leafFunc,
			      BiConsumer<List<? extends Node<T>>, List<InternalNode<T>>> nodeFunc) {
	numLeafs = data.size ();
	List<LeafNode<T>> leafNodes = new ArrayList<> (estimateSize (data.size ()));
	leafFunc.accept (data, leafNodes);
	height = 1;
	List<? extends Node<T>> nodes = leafNodes;
	while (nodes.size () > branchFactor) {
	    height++;
	    List<InternalNode<T>> internalNodes = new ArrayList<> (estimateSize (nodes.size ()));
	    nodeFunc.accept (nodes, internalNodes);
	    nodes = internalNodes;
	}
	setRoot (nodes);
    }

    private void loadSerial (Collection<? extends T> data) {
	numLeafs = data.size ();
	int dims = converter.getDimensions ();
	NodeComparators<T> dataComp = new DataComparators<> (converter);
	NodeComparators<Node<T>> internalComp = new InternalNodeComparators<> (converter);
	PseudoPRTreeBuilder<T, LeafNode<T>> leafBuilder = new PseudoPRTreeBuilder<> (dataComp, branchFactor, dims);
	PseudoPRTreeBuilder<Node<T>, InternalNode<T>> internalBuilder = new PseudoPRTreeBuilder<> (internalComp,
												   branchFactor, dims);

	List<LeafNode<T>> leafNodes = new ArrayList<> (estimateSize (numLeafs));
	// place leafs in leafNodes list
	// bottom-up: start with creating actual leaves
	leafBuilder.pprListBuild (data, LeafNode::new, leafNodes);

	height = 1;
	List<? extends Node<T>> nodes = leafNodes;
	while (nodes.size () > branchFactor) {
	    height++;
	    List<InternalNode<T>> internalNodes = new ArrayList<> (estimateSize (nodes.size ()));
	    internalBuilder.pprListBuild (nodes, InternalNode::new, internalNodes);

	    nodes = internalNodes;
	}
	setRoot (nodes);
    }

    /**
     * Bulk load data into this tree.
     *
     * Create the leaf nodes that each hold (up to) branchFactor data entries. Then use the leaf nodes as data until we
     * can fit all nodes into the root node.
     * @param data the collection of data to store in the tree.
     */
    public void load (Collection<? extends T> data) {
	numLeafs = data.size ();

	MBRValueExtractor<T> dataExtractor = new DataExtractor<> (converter);
	MBRValueExtractor<Node<T>> nodeExtractor = new NodeExtractor<> (converter);

	PseudoPRTreeBuilder<T, LeafNode<T>> leafBuilder = new PseudoPRTreeBuilder<> (branchFactor,
										     converter.getDimensions ());
	PseudoPRTreeBuilder<Node<T>, InternalNode<T>> internalBuilder = new PseudoPRTreeBuilder<> (branchFactor,
												   converter.getDimensions ());

	List<LeafNode<T>> leafNodes = new ArrayList<> (estimateSize (numLeafs));
	// place leafs in leafNodes list
	// bottom-up: start with creating actual leaves
	leafBuilder.pprPrimitiveBuild (data, LeafNode::new, leafNodes, dataExtractor);

	height = 1;
	List<? extends Node<T>> nodes = leafNodes;
	while (nodes.size () > branchFactor) {
	    height++;
	    List<InternalNode<T>> internalNodes = new ArrayList<> (estimateSize (nodes.size ()));
	    internalBuilder.pprPrimitiveBuild (nodes, InternalNode::new, internalNodes, nodeExtractor);

	    nodes = internalNodes;
	}
	setRoot (nodes);
    }

    /**
     * Bulk load data into this tree in parallel.
     */
    public void loadParallel (Collection<? extends T> data) {
	numLeafs = data.size ();

	MBRValueExtractor<T> dataExtractor = new DataExtractor<> (converter);
	MBRValueExtractor<Node<T>> nodeExtractor = new NodeExtractor<> (converter);

	PseudoPRTreeBuilder<T, LeafNode<T>> leafBuilder = new PseudoPRTreeBuilder<> (branchFactor,
										     converter.getDimensions ());
	PseudoPRTreeBuilder<Node<T>, InternalNode<T>> internalBuilder = new PseudoPRTreeBuilder<> (branchFactor,
												   converter.getDimensions ());

	List<LeafNode<T>> leafNodes = new ArrayList<> (estimateSize (numLeafs));

	leafBuilder.pprPrimitiveParallelBuildWrapper (data, LeafNode::new, leafNodes, dataExtractor);

	height = 1;
	List<? extends Node<T>> nodes = leafNodes;
	while (nodes.size () > branchFactor) {
	    height++;
	    List<InternalNode<T>> internalNodes = new ArrayList<> (estimateSize (nodes.size ()));
	    internalBuilder.pprPrimitiveParallelBuildWrapper (nodes, InternalNode::new, internalNodes, nodeExtractor);

	    nodes = internalNodes;
	}
	setRoot (nodes);
    }

    private int estimateSize (int dataSize) {
	return (int) (1.0 / (branchFactor - 1) * dataSize);
    }

    private <N extends Node<T>> void setRoot (List<N> nodes) {
	if (nodes.size () == 0)
	    //root = new InternalNode<> (new Object[0]);
	    // empty root was changed to be a single leaf node instead of internal node
	    // in order for insert to work
	    root = new LeafNode<> (new Object[0]);
	else if (nodes.size () == 1) {
	    root = nodes.get (0);
	} else {
	    height++;
	    root = new InternalNode<> (nodes.toArray ());
	}
    }

    /**
     * Get a 2 dimensional minimum bounding rectangle of the data stored in this tree.
     * @return the MBR of the whole PRTree
     */
    public MBR2D getMBR2D () {
	MBR mbr = getMBR ();
	if (mbr == null)
	    return null;
	return new SimpleMBR2D (mbr.getMin (0), mbr.getMin (1), mbr.getMax (0), mbr.getMax (1));
    }

    /**
     * Get an N dimensional minimum bounding box of the data stored in this tree.
     * @return the MBR of the whole PRTree
     */
    public MBR getMBR () {
	return root.getMBR (converter);
    }

    /**
     * Get the number of data leafs in this tree.
     * @return the total number of leafs in this tree
     */
    public int getNumberOfLeaves () {
	return numLeafs;
    }

    /**
     * Check if this tree is empty
     * @return true if the number of leafs is 0, false otherwise
     */
    public boolean isEmpty () {
	return numLeafs == 0;
    }

    /**
     * Get the height of this tree.
     * @return the total height of this tree
     */
    public int getHeight () {
	return height;
    }

    /**
     * Insert an element into the tree.
     * @param x The element to insert.
     */
    public void insert (T x) {
	numRootSplitsCausedByLastOperation = 0;
	numRootShrinksCausedByLastOperation = 0;
	write (() -> updater.insert (x));
    }

    // bulk-insert with one acquisition of the lock
    private void insert (Collection<T> xs) {
	write (() -> xs.forEach (x -> {
	    numRootSplitsCausedByLastOperation = 0;
	    numRootShrinksCausedByLastOperation = 0;
	    updater.insert (x);
	}));
    }

    /**
     * Delete an element from the tree. Returns true if the element is found and deleted.
     * @param x The element to delete.
     * @return True if the element was successfully deleted.
     */
    public boolean delete (T x) {
	numRootSplitsCausedByLastOperation = 0;
	numRootShrinksCausedByLastOperation = 0;
	return write (() -> updater.delete (x));
    }

    // bulk-delete with one acquisition of the lock
    private void delete (Collection<T> xs) {
	write (() -> xs.forEach (x -> {
	    numRootSplitsCausedByLastOperation = 0;
	    numRootShrinksCausedByLastOperation = 0;
	    updater.delete (x);
	}));
    }

    /**
     * Find an element in the tree and replace it with another element.
     * @param toReplace Element to replace.
     * @param newValue Element to put in the replaced element's place.
     * @return True if the element to replace is found and deleted.
     */
    public boolean update (T toReplace, T newValue) {
	numRootSplitsCausedByLastOperation = 0;
	numRootShrinksCausedByLastOperation = 0;
	return write (() -> updater.update (toReplace, newValue));
    }

    private int getDepthFromNodeHeight (int nodeHeight) {
	int depth = getHeight () - nodeHeight;
	assert depth >= 0;
	return depth;
    }

    private void handleRootShrink () {
	if (root.size () == 1 && root instanceof InternalNode) {
	    int prevHeight = getHeight ();
	    // if root is a leaf, then it's OK to have any size
	    // ((LeafNode<T>) root).getData()
	    // if root is an internal node, then if it has only 1 child, it's too small
	    // then make the child the new root
	    setRoot (((InternalNode<T>) root).getData ()); // big bad cast
	    height--; // the tree has now shrunk
	    numRootShrinksCausedByLastOperation++;
	    if (prevHeight == getHeight ()) {
		System.out.println ("Root shrink did not reduce tree height!!");
		assert false;
	    }
	}
    }


    // for testing
    protected Node<T> chooseLeaf (double xmin, double ymin, double xmax, double ymax) {
	// SimpleMBR takes min, max, min, max for each dimension
	MBR insertMBR = new SimpleMBR (xmin, xmax, ymin, ymax);
	return root.chooseLeaf (insertMBR, converter);
    }

    // for testing
    protected Node<T> chooseLeaf (T x) {
	MBR xMBR = root.getMBR (x, converter); // root only used for access to the method ... :|
	return root.chooseLeaf (xMBR, converter);
    }

    private List<Node<T>> chooseNodePath (Node<T> x, int nodeHeight) {
	MBR xMBR = x.getMBR (converter);
	List<Node<T>> path = new ArrayList<> ();
	int depth = getDepthFromNodeHeight (nodeHeight); // should test whether off by 1 or not here
	// seems this repo counts LeafNode to have height 1 (because it has children T:s)
	// with this logic it's off by one
	root.chooseLeafPath (xMBR, converter, path);
	assert path.size () == getHeight ();
	return path.subList (0, depth);
    }

    private List<Node<T>> chooseLeafPath (T x) {
	MBR xMBR = root.getMBR (x, converter); // root only used for access to the method ... :|
	List<Node<T>> path = new ArrayList<> ();
	root.chooseLeafPath (xMBR, converter, path);
	assert path.size () == getHeight ();
	return path;
    }

    // for testing
    protected Node<T> findLeaf (T toFind) {
	return root.findLeaf (toFind, converter);
    }

    private List<Node<T>> findLeafPath (T toFind) {
	List<Node<T>> path = new ArrayList<> ();
	Node<T> leaf = root.findLeafPath (toFind, converter, path);

	// some prints here in case of insane tree
	if (leaf == null) {
	    System.out.println ("No valid path to leaf found in search of " + toFind);
	}
	//System.out.println("FindLeafPath: leaf is of type " + leaf.getClass());

	if (!path.contains (leaf)) {
	    System.out.println ("FindLeafPath: returning a path without containing the final leaf");
	}

	return path;
    }

    private void read (Runnable r) {
	read (() -> {
	    r.run ();
	    return null;
	});
    }

    private <X> X read (Supplier<X> r) {
	if (concurrencyPolicy == ConcurrencyPolicy.SINGLE_THREAD_ONLY_UNSAFE)
	    return r.get ();

	lock.readLock ().lock ();
	try {
	    return r.get ();
	} finally {
	    lock.readLock ().unlock ();
	}
    }

    private void write (Runnable w) {
	write (() -> {
	    w.run ();
	    return null;
	});
    }

    private <X> X write (Supplier<X> w) {
	if (concurrencyPolicy == ConcurrencyPolicy.SINGLE_THREAD_ONLY_UNSAFE)
	    return w.get ();
	// if the modification count has changed or if the write lock is currently being held
	// the tree may have been modified and any collected nodes may be invalid (relevant for iterators)
	lock.writeLock ().lock ();
	try {
	    // record a modification
	    modificationCount++;
	    return w.get ();
	} finally {
	    lock.writeLock ().unlock ();
	}
    }

    /**
     * Insert all elements in the supplied collection.
     * @param xs Collection of elements.
     */
    public void insertAll (Collection<T> xs) {
	//write (() -> xs.forEach (this::insert));
	insert (xs);
    }

    /**
     * Delete all elements in the supplied collection.
     * @param xs Collection of elements.
     */
    public void deleteAll (Collection<T> xs) {
	//write (() -> xs.forEach (this::delete));
	delete (xs);
    }

    private class RTreeUpdater implements TreeUpdater<T> {
	private Function<Node<T>, List<Node<T>>> splitFunction = (node) -> node.split (minBranchFactor, branchFactor,
										       converter);

	public void insert (T x) {
	    rTreeInsert (x, true);
	}

	public boolean delete (T x) {
	    return rTreeDelete (x);
	}

	public boolean update (T x, T y) {
	    return rTreeUpdate (x, y);
	}

	private void handleRootSplit (Node<T> newRoot) {
	    if (newRoot != root) {
		root = newRoot;
		height++;
		numRootSplitsCausedByLastOperation++;
		root.recomputeMBR (converter); // not necessary I think
	    }
	}

	private void handleRootSplit () {
	    if (root.size () > branchFactor) {
		//System.out.println("Splitting the root here");
		List<Node<T>> newNodes = splitFunction.apply (root);
		handleRootSplit (new InternalNode<> (newNodes.toArray ()));
	    }
	}

	private void reinsertNodeChildrenAtDepth (LeafNode<T> leaf, int height) {
	    for (T entry : leaf.getData ()) {
		rTreeInsert (entry, false);
	    }
	}

	private void reinsertNodeChildrenAtDepth (InternalNode<T> node, int height) {
	    for (Node<T> child : node.getData ()) {
		insertSubtree (child, height);
	    }
	}

	// depth here refers to the depth at which to reinsert the children
	private void reinsertNodeChildrenAtDepth (Node<T> node, int height) {
	    if (node instanceof LeafNode<T> leafNode) {
		reinsertNodeChildrenAtDepth (leafNode, height);
	    } else if (node instanceof InternalNode<T> iNode) {
		reinsertNodeChildrenAtDepth (iNode, height);
	    }
	}

	private void condenseTree (List<Node<T>> path, int minBranchFactor) {

	    // This removes the nodes that needs to be reinserted, and puts them in this list
	    List<Node<T>> nodeList = getNodesForReinsertion (path, minBranchFactor);
	    // the height of each node is indicated by its index in the list,
	    // indexes where the node was not too small, are padded with null

	    // nodeList.get(0) is unused, as the root shrinking is handled in its own case
	    // order by smallest branch to largest (smallest being leafs, largest being children of root)
	    int oldHeight = getHeight ();
	    for (int d = nodeList.size () - 1; d > 0; d--) {
		// d here is depth
		Node<T> node = nodeList.get (d);
		if (node != null) {
		    //int childNodeHeight = getHeight() - (d + 1);
		    int nodeHeight = getHeight () - d;
		    int subtreeHeight = nodeHeight - 1;
		    reinsertNodeChildrenAtDepth (node, subtreeHeight);
		}
	    }
	}

	private boolean rTreeDelete (T x) {
	    List<Node<T>> path = findLeafPath (x);
	    if (path.size () == 0)
		return false;
	    Node<T> leaf = path.get (path.size () - 1);
	    boolean removeSuccess = leaf.removeChild (x);

	    // Debug prints may be removed soon
	    if (!removeSuccess) {
		System.out.println ("Delete was called but a child was not found to be removed");
				/*System.out.println("Leaf is of  type: " + leaf.getClass());
				System.out.println("Find leaf path:");
				for(int i = 0; i < path.size(); i++) {
					System.out.println("Node of type " + path.get(i).getClass() + " at index " + i);
				}*/
		return false;
	    }
	    numLeafs--;
	    condenseTree (path, minBranchFactor);
	    handleRootShrink ();
	    return true;
	}

	private boolean rTreeUpdate (T toReplace, T newValue) {
	    boolean deleteSuccess = rTreeDelete (toReplace);
	    if (deleteSuccess)
		rTreeInsert (newValue, true);
	    return deleteSuccess;
	}

	// Original R-tree insert logic
	private void rTreeInsert (T x, boolean count) {
	    List<Node<T>> path = chooseLeafPath (x);
	    Node<T> leaf = path.get (path.size () - 1);

	    //System.out.println("Inserting into child");
	    if (!(leaf instanceof LeafNode)) {
		System.out.println ("Leaf is not instance of leaf!");
	    }

	    leaf.insertChild (x);
	    if (count)
		numLeafs++;

	    adjustTree (path, branchFactor);
	    handleRootSplit ();
	}

	// Helper for original R-tree insertion of subtrees (used for rebalancing during removal of nodes)
	private void insertSubtree (Node<T> x, int xHeight) {
	    List<Node<T>> path = chooseNodePath (x, xHeight);
	    Node<T> endpoint = path.get (path.size () - 1);
	    endpoint.insertChild (x);
	    // this shouldn't modify the number of leaves (unless something big bad is bugged)
	    adjustTree (path, branchFactor);
	    handleRootSplit ();
	}

	private Node<T> adjustTree (List<Node<T>> path, int maxBranchFactor) {
	    Node<T> ret = root;

	    for (int i = path.size () - 1; i > 0; i--) {
		Node<T> cur = path.get (i);
		Node<T> parent = path.get (i - 1);

		// just recompute mbr regardless for safety
		// order of MBR recomputes need to be from leaf -> ... -> root
		cur.recomputeMBR (converter);
		if (cur.size () > maxBranchFactor) {
		    //System.out.println("AdjustTree: Performing node split");
		    parent.removeChild (cur);
		    List<Node<T>> newNodes = splitFunction.apply (cur);
		    // insert both new children
		    parent.insertChild (newNodes.get (0));
		    parent.insertChild (newNodes.get (1));
		    // we know parent may never be a leaf node
		}
	    }
	    // recomp root mbr
	    root.recomputeMBR (converter);
	    return ret;
	}
    }

    private class RStarTreeUpdater implements TreeUpdater<T> {

	private Function<Node<T>, List<Node<T>>> splitFunction = (node) -> node.rStarSplit (minBranchFactor,
											    branchFactor, converter);

	public void insert (T x) {
	    rStarTreeInsertDataInternal (x, new HashSet<> (), true);
	}

	public boolean delete (T x) {
	    return rStarTreeDelete (x);
	}

	public boolean update (T x, T y) {
	    if (delete (x)) {
		insert (y);
		return true;
	    }
	    return false;
	}

	private void handleRootSplit (Node<T> newRoot) {
	    if (newRoot != root) {
		root = newRoot;
		height++;
		numRootSplitsCausedByLastOperation++;
		root.recomputeMBR (converter); // not necessary I think
	    }
	}

	private void handleRootSplit () {
	    if (root.size () > branchFactor) {
		//System.out.println("Splitting the root here");
		List<Node<T>> newNodes = splitFunction.apply (root);
		handleRootSplit (new InternalNode<> (newNodes.toArray ()));
	    }
	}

	private Node<T> rStarChooseSubtree (T x) {
	    MBR xMBR = root.getMBR (x, converter);
	    return root.RStarChooseSubtree (xMBR, converter);
	}

	private List<Node<T>> rStarChooseSubtreePath (T x) {
	    MBR xMBR = root.getMBR (x, converter);
	    List<Node<T>> path = new ArrayList<> ();
	    root.RStarChooseSubtreePath (xMBR, converter, path);
	    if (path.size () != getHeight ()) {
		System.out.println ("path size to height diff: " + (path.size () - getHeight ()));
		return null;
	    }
	    assert path.size () == getHeight ();
	    return path;
	}

	// Corresponds to R*-tree ChooseSubtree with a given level
	private List<Node<T>> rStarChooseSubtreePath (Node<T> x, int nodeHeight) {
	    MBR xMBR = x.getMBR (converter);
	    List<Node<T>> path = new ArrayList<> ();
	    int depth = getDepthFromNodeHeight (nodeHeight);
	    root.RStarChooseSubtreePath (xMBR, converter, path);
	    //Node<T> last = path.get (path.size () - 1);
	    //assertAllChildrenHaveSameClass (last);
	    return path.subList (0, depth);
	}

	private void assertAllChildrenHaveSameClass (Node<T> x) {
	    if (x instanceof InternalNode<T> iNode) {
		Class<?> t = null;
		if (iNode.size () > 0) {
		    t = iNode.getData ().get (0).getClass ();
		}
		for (Node<T> child : iNode.getData ()) {
		    assert child.getClass () == t;
		}
	    }
	}

	private void rStarTreeInsert (T x) {
	    rStarTreeInsertDataInternal (x, new HashSet<> (), true);
	}

	private void rStarTreeReInsert (T x) {
	    rStarTreeInsertDataInternal (x, new HashSet<> (), false);
	}

	private void rStarTreeInsertDataInternal (T x, Set<Integer> reinsertionLevelCache, boolean count) {

	    List<Node<T>> path = rStarChooseSubtreePath (x);
	    Node<T> leaf = path.get (path.size () - 1);

	    assert path.size () == getHeight ();
	    // this is risky
	    int nodeHeight = 1; // leaf nodes are of height 1 as they have children of type T (we count them as nodes)
	    int depth = getDepthFromNodeHeight (nodeHeight);
	    //System.out.println("Inserting into child");
	    if (!(leaf instanceof LeafNode)) {
		System.out.println ("Leaf is not instance of leaf!");
	    }
	    // reinsertion mechanism may be triggered here in the case where the leaf is full
	    leaf.insertChild (x);
	    if (count)
		numLeafs++;

	    //System.out.println("Calling propagateOverflow with a leaf");
	    propagateOverflow (leaf, reinsertionLevelCache, depth, nodeHeight, path);
	    handleRootSplit ();
	}

	private void rStarTreeInsertSubtreeInternal (Node<T> x, int nodeHeight, Set<Integer> reinsertionLevelCache) {

	    List<Node<T>> path = rStarChooseSubtreePath (x, nodeHeight);
	    Node<T> iNode = path.get (path.size () - 1);
	    int depth = getDepthFromNodeHeight (nodeHeight);
	    //System.out.println("Inserting into child");
	    if (!(iNode instanceof InternalNode<T>)) {
		System.out.println ("Attempting to insert subtree into a leaf!");
		System.out.println ("Path length: " + path.size ());
		System.out.println ("Height of tree: " + getHeight ());
		System.out.println ("Trying to insert " + x.getClass () + " with height " + nodeHeight + " into "
				    + iNode.getClass ());
		System.out.println (iNode.getClass ());
		assert false;
	    }
	    // reinsertion mechanism may be triggered here in the case where the leaf is full
	    iNode.insertChild (x);
	    //numLeafs++; // do not count insertion of subtrees as new leaves...
	    // inserted data doesn't pollute the overflowing node unless MBR is recomputed... and it may be recomputed
	    // shouldn't matter much though

	    //System.out.println("Calling propagateOverflow with an internal node");

	    propagateOverflow (iNode, reinsertionLevelCache, depth, nodeHeight, path);
	    handleRootSplit ();

	}

	private boolean rStarTreeDelete (T x) {
	    List<Node<T>> path = findLeafPath (x);
	    assert path.size () == getHeight ();
	    if (path.size () == 0)
		return false;
	    Node<T> leaf = path.get (path.size () - 1);
	    boolean removeSuccess = leaf.removeChild (x);

	    // Debug prints may be removed soon
	    if (!removeSuccess) {
		System.out.println ("Delete was called but a child was not found to be removed");
		return false;
	    }
	    numLeafs--;
	    rStarCondenseTree (path, minBranchFactor);

	    handleRootShrink ();
	    return true;
	}

	private void rStarReInserter (T x, int h, Set<Integer> levelCache) {
	    rStarTreeInsertDataInternal (x, levelCache, false);
	}

	private void rStarInserter (Node<T> x, int h, Set<Integer> levelCache) {
	    rStarTreeInsertSubtreeInternal (x, h, levelCache);
	}

	private void reinsert (Node<T> node, int nodeHeight, Set<Integer> levelCache, List<Node<T>> insertionPath) {
	    int depth = getDepthFromNodeHeight (nodeHeight);
	    // add depth to cache so that no more reinsertions on this level can happen
	    levelCache.add (nodeHeight);
	    //System.out.printf("Reinsert is called: %d times in total\n", reinsertCalls);
	    if (node instanceof LeafNode<T> leafNode) {

		BiConsumer<T, Integer> inserter = (t, i) -> rStarTreeInsertDataInternal (t, levelCache, false);

		List<T> toReinsert = leafNode.getAndRemoveReinsertionCandidates (0.3f, minBranchFactor, branchFactor,
										 converter);

		// either this count is removed before reinsertion is called, or reinsertion doesnt increment the count
		//numLeafs -= toReinsert.size();

		// MBRs need to be updated when p% of entries disappear from the node
		propagateMBRChanges (insertionPath);
		// insert all nodes in either asc or desc order (close or far reinsertion)
		closeReinsert (toReinsert, nodeHeight - 1, inserter);

	    } else if (node instanceof InternalNode<T> iNode) {

		BiConsumer<Node<T>, Integer> inserter = (t, i) -> rStarTreeInsertSubtreeInternal (t, i, levelCache);

		List<Node<T>> toReinsert = iNode.getAndRemoveReinsertionCandidates (0.3f, minBranchFactor, branchFactor,
										    converter);
		//numLeafs -= toReinsert.size(); // internalNode doesn't cause diff in number of leaves
		// MBRs need to be updated when p% of entries disappear from the node
		propagateMBRChanges (insertionPath);

		// insert all nodes in either asc or desc order (close or far reinsertion)
		closeReinsert (toReinsert, nodeHeight - 1, inserter);

	    } else {
		System.out.println ("reinsertion: Node neither leaf or internal.");
	    }
	}

	// this can probably not even be used with the current structure
	private <N, X extends NodeBase<N, T>> void reinsertRefactor (X node, int nodeHeight, Set<Integer> levelCache,
								    List<Node<T>> insertionPath,
								    BiConsumer<N, Integer> inserter) {
	    int depth = getDepthFromNodeHeight (nodeHeight);
	    // add depth to cache so that no more reinsertions on this level can happen
	    levelCache.add (depth);

	    // these two are moved out
	    //BiConsumer<T, Integer> inserter = (t, i) -> rStarTreeInsertDataInternal(t, levelCache);
	    //BiConsumer<Node<T>, Integer> inserter = (t, i) -> rStarTreeInsertSubtreeInternal(t, i, levelCache);
	    //BiConsumer<N, Integer> inserter = (t, i) -> rStarInserter(t, i, levelCache);

	    List<N> toReinsert = node.getAndRemoveReinsertionCandidates (0.3f, minBranchFactor, branchFactor,
									 converter);
	    // MBRs need to be updated when p% of entries disappear from the node
	    propagateMBRChanges (insertionPath);
	    // insert all nodes in either asc or desc order (close or far reinsertion)
	    closeReinsert (toReinsert, nodeHeight, inserter);
	}

	private <X> void farReinsert (List<X> maxSortedNodeList, int nodeHeight, BiConsumer<X, Integer> inserter) {
	    // far reinsert means reinserting nodes with largest distance from center first
	    // in other words go through the input list sequentially
	    for (X x : maxSortedNodeList) {
		inserter.accept (x, nodeHeight);
	    }
	}

	private <X> void closeReinsert (List<X> maxSortedNodeList, int nodeHeight, BiConsumer<X, Integer> inserter) {
	    // close reinsert means reinserting nodes with smallest distance from center first
	    // in other words go through the input list sequentially
	    for (int i = maxSortedNodeList.size () - 1; i >= 0; i--) {
		inserter.accept (maxSortedNodeList.get (i), nodeHeight);
	    }
	}

	// meant to function for R*-tree like condenseTree for original R-tree
	// an insertion should cause at most one reinsertion-routine per level
	// this means one insert should pass along a cache to all insertions caused by it
	// if reinsertion is called, a split may occur (rendering the path list possibly invalid)
	// if a reinsertion is to be done at some level, nodes need to be extracted from that overfull node
	// then MBRs should be updated, finally reinsertions should be done (and then path list shouldn't be used anymore)
	// split and reinsertion are mutually exclusive

	// the reason why one insertion should not be allowed to cause multiple reinsertion-calls on the same level,
	// is, among other things, it could result in an infinite loop of reinserting elements into the same node
	// so the flow insert -> overflow -> reinsert needs to share a level-cache, telling whether a reinsert
	// has been done on a level before.

	// R*-tree algorithm to propagate splits and perform reinsertions if applicable
	// If no split or reinsertion is needed, then MBRs on the path are simply updated, as intended
	private Node<T> propagateOverflow (Node<T> x, Set<Integer> levelCache, int level, int xNodeHeight,
					  List<Node<T>> path) {

	    //System.out.printf("propagateOverflow: tree size is now %d\n", numLeafs);
	    Node<T> ret = root;
	    assert getHeight () - level == xNodeHeight;

	    // current design poses a problem:
	    // need to update all associated MBRs (the path)
	    // before reinsertion starts
	    // or the path may be invalidated (no longer exist in the tree)
	    // TODO: check whether the depth parameter is the same in original R-tree implementation
	    // if they are the same then it will work here too

	    // Nodes will not change height on this path
	    // therefore it is best if we cache getHeight
	    int oldHeight = getHeight ();
	    for (int d = path.size () - 1; d > 0; d--) {
		int oldCurHeight = getHeight () - d;
		int curHeight = oldHeight - d;
		assert curHeight >= 1;
		Node<T> parent = path.get (d - 1);
		Node<T> cur = path.get (d);

		cur.recomputeMBR (converter);
		if (cur.size () > branchFactor) {
		    if (!levelCache.contains (curHeight)) {
			// a reinsert may cause the tree to grow, not shrink
			// therefore reinsertion at a specific level (a depth)
			// tldr a depth in the case seems more reasonable
			//System.out.printf("levelCache didn't contain curHeight = %d\n", curHeight);
			// after choosing reinsertion instead of splitting, we can exit the loop (and our path list may be invalid)
			reinsert (cur, curHeight, levelCache, path);
			// reinsert -> all children reinserted into cur -> split
			// this means pointer cur may be invalid / useless after the call to reinsert
			// as all cases are handled by recursive insertion, there needs to be no condition
			// to break
			break;
			// the "else" case is that all nodes were reinserted into the same node again upon reinsertion
			// in this case the split is apparently necessary
			// OR the "else" case may be that a reinsertion has already been done with this node height
			// A design decision here is whether to: remember node height or depth
			// for depth: a depth will always correspond to a specific level of the tree
			// the authors write level, so it should correspond to depth
		    }
		    boolean removeSuccess = parent.removeChild (cur);
		    if (!removeSuccess) {
			System.out.println ("###################");
			System.out.println ("FAILED TO REMOVE NODE in propagateOverflow");
			System.out.println ("###################");
		    }
		    //System.out.println("Splitting current node here");
		    List<Node<T>> newNodes = splitFunction.apply (cur);
		    parent.insertChild (newNodes.get (0));
		    parent.insertChild (newNodes.get (1));

		}
	    }
	    // this needs recomputation unless early exit (reinsert) happened
	    root.recomputeMBR (converter);
	    return ret;
	}

	private void rStarReinsertNodeChildrenAtDepth (LeafNode<T> leaf, int depth) {
	    for (T entry : leaf.getData ()) {
		rStarTreeReInsert (entry);
	    }
	}

	private void rStarReinsertNodeChildrenAtDepth (InternalNode<T> node, int height) {
	    for (Node<T> child : node.getData ()) {
		// these may lead to a lot of reinsertions, but there is nothing in the paper indicating
		// that it should be forbidden
		rStarTreeInsertSubtreeInternal (child, height, new HashSet<> ());
	    }
	}

	private void rStarReinsertNodeChildrenAtDepth (Node<T> node, int height) {
	    if (node instanceof LeafNode<T> leafNode) {
		rStarReinsertNodeChildrenAtDepth (leafNode, height);
	    } else if (node instanceof InternalNode<T> iNode) {
		rStarReinsertNodeChildrenAtDepth (iNode, height);
	    }
	}

	private void rStarCondenseTree (List<Node<T>> path, int minBranchFactor) {

	    // this extracts nodes from the tree that are too small, and puts them in a list
	    List<Node<T>> nodeList = getNodesForReinsertion (path, minBranchFactor);
	    // nodeList.get(0) is unused, as the root shrinking is handled in its own case
	    // order by smallest branch to largest (smallest being leafs, largest being children of root)
	    int oldHeight = getHeight ();
	    for (int d = nodeList.size () - 1; d > 0; d--) {
		// the nodes we are working on in this loop, are outside the tree pointed to by root.
		Node<T> node = nodeList.get (d);
		int subtreeHeight = oldHeight - d;
		// previous bug here: height of this tree may differ between iterations here, but the height
		// if the nodes to be reinserted, stay the same.
		// the solution here is to cache the height, use an old height of the tree,
		// to determine the height of subtrees.
		// this is counterintuitive
		int childHeight = subtreeHeight - 1;
		// we need to calculate the height of nodes here
		if (node != null) {
		    // Height "oldHeight" is snapshotted here to be able to determine subtree heights.
		    // The height of a node outside of this tree, remains constant.
		    // After a few levels of method calls, the actual depth to insert at, is determined
		    // from a current height of the tree and the height of the subtree to be reinserted.
		    rStarReinsertNodeChildrenAtDepth (node, childHeight);
		}
	    }
	}
    }

    private void propagateMBRChanges (List<Node<T>> path) {
	for (int i = path.size () - 1; i >= 0; i--) {
	    Node<T> cur = path.get (i);
	    cur.recomputeMBR (converter);
	}
    }

    // TODO: use this
    private void removeChildAndSplit (Node<T> parent, Node<T> child, Function<Node<T>, List<Node<T>>> splitter) {
	boolean removeSuccess = parent.removeChild (child);
	if (!removeSuccess) {
	    System.out.println ("###################");
	    System.out.println ("FAILED TO REMOVE NODE");
	    System.out.println ("###################");
	}
	List<Node<T>> newNodes = splitter.apply (child);
	parent.insertChild (newNodes.get (0));
	parent.insertChild (newNodes.get (1));
    }

    // end R*-tree

    private void getNodesForReinsertion (List<Node<T>> path, int minBranchFactor, List<List<Node<T>>> nodeList) {
	// It is OK that this runs 0 times if root is a leaf (If root is a leaf then it cant be condensed)
	// If root is an internal node, it may run and cause root to have 1 child
	for (int i = path.size () - 1; i > 0; i--) {
	    Node<T> cur = path.get (i);
	    Node<T> parent = path.get (i - 1);

	    if (cur.size () < minBranchFactor) {
		//System.out.println("Condensing tree...");
		nodeList.get (i).add (cur);
		// need to remove cur as we are to reinsert all it's children after this
		boolean removeSuccess = parent.removeChild (cur);
		if (!removeSuccess) {
		    System.out.println ("###################### failed to remove child ######################");
		}
	    }
	    parent.recomputeMBR (converter);
	}
    }

    private List<Node<T>> getNodesForReinsertion (List<Node<T>> path, int minBranchFactor) {
	// It is OK that this runs 0 times if root is a leaf (If root is a leaf then it cant be condensed)
	// If root is an internal node, it may run and cause root to have 1 child
	List<Node<T>> nodeList = new ArrayList<> (path.size ());
	// this is due to lists being ordered root, ..., leaf
	for (int i = 0; i < path.size (); i++) {
	    nodeList.add (null);
	}

	for (int i = path.size () - 1; i > 0; i--) {
	    Node<T> cur = path.get (i);
	    Node<T> parent = path.get (i - 1);

	    if (cur.size () < minBranchFactor) {
		nodeList.set (i, cur); // record node depth using index i, in case of invalid tree
		// need to remove cur as we are to reinsert all it's children after this
		boolean removeSuccess = parent.removeChild (cur);
		if (!removeSuccess) {
		    System.out.println ("###################### failed to remove child ######################");
		}
	    }
	    parent.recomputeMBR (converter);
	}
	return nodeList;
    }

    public Iterable<T> find (T x) {
	MBR query = new SimpleMBR (x, converter);
	return find (query);
    }

    protected Finder findWithDetails (T x) {
	return read (() -> new Finder (new SimpleMBR (x, converter), new AcceptAll<> ()));
    }

    /**
     * Find all objects that intersect the point (x,y).
     * @param x x-coordinate
     * @param y y-coordinate
     * @param resultNodes List to put the found objects in.
     */
    public void find (double x, double y, List<T> resultNodes) {
	find (new SimpleMBR (x, x, y, y), resultNodes, new AcceptAll<> ());
    }

    /**
     * Finds all objects that intersect the given rectangle and stores the found node in the given list. Note, this find
     * method will only use two dimensions, no matter how many dimensions the PRTree actually has.
     * @param xmin the minimum value of the x coordinate when searching
     * @param ymin the minimum value of the y coordinate when searching
     * @param xmax the maximum value of the x coordinate when searching
     * @param ymax the maximum value of the y coordinate when searching
     * @param resultNodes the list that will be filled with the result
     */
    public void find (double xmin, double ymin, double xmax, double ymax, List<T> resultNodes) {
	find (new SimpleMBR (xmin, xmax, ymin, ymax), resultNodes, new AcceptAll<> ());
    }

    /**
     * Finds all objects that intersect the given rectangle and stores the found node in the given list. Note, this find
     * method will only use two dimensions, no matter how many dimensions the PRTree actually has.
     * @param xmin the minimum value of the x coordinate when searching
     * @param ymin the minimum value of the y coordinate when searching
     * @param xmax the maximum value of the x coordinate when searching
     * @param ymax the maximum value of the y coordinate when searching
     * @param resultNodes the list that will be filled with the result
     * @param filter a secondary filter to apply
     */
    public void find (double xmin, double ymin, double xmax, double ymax, List<T> resultNodes, Predicate<T> filter) {
	find (new SimpleMBR (xmin, xmax, ymin, ymax), resultNodes, filter);
    }

    /**
     * Finds all objects that intersect the given rectangle and stores the found node in the given list.
     * @param query the bounds of the query
     * @param resultNodes the list that will be filled with the result
     */
    public void find (MBR query, List<T> resultNodes) {
	find (query, resultNodes, new AcceptAll<> ());
    }

    /**
     * Finds all objects that intersect the given rectangle and stores the found node in the given list.
     * @param query the bounds of the query
     * @param resultNodes the list that will be filled with the result
     * @param filter a secondary filter to apply to the found nodes
     */
    public void find (MBR query, List<T> resultNodes, Predicate<T> filter) {
	validateRect (query);
	if (filter == null)
	    throw new NullPointerException ("Filter may not be null");
	read (() -> root.find (query, converter, resultNodes, filter));
    }

    /**
     * Find all objects that intersect the given rectangle. Note, this find method will only use two dimensions, no
     * matter how many dimensions the PRTree actually has.
     * @param xmin the minimum value of the x coordinate when searching
     * @param ymin the minimum value of the y coordinate when searching
     * @param xmax the maximum value of the x coordinate when searching
     * @param ymax the maximum value of the y coordinate when searching
     * @return an iterable of the elements inside the query rectangle
     * @throws IllegalArgumentException if xmin &gt; xmax or ymin &gt; ymax
     */
    public Iterable<T> find (double xmin, double ymin, double xmax, double ymax) {
	return find (xmin, ymin, xmax, ymax, new AcceptAll<> ());
    }

    /**
     * Find all objects that intersect the given rectangle. Note, this find method will only use two dimensions, no
     * matter how many dimensions the PRTree actually has.
     * @param xmin the minimum value of the x coordinate when searching
     * @param ymin the minimum value of the y coordinate when searching
     * @param xmax the maximum value of the x coordinate when searching
     * @param ymax the maximum value of the y coordinate when searching
     * @param filter a secondary filter to apply to the found nodes
     * @return an iterable of the elements inside the query rectangle
     * @throws IllegalArgumentException if xmin &gt; xmax or ymin &gt; ymax
     */
    public Iterable<T> find (double xmin, double ymin, double xmax, double ymax, Predicate<T> filter) {
	return find (new SimpleMBR (xmin, xmax, ymin, ymax), filter);
    }

    /**
     * Find all objects that intersect the given rectangle.
     * @param query the bounds of the query
     * @return an iterable of the elements inside the query rectangle
     * @throws IllegalArgumentException if xmin &gt; xmax or ymin &gt; ymax
     */
    public Iterable<T> find (final MBR query) {
	return find (query, new AcceptAll<> ());
    }

    /**
     * Find all objects that intersect the given rectangle.
     * @param query the bounds of the query
     * @param filter a secondary filter to apply to the found nodes
     * @return an iterable of the elements inside the query rectangle
     * @throws IllegalArgumentException if xmin &gt; xmax or ymin &gt; ymax
     */
    public Iterable<T> find (final MBR query, final Predicate<T> filter) {
	validateRect (query);
	if (filter == null)
	    throw new NullPointerException ("Filter may not be null");
	return () -> read (() -> new Finder (query, filter));
    }

    private void validateRect (MBR query) {
	for (int i = 0; i < converter.getDimensions (); i++) {
	    double max = query.getMax (i);
	    double min = query.getMin (i);
	    if (max < min)
		throw new IllegalArgumentException (
			"max: " + max + " < min: " + min + ", axis: " + i + ", query: " + query);
	}
    }

    protected class Finder implements Iterator<T> {
	private final MBR mbr;
	private final Predicate<T> filter;

	private List<T> ts = new ArrayList<> ();
	private List<Node<T>> toVisit = new ArrayList<> ();
	private T next;

	public int visitedNodes = 0;
	public int dataNodesVisited = 0;

	private final int localModificationCount;

	public Finder (MBR mbr, Predicate<T> filter) {
	    localModificationCount = modificationCount;
	    this.mbr = mbr;
	    this.filter = filter;
	    toVisit.add (root);
	    findNext ();
	}

	// Iterator will not allow for concurrent modifications
	public void checkConcurrentModification () {
	    if (lock.isWriteLocked () || localModificationCount != modificationCount) {
		throw new ConcurrentModificationException ("PRTree was modified while consuming a lazy iterator"
							   + "that assumes the tree is remains unchanged until "
							   + "the iterator has been emptied. "
							   + "Consider collecting results into a list as this "
							   + "avoids any problems with concurrent modifications.");
	    }
	}

	public boolean hasNext () {
	    return next != null;
	}

	public T next () {
	    checkConcurrentModification ();
	    T toReturn = next;
	    findNext ();
	    return toReturn;
	}

	private void findNext () {
	    while (ts.isEmpty () && !toVisit.isEmpty ()) {
		Node<T> n = toVisit.remove (toVisit.size () - 1);
		visitedNodes++;
		n.expand (mbr, filter, converter, ts, toVisit);
	    }
	    if (ts.isEmpty ()) {
		next = null;
	    } else {
		next = ts.remove (ts.size () - 1);
		dataNodesVisited++;
	    }
	}

	public void remove () {
	    throw new UnsupportedOperationException ("Not implemented");
	}
    }

    /**
     * Get the nearest neighbour of the given point
     * @param dc the DistanceCalculator to use.
     * @param filter a NodeFilter that can be used to ignore some leaf nodes.
     * @param maxHits the maximum number of entries to find.
     * @param p the point to find the nearest neighbour to.
     * @return A List of DistanceResult with up to maxHits results. Will return an empty list if this tree is empty.
     */
    public List<DistanceResult<T>> nearestNeighbour (DistanceCalculator<T> dc, Predicate<T> filter, int maxHits,
						     PointND p) {
	return read (() -> nearestNeighbourUnsafe (dc, filter, maxHits, p));
    }

    private List<DistanceResult<T>> nearestNeighbourUnsafe (DistanceCalculator<T> dc, Predicate<T> filter, int maxHits,
							    PointND p) {
	if (isEmpty ())
	    return Collections.emptyList ();
	NearestNeighbour<T> nn = new NearestNeighbour<> (converter, filter, maxHits, root, dc, p);
	return nn.find ();
    }

    /**
     * Get the nearest neighbour of the given point
     * @param dc the DistanceCalculator to use.
     * @param maxHits the maximum number of entries to find.
     * @param p the point to find the nearest neighbour to.
     * @return A List of DistanceResult with up to maxHits results. Will return an empty list if this tree is empty.
     */
    public List<DistanceResult<T>> nearestNeighbour (DistanceCalculator<T> dc, int maxHits, PointND p) {
	return nearestNeighbour (dc, new AcceptAll<> (), maxHits, p);
    }

    /**
     * Get the nearest neighbour of the given point
     * @param dc the DistanceCalculator to use.
     * @param p the point to find the nearest neighbour to.
     * @return A List of DistanceResult with up to maxHits results. Will return an empty list if this tree is empty.
     */
    public List<DistanceResult<T>> nearestNeighbour (DistanceCalculator<T> dc, PointND p) {
	return nearestNeighbour (dc, new AcceptAll<> (), 1, p);
    }
}
