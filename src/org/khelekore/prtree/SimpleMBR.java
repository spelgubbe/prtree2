package org.khelekore.prtree;

import java.util.List;

/**
 * An implementation of MBRND that keeps a double array with the max and min values
 *
 * <p>Please note that you should not normally use this class when PRTree
 * wants a MBR since this will actually use a lot of extra memory.
 */
public class SimpleMBR implements MBR {
    private final double[] values;

    private SimpleMBR (int dimensions) {
	values = new double[dimensions * 2];
    }

    /**
     * Create a new SimpleMBR using the given double values for max and min. Note that the values should be stored as
     * min, max, min, max.
     * @param values the min and max values for each dimension.
     */
    public SimpleMBR (double... values) {
	this.values = values.clone ();
    }

    /**
     * Create a new SimpleMBR from a given object and a MBRConverter
     * @param t the object to create the bounding box for
     * @param converter the actual MBRConverter to use
     */
    public <T> SimpleMBR (T t, MBRConverter<T> converter) {
	int dims = converter.getDimensions ();
	values = new double[dims * 2];
	int p = 0;
	for (int i = 0; i < dims; i++) {
	    values[p++] = converter.getMin (i, t);
	    values[p++] = converter.getMax (i, t);
	}
    }

    public <T> SimpleMBR (MBR mbr) {
	int dims = mbr.getDimensions ();
	values = new double[dims * 2];
	int p = 0;
	for (int i = 0; i < dims; i++) {
	    values[p++] = mbr.getMin (i);
	    values[p++] = mbr.getMax (i);
	}
    }

    public SimpleMBR (List<MBR> lst, int dims) {
	values = new double[dims * 2];
	MBR first = lst.get (0);
	// Build initial MBR to not have all zeros (which would be incorrect).
	for (int d = 0; d < dims; d++) {
	    values[2 * d] = first.getMin (d);
	    values[2 * d + 1] = first.getMax (d);
	}
	for (int i = 1; i < lst.size (); i++) {
	    MBR t = lst.get (i);
	    for (int d = 0; d < dims; d++) {
		values[2 * d] = Math.min (values[2 * d], t.getMin (d));
		values[2 * d + 1] = Math.max (values[2 * d + 1], t.getMax (d));
	    }
	}
    }

    public int getDimensions () {
	return values.length / 2;
    }

    public double getMin (int axis) {
	return values[axis * 2];
    }

    public double getMax (int axis) {
	return values[axis * 2 + 1];
    }

    public MBR union (MBR mbr) {
	int dims = getDimensions ();
	SimpleMBR n = new SimpleMBR (dims);
	int p = 0;
	for (int i = 0; i < dims; i++) {
	    n.values[p] = Math.min (getMin (i), mbr.getMin (i));
	    p++;
	    n.values[p] = Math.max (getMax (i), mbr.getMax (i));
	    p++;
	}
	return n;
    }

    public double getUnionArea (MBR mbr) {
	int dims = getDimensions ();
	double area = 1.0;
	for (int i = 0; i < dims; i++) {
	    area *= Math.max (getMax (i), mbr.getMax (i)) - Math.min (getMin (i), mbr.getMin (i));
	}
	return area;
    }

    // calculate intersection similar to how union is calculated
    public MBR intersection (MBR mbr) {
	int dims = getDimensions ();
	SimpleMBR n = new SimpleMBR (dims);
	// seems safest to return a 0-mbr if no intersection, or this may cause errors to propagate
	if (intersects (mbr)) {
	    int p = 0;
	    for (int i = 0; i < dims; i++) {
		n.values[p] = Math.max (getMin (i), mbr.getMin (i));
		p++;
		n.values[p] = Math.min (getMax (i), mbr.getMax (i));
		p++;
	    }
	}
	return n;
    }

    public double getIntersectionArea (MBR other) {
	double intersectionArea = 1.0;
	if (!intersects (other)) {
	    return 0.0;
	} else {
	    // if intersect we know: other.getMax(i) >= getMin(i) && other.getMin(i) <= getMax(i)
	    for (int i = 0; i < getDimensions (); i++) {
		// given there is an intersection:
		// intersection starts from the max of the min-coordinates in the axis
		// intersection ends at the min of the max-coordinates in the axis
		double maxMin = Math.max (getMin (i), other.getMin (i));
		double minMax = Math.min (getMax (i), other.getMax (i));
		double range = minMax - maxMin;
		intersectionArea *= range;
	    }
	    return intersectionArea;
	}
    }

    public double margin () {
	double sum = 0.0;
	for (int i = 0; i < getDimensions (); i++) {
	    sum += 2 * (getMax (i) - getMin (i));
	}
	assert sum >= 0;
	return sum;
    }

    public double[] center () {
	int dims = getDimensions ();
	double[] centerCoords = new double[dims];
	for (int i = 0; i < dims; i++) {
	    // bugfix: this previously calculated the edge length instead of center
	    double maxMinDiff = getMax (i) - getMin (i);
	    centerCoords[i] = getMin (i) + maxMinDiff / 2;
	}
	return centerCoords;
    }

    public double centerDistance (MBR other) {
	double[] thisCenter = center ();
	double[] otherCenter = other.center ();
	double dist = 0.0;
	// calculate euclidean distance between the two center points
	for (int i = 0; i < getDimensions (); i++) {
	    dist += (thisCenter[i] - otherCenter[i]) * (thisCenter[i] - otherCenter[i]);
	}
	return dist;
    }

    public boolean intersects (MBR other) {
	for (int i = 0; i < getDimensions (); i++) {
	    if (other.getMax (i) < getMin (i) || other.getMin (i) > getMax (i))
		return false;
	}
	return true;
    }

    public <T> boolean intersects (T t, MBRConverter<T> converter) {
	for (int i = 0; i < getDimensions (); i++) {
	    if (converter.getMax (i, t) < getMin (i) || converter.getMin (i, t) > getMax (i))
		return false;
	}
	return true;
    }

    @Override
    public String toString () {
	return getClass ().getSimpleName () + "{values: " + java.util.Arrays.toString (values) + "}";
    }

    public double getArea () {
	// this will break in multiple dims, using log sum works too for the use case
	double area = 1.0;
	for (int i = 0; i < getDimensions (); i++) {
	    double range = getMax (i) - getMin (i);
	    area *= range;
	}
	return area;
    }
}
