package org.khelekore.prtree;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

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

/** Measures the complete R*-tree split decision for one overflowing node. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class RStarSplitBenchmark {
    @Param({"8", "32", "128"})
    public int branchFactor;

    private NodeSplitter<Box> splitter;
    private NodeComparators<Box> comparators;
    private BiFunction<Box, MBRConverter<Box>, MBR> mbrProvider;
    private List<Box> entries;

    @Setup
    public void setup () {
	MBRConverter<Box> converter = new BoxConverter ();
	splitter = new NodeSplitter<> (converter);
	comparators = new DataComparators<> (converter);
	mbrProvider = (box, boxConverter) -> new SimpleMBR (box, boxConverter);
	entries = createEntries (branchFactor + 1);
    }

    @Benchmark
    public Object split () {
	int minBranchFactor = Math.max (2, branchFactor / 4);
	return splitter.rStarSplit (entries, minBranchFactor, branchFactor, mbrProvider, comparators);
    }

    private List<Box> createEntries (int size) {
	SplittableRandom random = new SplittableRandom (0x5eedL);
	List<Box> result = new ArrayList<> (size);
	for (int i = 0; i < size; i++) {
	    double x = random.nextDouble (0.0, 1_000.0);
	    double y = random.nextDouble (0.0, 1_000.0);
	    double width = random.nextDouble (0.1, 10.0);
	    double height = random.nextDouble (0.1, 10.0);
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
