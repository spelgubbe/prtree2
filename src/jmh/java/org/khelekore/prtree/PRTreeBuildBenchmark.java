package org.khelekore.prtree;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Measures the current serial and parallel PR-tree bulk-loading paths. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class PRTreeBuildBenchmark {
    @Param({"1000", "10000", "100000"})
    public int entryCount;

    @Param({"32"})
    public int branchFactor;

    private MBRConverter<Box> converter;
    private List<Box> entries;

    @Setup
    public void setup () {
	converter = new BoxConverter ();
	entries = createEntries (entryCount);
    }

    @Benchmark
    public PRTree<Box> load () {
	PRTree<Box> tree = new PRTree<> (converter, branchFactor);
	tree.load (entries);
	return tree;
    }

    @Benchmark
    public PRTree<Box> loadParallel () {
	PRTree<Box> tree = new PRTree<> (converter, branchFactor);
	tree.loadParallel (entries);
	return tree;
    }

    private List<Box> createEntries (int size) {
	SplittableRandom random = new SplittableRandom (0x5eedL);
	List<Box> result = new ArrayList<> (size);
	for (int i = 0; i < size; i++) {
	    double x = random.nextDouble (0.0, 1_000_000.0);
	    double y = random.nextDouble (0.0, 1_000_000.0);
	    double width = random.nextDouble (0.1, 100.0);
	    double height = random.nextDouble (0.1, 100.0);
	    result.add (new Box (x, x + width, y, y + height));
	}
	return result;
    }

    private record Box(double xmin, double xmax, double ymin, double ymax) {
    }

    private static class BoxConverter implements MBRConverter<Box> {
        @Override
	public int getDimensions () {
	    return 2;
	}

	@Override
	public double getMin (int axis, Box box) {
	    return axis == 0 ? box.xmin () : box.ymin ();
	}

	@Override
	public double getMax (int axis, Box box) {
	    return axis == 0 ? box.xmax () : box.ymax ();
	}
    }
}
